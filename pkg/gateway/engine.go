package gateway

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"

	"helix-gateway/internal/config"
	"helix-gateway/internal/keymgmt"
	"helix-gateway/internal/logger"
	"helix-gateway/internal/metrics"
	"helix-gateway/internal/proxy"
	"helix-gateway/internal/recovery"
	"helix-gateway/internal/server"
)

var (
	Version   = "2.1.0"
	GitCommit = "none"
	BuildTime = "unknown"
)

type responseLogger struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int64
}

func (rl *responseLogger) WriteHeader(code int) {
	// Record only the first status: net/http ignores later calls, so the log
	// should report what the client actually received.
	if rl.statusCode == 0 {
		rl.statusCode = code
	}
	rl.ResponseWriter.WriteHeader(code)
}

func (rl *responseLogger) Write(b []byte) (int, error) {
	if rl.statusCode == 0 {
		rl.statusCode = http.StatusOK
	}
	rl.bytesWritten += int64(len(b))
	return rl.ResponseWriter.Write(b)
}

func (rl *responseLogger) Flush() {
	if f, ok := rl.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func detailedLoggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-ID, Accept, Origin, Cache-Control")
		w.Header().Set("Access-Control-Expose-Headers", "X-Request-ID")
		// Access-Control-Allow-Credentials is deliberately absent: the fetch
		// spec rejects it together with a wildcard origin, and this gateway
		// authenticates with a bearer header rather than a cookie.
		w.Header().Set("Access-Control-Max-Age", "86400")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			slog.Info("HTTP OPTIONS preflight handled", "path", r.URL.Path, "remote", r.RemoteAddr)
			return
		}

		// Request and response bodies are never logged. They carry conversation
		// content and user data, and the redacting handler cannot catch them
		// because the payload sits in one string value rather than a named
		// credential field.
		slog.Info(">>> INCOMING HTTP REQUEST",
			"method", r.Method,
			"path", r.URL.Path,
			"query", logger.RedactQuery(r.URL.RawQuery),
			"remote", r.RemoteAddr,
			"content_type", r.Header.Get("Content-Type"),
			"content_length", r.ContentLength,
		)

		start := time.Now()
		rl := &responseLogger{ResponseWriter: w}

		next.ServeHTTP(rl, r)

		slog.Info("<<< OUTGOING HTTP RESPONSE",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rl.statusCode,
			"bytes", rl.bytesWritten,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}

type Engine struct {
	cfg     *config.Config
	srv     *server.Server
	keyMgr  *keymgmt.Manager
	cancel  context.CancelFunc
	ctx     context.Context
	mu      sync.Mutex
	running bool
}

func StartEngine(getenv func(string) string) (*Engine, error) {
	cfg, err := config.Load(getenv)
	if err != nil {
		return nil, fmt.Errorf("configuration error: %w", err)
	}

	logger.Init(cfg.LogLevel, os.Stdout)
	slog.Info("starting helix-gateway engine",
		"version", Version,
		"git_commit", GitCommit,
		"build_time", BuildTime,
		"port", cfg.Port,
		"upstream", cfg.UpstreamBaseURL,
		"wrapper_mode", cfg.WrapperMode,
		"recovery_policy", cfg.RecoveryPolicy,
		"empty_retries", cfg.UpstreamEmptyRetries,
	)

	keyMgr, err := keymgmt.NewManager(cfg.KeyDBPath, cfg.KeyHMACSecret, cfg.StaticKeys)
	if err != nil {
		return nil, fmt.Errorf("failed to initialize key manager: %w", err)
	}

	upstreamClient := proxy.NewUpstreamClient(cfg)
	orch := recovery.NewOrchestrator(cfg, upstreamClient, keyMgr)
	if err := orch.AntiRouteError(); err != nil {
		keyMgr.Close()
		return nil, fmt.Errorf("anti-route configuration error: %w", err)
	}
	proxyHandler := proxy.NewProxyHandler(cfg, keyMgr, upstreamClient)
	proxyHandler.SetChatHandler(orch.HandleChatCompletions)
	adminHandler := keymgmt.NewAdminHandler(cfg.AdminAPIKey, keyMgr)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		server.WriteJSON(w, http.StatusOK, map[string]string{
			"status":  "ok",
			"version": Version,
		})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		server.WriteJSON(w, http.StatusOK, map[string]string{
			"status":  "ready",
			"version": Version,
		})
	})
	mux.HandleFunc("GET /metrics", metrics.Default.Handler())
	proxyHandler.RegisterRoutes(mux)
	adminHandler.RegisterRoutes(mux)

	handlerWithMiddlewares := detailedLoggingMiddleware(mux)
	srv := server.New(cfg, handlerWithMiddlewares)
	if err := srv.Start(); err != nil {
		keyMgr.Close()
		return nil, fmt.Errorf("failed to start server listener on port %d: %w", cfg.Port, err)
	}

	// A Serve error after the listener is up would otherwise only reach a log
	// line, so surface it through the server's channel as well.
	go func() {
		if serveErr, ok := <-srv.ServeErr(); ok && serveErr != nil {
			slog.Error("http server stopped unexpectedly", "err", serveErr)
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	engine := &Engine{
		cfg:     cfg,
		srv:     srv,
		keyMgr:  keyMgr,
		cancel:  cancel,
		ctx:     ctx,
		running: true,
	}

	return engine, nil
}

func (e *Engine) Stop() error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if !e.running {
		return nil
	}
	e.running = false
	e.cancel()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var err error
	if e.srv != nil {
		err = e.srv.Shutdown(shutdownCtx)
	}
	if e.keyMgr != nil {
		_ = e.keyMgr.Close()
	}
	slog.Info("gateway engine stopped cleanly")
	return err
}

func (e *Engine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.running
}

func (e *Engine) Addr() string {
	if e.srv != nil {
		return e.srv.Addr()
	}
	return ""
}
