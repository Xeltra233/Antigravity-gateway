package logger

import (
	"context"
	"io"
	"log/slog"
	"net/url"
	"os"
	"strings"
)

type RedactingHandler struct {
	inner slog.Handler
}

func NewRedactingHandler(inner slog.Handler) *RedactingHandler {
	return &RedactingHandler{inner: inner}
}

func (h *RedactingHandler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.inner.Enabled(ctx, level)
}

func (h *RedactingHandler) Handle(ctx context.Context, r slog.Record) error {
	var newAttrs []slog.Attr
	r.Attrs(func(a slog.Attr) bool {
		newAttrs = append(newAttrs, sanitizeAttr(a))
		return true
	})

	newRecord := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	newRecord.AddAttrs(newAttrs...)
	return h.inner.Handle(ctx, newRecord)
}

func (h *RedactingHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	sanitized := make([]slog.Attr, len(attrs))
	for i, a := range attrs {
		sanitized[i] = sanitizeAttr(a)
	}
	return &RedactingHandler{inner: h.inner.WithAttrs(sanitized)}
}

func (h *RedactingHandler) WithGroup(name string) slog.Handler {
	return &RedactingHandler{inner: h.inner.WithGroup(name)}
}

// loggableKeys are metric and identity field names that contain a sensitive
// substring but carry no secret: token counters, the key id, the auth mode.
// They are checked before the sensitive sets so they stay readable.
var loggableKeys = map[string]struct{}{
	"key_id":            {},
	"key_prefix":        {},
	"auth_mode":         {},
	"max_tokens":        {},
	"prompt_tokens":     {},
	"completion_tokens": {},
	"total_tokens":      {},
	"tokenizer":         {},
	"token_count":       {},
	"tokens":            {},
}

// sensitiveKeys carry a secret in whole. Matching the complete name instead of a
// substring keeps ordinary words such as "monkey" or "hotkey" readable while
// still covering every credential field.
var sensitiveKeys = map[string]struct{}{
	"key":              {},
	"keys":             {},
	"api_key":          {},
	"apikey":           {},
	"access_key":       {},
	"secret":           {},
	"client_secret":    {},
	"secret_key":       {},
	"token":            {},
	"access_token":     {},
	"refresh_token":    {},
	"id_token":         {},
	"auth":             {},
	"authorization":    {},
	"proxy_auth":       {},
	"prompt":           {},
	"password":         {},
	"passwd":           {},
	"pwd":              {},
	"private_key":      {},
	"cookie":           {},
	"set_cookie":       {},
	"session":          {},
	"credential":       {},
	"hash":             {},
	"credentials":      {},
	"body_preview":     {},
	"resp_preview":     {},
	"response_preview": {},
}

// sensitiveSuffixes cover generated names such as "upstream_api_key" or
// "key_hmac_hash" without matching every word that happens to contain "key".
var sensitiveSuffixes = []string{"_key", "_token", "_secret", "_password", "_passwd", "_hash", "_credential"}

// normalizeKey lowercases a field name and inserts underscores at camelCase
// boundaries, so "UpstreamAPIKey" and "upstream_api_key" reach the same lookup.
func normalizeKey(s string) string {
	runes := []rune(s)
	var b strings.Builder
	for i, r := range runes {
		if r >= 'A' && r <= 'Z' {
			insert := false
			if i > 0 {
				prev := runes[i-1]
				switch {
				case prev >= 'a' && prev <= 'z':
					insert = true
				case prev >= 'A' && prev <= 'Z' && i+1 < len(runes) && runes[i+1] >= 'a' && runes[i+1] <= 'z':
					insert = true
				}
			}
			if insert {
				b.WriteByte('_')
			}
			b.WriteRune(r + ('a' - 'A'))
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

func sanitizeAttr(a slog.Attr) slog.Attr {
	key := normalizeKey(strings.TrimSpace(a.Key))
	if _, ok := loggableKeys[key]; ok {
		return a
	}
	if _, ok := sensitiveKeys[key]; ok {
		return slog.String(a.Key, "[REDACTED]")
	}
	for _, suffix := range sensitiveSuffixes {
		if strings.HasSuffix(key, suffix) {
			return slog.String(a.Key, "[REDACTED]")
		}
	}
	return a
}

func Init(levelStr string, w io.Writer) *slog.Logger {
	if w == nil {
		w = os.Stdout
	}
	var level slog.Level
	switch strings.ToLower(levelStr) {
	case "debug":
		level = slog.LevelDebug
	case "warn", "warning":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	default:
		level = slog.LevelInfo
	}

	opts := &slog.HandlerOptions{
		Level: level,
	}
	jsonHandler := slog.NewJSONHandler(w, opts)
	handler := NewRedactingHandler(jsonHandler)
	l := slog.New(handler)
	slog.SetDefault(l)
	return l
}

// RedactQuery masks query parameter values whose name looks like a credential,
// so a key passed in the URL never reaches a log line.
func RedactQuery(raw string) string {
	if raw == "" {
		return ""
	}
	values, err := url.ParseQuery(raw)
	if err != nil {
		return "[unparsable]"
	}
	for name := range values {
		lower := normalizeKey(strings.TrimSpace(name))
		if _, ok := sensitiveKeys[lower]; ok {
			values.Set(name, "[REDACTED]")
			continue
		}
		for _, suffix := range sensitiveSuffixes {
			if strings.HasSuffix(lower, suffix) {
				values.Set(name, "[REDACTED]")
				break
			}
		}
	}
	return values.Encode()
}
