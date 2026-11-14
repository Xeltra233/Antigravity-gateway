package antiroute

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
)

// StreamRewriter restores masked terms inside a Server-Sent-Events response
// while it is being read.
//
// Restoring at the byte level is not enough: an upstream splits its output at
// arbitrary points, so a masked term regularly straddles two events, and the
// SSE framing sits between the two halves. The rewriter therefore works per
// event on the decoded text fields, and keeps the trailing partial term of the
// text stream in a small carry until the next event completes it.
//
// Anything the rewriter does not recognise (a line without a data: payload, a
// payload that is not JSON, an event without text fields) is passed through
// byte for byte.
type StreamRewriter struct {
	src     *bufio.Reader
	r       *StreamRestorer
	pending []byte
	done    bool
}

// NewStreamRewriter wraps r. It returns nil when the session has no masked
// terms, so callers can treat a nil rewriter as "nothing to do".
func (s *Session) NewStreamRewriter(r io.Reader) io.Reader {
	restorer := s.NewStreamRestorer()
	if restorer == nil {
		return nil
	}
	return &StreamRewriter{src: bufio.NewReaderSize(r, 32*1024), r: restorer}
}

func (rw *StreamRewriter) Read(p []byte) (int, error) {
	for {
		if len(rw.pending) > 0 {
			n := copy(p, rw.pending)
			rw.pending = rw.pending[n:]
			return n, nil
		}
		if rw.done {
			return 0, io.EOF
		}
		line, err := rw.src.ReadBytes('\n')
		if len(line) > 0 {
			rw.pending = rw.rewriteLine(line)
		}
		if err != nil {
			rw.done = true
			tail := rw.flushTail()
			rw.pending = append(rw.pending, tail...)
			if len(rw.pending) == 0 {
				return 0, err
			}
		}
	}
}

// rewriteLine restores one SSE line, or returns it unchanged.
func (rw *StreamRewriter) rewriteLine(line []byte) []byte {
	body := bytes.TrimRight(line, "\r\n")
	if !bytes.HasPrefix(body, []byte("data:")) {
		return line
	}
	payload := bytes.TrimSpace(body[5:])
	if len(payload) == 0 {
		return line
	}
	if bytes.Equal(payload, []byte("[DONE]")) {
		// The terminator is the last thing a client reads, so a term still
		// held back has to be released just before it.
		if tail := rw.flushTail(); len(tail) > 0 {
			return append(tail, line...)
		}
		return line
	}
	var event map[string]any
	if err := json.Unmarshal(payload, &event); err != nil {
		return line
	}
	if !rw.restoreEvent(event) {
		return line
	}
	encoded, err := marshalNoHTML(event)
	if err != nil {
		return line
	}
	out := make([]byte, 0, len(encoded)+8)
	out = append(out, "data: "...)
	out = append(out, encoded...)
	out = append(out, line[len(body):]...)
	// Keep the exact terminator the upstream used: "\n", "\r\n" or none.
	return out
}

// restoreEvent rewrites the text fields of one event and reports whether
// anything changed. Only the last text field of an event can hold a partial
// term, because the text stream is sequential.
func (rw *StreamRewriter) restoreEvent(event map[string]any) bool {
	choices, ok := event["choices"].([]any)
	if !ok {
		// Gemini-style payloads carry candidate objects instead.
		choices, ok = event["candidates"].([]any)
		if !ok {
			return rw.restoreField(event, "text") || rw.restoreField(event, "content")
		}
	}
	changed := false
	for _, raw := range choices {
		choice, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		changed = rw.restoreField(choice, "delta") || changed
		changed = rw.restoreField(choice, "message") || changed
		changed = rw.restoreField(choice, "content") || changed
		if content, ok := choice["content"].(map[string]any); ok {
			changed = rw.restoreField(content, "parts") || changed
		}
	}
	return changed
}

// restoreField handles the common shapes: a string field, an object holding a
// text field, or a list of parts.
func (rw *StreamRewriter) restoreField(container map[string]any, key string) bool {
	switch value := container[key].(type) {
	case string:
		restored := string(rw.r.Feed([]byte(value)))
		if restored == value {
			return false
		}
		container[key] = restored
		return true
	case map[string]any:
		return rw.restoreField(value, "text") || rw.restoreField(value, "content")
	case []any:
		changed := false
		for _, item := range value {
			if part, ok := item.(map[string]any); ok {
				changed = rw.restoreField(part, "text") || changed
			}
		}
		return changed
	}
	return false
}

// flushTail releases a partial term still held at the end of the stream.
func (rw *StreamRewriter) flushTail() []byte {
	tail := rw.r.Flush()
	if len(tail) == 0 {
		return nil
	}
	event := map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"content": string(tail)}}}}
	encoded, err := marshalNoHTML(event)
	if err != nil {
		return tail
	}
	out := make([]byte, 0, len(encoded)+9)
	out = append(out, "data: "...)
	out = append(out, encoded...)
	out = append(out, '\n', '\n')
	return out
}

// marshalNoHTML encodes without escaping <, > and &, so the rewriter does not
// change how the payload looks beyond the restored terms.
func marshalNoHTML(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// RestoreEventText is a test seam: it restores the text fields of one decoded
// event the way the streaming path does.
func (rw *StreamRewriter) RestoreEventText(payload string) (string, bool) {
	var event map[string]any
	if err := json.Unmarshal([]byte(payload), &event); err != nil {
		return payload, false
	}
	if !rw.restoreEvent(event) {
		return payload, false
	}
	out, err := marshalNoHTML(event)
	if err != nil {
		return payload, false
	}
	return string(out), true
}
