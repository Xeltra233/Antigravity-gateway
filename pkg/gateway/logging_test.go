package gateway

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestDetailedLoggingMiddlewareDoesNotLogBodies pins the P0 fix: request and
// response payloads must never reach a log line, because the redacting handler
// only knows about named credential fields, not conversation content.
func TestDetailedLoggingMiddlewareDoesNotLogBodies(t *testing.T) {
	var buf bytes.Buffer
	old := slog.Default()
	slog.SetDefault(slog.New(slog.NewJSONHandler(&buf, nil)))
	defer slog.SetDefault(old)

	const secret = "ID-1234-5678 private conversation body"
	handler := detailedLoggingMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"` + secret + `"}}]}`))
	}))

	req := httptest.NewRequest(http.MethodPost, "/v1/chat/completions?api_key=sk-live-123&model=gpt-4o", strings.NewReader(secret))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	logs := buf.String()
	if strings.Contains(logs, secret) {
		t.Errorf("log output leaked request or response body: %s", logs)
	}
	if strings.Contains(logs, "sk-live-123") {
		t.Errorf("log output leaked the raw api key: %s", logs)
	}
	if !strings.Contains(logs, "REDACTED") {
		t.Errorf("log output should carry a redacted query value: %s", logs)
	}
	if !strings.Contains(logs, "gpt-4o") {
		t.Errorf("log output should keep non-sensitive query values: %s", logs)
	}
}

// TestDetailedLoggingMiddlewareCORS checks that the wildcard origin no longer
// travels with Allow-Credentials, a combination the fetch spec rejects.
func TestDetailedLoggingMiddlewareCORS(t *testing.T) {
	handler := detailedLoggingMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	req.Header.Set("Origin", "https://example.com")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Errorf("Allow-Origin = %q, want *", got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Credentials"); got != "" {
		t.Errorf("Allow-Credentials = %q, want absent", got)
	}
}

func TestDetailedLoggingMiddlewarePreflight(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("preflight must not reach the handler")
	})
	handler := detailedLoggingMiddleware(next)

	req := httptest.NewRequest(http.MethodOptions, "/v1/chat/completions", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Errorf("preflight status = %d, want %d", rec.Code, http.StatusNoContent)
	}
}
