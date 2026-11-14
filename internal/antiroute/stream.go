package antiroute

import (
	"bytes"
)

// StreamRestorer puts masked terms back while a response is still streaming.
//
// A masked term can straddle two chunks, so the tail of every chunk that could
// still grow into a masked term is held back until the next chunk (or Flush)
// arrives. Only a handful of bytes are ever held, which keeps the visible
// streaming latency unchanged.
type StreamRestorer struct {
	pairs   []pair
	maxHold int
	carry   []byte
	active  bool
	restore int
}

// NewStreamRestorer returns nil when nothing has to be restored.
func (s *Session) NewStreamRestorer() *StreamRestorer {
	if s == nil || s.g == nil || !s.g.opts.Split {
		return nil
	}
	s.mu.Lock()
	pairs := append([]pair(nil), s.pairs...)
	s.mu.Unlock()
	if len(pairs) == 0 {
		return nil
	}
	maxHold := 0
	for _, p := range pairs {
		if n := len([]byte(p.masked)) - 1; n > maxHold {
			maxHold = n
		}
	}
	return &StreamRestorer{pairs: pairs, maxHold: maxHold, active: true}
}

// Feed restores the writable prefix of chunk and buffers a possible partial
// term at the end.
func (r *StreamRestorer) Feed(chunk []byte) []byte {
	if r == nil || !r.active {
		return chunk
	}
	if len(chunk) == 0 {
		return chunk
	}
	buf := make([]byte, 0, len(r.carry)+len(chunk))
	buf = append(buf, r.carry...)
	buf = append(buf, chunk...)
	r.carry = r.carry[:0]

	hold := r.holdLen(buf)
	head := buf[:len(buf)-hold]
	if hold > 0 {
		r.carry = append(r.carry[:0], buf[len(buf)-hold:]...)
	}
	return r.apply(head)
}

// Flush returns whatever is still buffered. Call it once the stream ends.
func (r *StreamRestorer) Flush() []byte {
	if r == nil || !r.active || len(r.carry) == 0 {
		return nil
	}
	out := r.apply(r.carry)
	r.carry = nil
	return out
}

// holdLen returns the length of the longest suffix of buf that is a proper
// prefix of some masked term.
func (r *StreamRestorer) holdLen(buf []byte) int {
	if r.maxHold <= 0 || len(buf) == 0 {
		return 0
	}
	limit := r.maxHold
	if limit > len(buf) {
		limit = len(buf)
	}
	for n := limit; n > 0; n-- {
		suffix := buf[len(buf)-n:]
		for _, p := range r.pairs {
			masked := []byte(p.masked)
			if len(suffix) < len(masked) && bytes.HasPrefix(masked, suffix) {
				return n
			}
		}
	}
	return 0
}

func (r *StreamRestorer) apply(in []byte) []byte {
	if len(in) == 0 {
		return in
	}
	out := in
	for _, p := range r.pairs {
		masked := []byte(p.masked)
		if bytes.Contains(out, masked) {
			r.restore++
			out = bytes.ReplaceAll(out, masked, []byte(p.original))
		}
	}
	return out
}

// Restored reports how many terms were put back.
func (r *StreamRestorer) Restored() int {
	if r == nil {
		return 0
	}
	return r.restore
}
