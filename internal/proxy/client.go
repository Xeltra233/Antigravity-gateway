package proxy

import (
	"context"
	crand "crypto/rand"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"antigravity-gateway/internal/config"
	"antigravity-gateway/internal/entropy"
	"antigravity-gateway/internal/limits"
)

// upstreamUserAgents is a small pool of realistic browser profiles. The header
// value is rotated per request so outbound traffic does not share one fixed
// client signature.
var upstreamUserAgents = []string{
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
	"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
	"Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1",
	"Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
}

var upstreamAcceptHeaders = []string{
	"application/json, text/event-stream",
	"application/json",
	"application/json, text/plain, text/event-stream",
	"application/json; charset=utf-8, text/event-stream",
}

type UpstreamClient struct {
	cfg            *config.Config
	httpClient     *http.Client
	entropyEnabled bool

	modelsCacheMu sync.RWMutex
	modelsCache   []byte
	modelsExpiry  time.Time
}

func NewUpstreamClient(cfg *config.Config) *UpstreamClient {
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          cfg.UpstreamMaxIdleConns,
		MaxIdleConnsPerHost:   cfg.UpstreamMaxIdleConnsPerHost,
		MaxConnsPerHost:       cfg.UpstreamMaxConnsPerHost,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		ResponseHeaderTimeout: cfg.UpstreamTimeout,
		TLSClientConfig: &tls.Config{
			MinVersion: tls.VersionTLS12,
		},
	}

	httpClient := &http.Client{
		Transport: transport,
		Timeout:   cfg.UpstreamTimeout,
	}

	return &UpstreamClient{
		cfg:            cfg,
		httpClient:     httpClient,
		entropyEnabled: entropy.ParseLevel(cfg.EntropyLevel).Enabled(),
	}
}

// fingerprintHeader returns one value from pool. When request-shape entropy is
// enabled the choice is drawn from the OS CSPRNG; otherwise the first entry is
// kept for stability.
func (c *UpstreamClient) fingerprintHeader(pool []string) string {
	if len(pool) == 0 {
		return ""
	}
	if !c.entropyEnabled {
		return pool[0]
	}
	var b [1]byte
	if _, err := crand.Read(b[:]); err != nil {
		return pool[0]
	}
	return pool[int(b[0])%len(pool)]
}

func (c *UpstreamClient) NewUpstreamRequest(ctx context.Context, method, targetURL string, body io.Reader, reqID string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, targetURL, body)
	if err != nil {
		return nil, fmt.Errorf("failed to create upstream request: %w", err)
	}

	// Standard content type plus a rotated client fingerprint.
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", c.fingerprintHeader(upstreamAcceptHeaders))
	req.Header.Set("User-Agent", c.fingerprintHeader(upstreamUserAgents))
	// Upstream authentication - NEVER forward downstream auth
	if c.cfg.UpstreamAuthMode == "bearer" && c.cfg.UpstreamAPIKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.cfg.UpstreamAPIKey)
	}

	if reqID != "" {
		req.Header.Set("X-Request-ID", reqID)
	}

	return req, nil
}

func (c *UpstreamClient) CloseIdleConnections() {
	c.httpClient.CloseIdleConnections()
}

func (c *UpstreamClient) Do(req *http.Request) (*http.Response, error) {
	return c.httpClient.Do(req)
}

func (c *UpstreamClient) GetModels(ctx context.Context, reqID string) ([]byte, error) {
	c.modelsCacheMu.RLock()
	if c.modelsCache != nil && time.Now().Before(c.modelsExpiry) {
		data := make([]byte, len(c.modelsCache))
		copy(data, c.modelsCache)
		c.modelsCacheMu.RUnlock()
		return data, nil
	}
	c.modelsCacheMu.RUnlock()

	req, err := c.NewUpstreamRequest(ctx, http.MethodGet, c.cfg.UpstreamModelsURL(), nil, reqID)
	if err != nil {
		return nil, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch models from upstream: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		bodyBytes, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("upstream returned status %d: %s", resp.StatusCode, string(bodyBytes))
	}

	bodyBytes, err := limits.ReadAll(resp.Body, c.cfg.MaxResponseBytes)
	if errors.Is(err, limits.ErrExceeded) {
		return nil, fmt.Errorf("upstream models response exceeded configured size limit")
	}
	if err != nil {
		return nil, fmt.Errorf("failed to read models response body: %w", err)
	}

	if c.cfg.ModelsCacheTTL > 0 {
		c.modelsCacheMu.Lock()
		c.modelsCache = bodyBytes
		c.modelsExpiry = time.Now().Add(c.cfg.ModelsCacheTTL)
		c.modelsCacheMu.Unlock()
	}

	return bodyBytes, nil
}
