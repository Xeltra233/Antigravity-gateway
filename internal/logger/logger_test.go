package logger

import (
	"log/slog"
	"net/url"
	"testing"
)

func TestSanitizeAttrKeepsMetricsAndIdentity(t *testing.T) {
	keep := []string{
		"max_tokens", "prompt_tokens", "completion_tokens", "total_tokens",
		"token_count", "tokens", "tokenizer",
		"key_id", "key_prefix", "auth_mode",
		"Key_ID", "MaxTokens", "AuthMode",
	}
	for _, key := range keep {
		got := sanitizeAttr(slog.String(key, "value"))
		if got.Value.String() == "[REDACTED]" {
			t.Errorf("sanitizeAttr(%q) redacted a non-secret field", key)
		}
	}
}

func TestSanitizeAttrRedactsSecrets(t *testing.T) {
	redact := []string{
		"api_key", "x_api_key", "apikey", "authorization", "Authorization",
		"access_token", "refresh_token", "token", "secret", "client_secret",
		"password", "passwd", "hash", "credential",
	}
	for _, key := range redact {
		got := sanitizeAttr(slog.String(key, "value"))
		if got.Value.String() != "[REDACTED]" {
			t.Errorf("sanitizeAttr(%q) = %q, want [REDACTED]", key, got.Value.String())
		}
	}
}

func TestRedactQuery(t *testing.T) {
	got := RedactQuery("model=gpt-4o&api_key=sk-live-123&refresh_token=abc")
	values, err := url.ParseQuery(got)
	if err != nil {
		t.Fatalf("parse redacted query: %v", err)
	}
	if v := values.Get("model"); v != "gpt-4o" {
		t.Errorf("model = %q, want gpt-4o", v)
	}
	if v := values.Get("api_key"); v != "[REDACTED]" {
		t.Errorf("api_key = %q, want [REDACTED]", v)
	}
	if v := values.Get("refresh_token"); v != "[REDACTED]" {
		t.Errorf("refresh_token = %q, want [REDACTED]", v)
	}
}

func TestRedactQueryPassesThroughPlainParameters(t *testing.T) {
	got := RedactQuery("model=gpt-4o&limit=10")
	values, err := url.ParseQuery(got)
	if err != nil {
		t.Fatalf("parse query: %v", err)
	}
	if values.Get("limit") != "10" {
		t.Errorf("limit = %q, want 10", values.Get("limit"))
	}
}

func TestRedactQueryHandlesEmptyAndInvalid(t *testing.T) {
	if got := RedactQuery(""); got != "" {
		t.Errorf("RedactQuery(\"\") = %q, want empty", got)
	}
	if got := RedactQuery("%zz=1"); got != "[unparsable]" {
		t.Errorf("RedactQuery(invalid) = %q, want [unparsable]", got)
	}
}
