package synthetic

import (
	"io"
	"net/http"
	"sync"
	"time"
)

// heartbeatComment is an SSE comment frame. Comments are ignored by every
// conforming client but keep proxies, browsers and idle detectors from
// treating a silent stream as dead.
const heartbeatComment = ": keep-alive\n\n"

// HeartbeatWriter serializes writes to an SSE response and emits a keep-alive
// comment whenever the downstream has been silent for the configured interval.
//
// The transformer writes from its own goroutine while the heartbeat loop runs
// on another, so every write has to go through one mutex: net/http does not
// allow concurrent ResponseWriter use.
type HeartbeatWriter struct {
	mu       sync.Mutex
	w        io.Writer
	flusher  http.Flusher
	interval time.Duration
	last     time.Time
	sent     int
	closed   bool
	stop     chan struct{}
	done     chan struct{}
}

// NewHeartbeatWriter wraps w. A non-positive interval returns nil so callers
// can keep using the raw writer unconditionally.
func NewHeartbeatWriter(w io.Writer, flusher http.Flusher, interval time.Duration) *HeartbeatWriter {
	if interval <= 0 {
		return nil
	}
	h := &HeartbeatWriter{
		w:        w,
		flusher:  flusher,
		interval: interval,
		last:     time.Now(),
		stop:     make(chan struct{}),
		done:     make(chan struct{}),
	}
	go h.loop()
	return h
}

func (h *HeartbeatWriter) loop() {
	defer close(h.done)
	// Check at half the interval so the observed silence stays close to the
	// configured value instead of drifting up to 2x.
	tick := h.interval / 2
	if tick < 50*time.Millisecond {
		tick = 50 * time.Millisecond
	}
	t := time.NewTicker(tick)
	defer t.Stop()
	for {
		select {
		case <-h.stop:
			return
		case <-t.C:
			h.mu.Lock()
			if !h.closed && time.Since(h.last) >= h.interval {
				n, err := io.WriteString(h.w, heartbeatComment)
				if err == nil || n > 0 {
					h.last = time.Now()
					h.sent++
					if h.flusher != nil {
						h.flusher.Flush()
					}
				}
			}
			h.mu.Unlock()
		}
	}
}

// Write implements io.Writer and records the activity timestamp.
func (h *HeartbeatWriter) Write(p []byte) (int, error) {
	if h == nil {
		return 0, io.ErrClosedPipe
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return 0, io.ErrClosedPipe
	}
	n, err := h.w.Write(p)
	if n > 0 {
		h.last = time.Now()
	}
	return n, err
}

// Flush exposes the underlying flusher for callers that need an explicit flush.
func (h *HeartbeatWriter) Flush() {
	if h == nil || h.flusher == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.closed {
		h.flusher.Flush()
	}
}

// Close stops the heartbeat loop and blocks until it has exited.
func (h *HeartbeatWriter) Close() {
	if h == nil {
		return
	}
	close(h.stop)
	<-h.done
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
}

// HeartbeatsSent reports how many keep-alive comments were emitted.
func (h *HeartbeatWriter) HeartbeatsSent() int {
	if h == nil {
		return 0
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.sent
}
