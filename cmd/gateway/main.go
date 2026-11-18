package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
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
	Version   = "2.0.1"
	GitCommit = "none"
	BuildTime = "unknown"
)

type responseLogger struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int64
}

func (rl *responseLogger) WriteHeader(code int) {
	if rl.statusCode != 0 {
		return
	}
	rl.statusCode = code
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
		// Authorization must be named explicitly: the CORS wildcard does not cover it.
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-ID, Accept, Origin, Cache-Control")
		w.Header().Set("Access-Control-Expose-Headers", "X-Request-ID")
		w.Header().Set("Access-Control-Max-Age", "86400")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			slog.Info("HTTP OPTIONS preflight handled", "path", r.URL.Path, "remote", r.RemoteAddr)
			return
		}

		// The bearer token is never logged, not even truncated: a prefix is still a
		// credential. The query string can carry a key too, so it goes through the
		// redacting helper instead of being written raw.
		slog.Info(">>> INCOMING HTTP REQUEST",
			"method", r.Method,
			"path", r.URL.Path,
			"query", logger.RedactQuery(r.URL.RawQuery),
			"remote", r.RemoteAddr,
			"content_type", r.Header.Get("Content-Type"),
			"content_length", r.ContentLength,
		)

		start := time.Now()
		rl := &responseLogger{
			ResponseWriter: w,
			statusCode:     0,
		}

		next.ServeHTTP(rl, r)
		if rl.statusCode == 0 {
			rl.statusCode = http.StatusOK
		}
		slog.Info("<<< OUTGOING HTTP RESPONSE",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rl.statusCode,
			"bytes", rl.bytesWritten,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}

func main() {
	if len(os.Args) > 1 {
		arg := strings.ToLower(os.Args[1])
		if arg == "-v" || arg == "-version" || arg == "--version" || arg == "version" {
			fmt.Printf("Helix Gateway version %s (commit: %s, built: %s)\n", Version, GitCommit, BuildTime)
			os.Exit(0)
		}
	}

	cfg, err := config.LoadFromEnv()
	if err != nil {
		slog.Error("configuration error", "err", err)
		os.Exit(1)
	}

	logger.Init(cfg.LogLevel, os.Stdout)
	if cfg.EnvFileLoaded != "" {
		slog.Info("loaded configuration from .env file", "path", cfg.EnvFileLoaded)
	}
	slog.Info("starting helix-gateway",
		"version", Version,
		"git_commit", GitCommit,
		"build_time", BuildTime,
		"port", cfg.Port,
		"upstream", cfg.UpstreamBaseURL,
		"env_file", cfg.EnvFileLoaded,
		"wrapper_mode", cfg.WrapperMode,
		"recovery_policy", cfg.RecoveryPolicy,
		"empty_retries", cfg.UpstreamEmptyRetries,
	)

	// 1. Initialize Key Manager (SQLite + in-memory snapshot)
	keyMgr, err := keymgmt.NewManager(cfg.KeyDBPath, cfg.KeyHMACSecret, cfg.StaticKeys)
	if err != nil {
		slog.Error("failed to initialize key manager", "err", err)
		os.Exit(1)
	}
	defer keyMgr.Close()

	// 2. Initialize Upstream Client
	upstreamClient := proxy.NewUpstreamClient(cfg)

	// 3. Initialize Orchestrator
	orch := recovery.NewOrchestrator(cfg, upstreamClient, keyMgr)
	if err := orch.AntiRouteError(); err != nil {
		keyMgr.Close()
		fmt.Fprintf(os.Stderr, "anti-route configuration error: %v\n", err)
		os.Exit(1)
	}

	// 4. Initialize Proxy Handler
	proxyHandler := proxy.NewProxyHandler(cfg, keyMgr, upstreamClient)
	proxyHandler.SetChatHandler(orch.HandleChatCompletions)

	// 5. Initialize Admin Handler
	adminHandler := keymgmt.NewAdminHandler(cfg.AdminAPIKey, keyMgr)

	// 6. Register HTTP Routes
	mux := http.NewServeMux()

	// Health & Metrics
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

	// Proxy Routes (/v1/models, /v1/chat/completions, /v1/*)
	proxyHandler.RegisterRoutes(mux)

	// Admin Routes (/admin/keys)
	adminHandler.RegisterRoutes(mux)

	handlerWithMiddlewares := detailedLoggingMiddleware(mux)

	// 7. Run Server with Graceful Shutdown
	if err := server.RunWithSignals(cfg, handlerWithMiddlewares); err != nil {
		slog.Error("server fatal error", "err", err)
		os.Exit(1)
	}
}
