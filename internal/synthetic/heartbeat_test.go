package synthetic

import (
	"bytes"
	"strings"
	"sync"
	"testing"
	"time"
)

type syncBuffer struct {
	mu      sync.Mutex
	buf     bytes.Buffer
	flushes int
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

func (s *syncBuffer) Flush() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.flushes++
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.String()
}

func TestHeartbeatWriterEmitsOnSilence(t *testing.T) {
	sink := &syncBuffer{}
	hb := NewHeartbeatWriter(sink, sink, 50*time.Millisecond)
	if hb == nil {
		t.Fatal("expected a heartbeat writer")
	}

	time.Sleep(180 * time.Millisecond)
	hb.Close()

	if hb.HeartbeatsSent() == 0 {
		t.Error("expected at least one keep-alive frame during silence")
	}
	if !strings.Contains(sink.String(), ": keep-alive") {
		t.Errorf("keep-alive frame missing from output: %q", sink.String())
	}
}

func TestHeartbeatWriterStaysQuietWhileWriting(t *testing.T) {
	sink := &syncBuffer{}
	hb := NewHeartbeatWriter(sink, sink, 60*time.Millisecond)
	if hb == nil {
		t.Fatal("expected a heartbeat writer")
	}

	deadline := time.Now().Add(240 * time.Millisecond)
	for time.Now().Before(deadline) {
		if _, err := hb.Write([]byte("data: x\n\n")); err != nil {
			t.Fatalf("write error: %v", err)
		}
		time.Sleep(10 * time.Millisecond)
	}
	hb.Close()

	if hb.HeartbeatsSent() != 0 {
		t.Errorf("expected no keep-alive while data flows, got %d", hb.HeartbeatsSent())
	}
	if strings.Contains(sink.String(), ": keep-alive") {
		t.Errorf("unexpected keep-alive frame: %q", sink.String())
	}
}

func TestHeartbeatWriterDisabled(t *testing.T) {
	sink := &syncBuffer{}
	if hb := NewHeartbeatWriter(sink, sink, 0); hb != nil {
		t.Error("expected nil writer when the interval is disabled")
	}
}
