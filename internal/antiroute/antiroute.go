package antiroute

import (
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

// Level selects how much of the guard is active.
type Level int

const (
	// LevelOff disables every outbound mutation.
	LevelOff Level = iota
	// LevelWire only re-encodes the payload so non-ASCII text no longer
	// appears as literal bytes on the wire. Semantically a no-op.
	LevelWire
	// LevelStandard adds phrase neutralization on instruction blocks.
	LevelStandard
	// LevelDeep additionally splits high-risk terms and can demote prefill
	// turns, with the response path restoring the original text.
	LevelDeep
)

func (l Level) String() string {
	switch l {
	case LevelWire:
		return "wire"
	case LevelStandard:
		return "standard"
	case LevelDeep:
		return "deep"
	default:
		return "off"
	}
}

// AtLeast reports whether the level reaches want.
func (l Level) AtLeast(want Level) bool { return l >= want }

// ParseLevel maps a configuration string to a Level.
func ParseLevel(raw string) Level {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "wire":
		return LevelWire
	case "standard":
		return LevelStandard
	case "deep":
		return LevelDeep
	default:
		return LevelOff
	}
}

// Separator choices for split terms.
const (
	SepHyphen     = "-"
	SepWordJoiner = "\u2060"
)

// Options is the resolved guard configuration.
type Options struct {
	Level         Level
	WireEscape    bool
	Split         bool
	Scrub         bool
	ScrubScope    string // "leading" (system + first turn) or "all"
	DemoteHistory bool
	Separator     string
	RulesPath     string
}

// DefaultOptions derives the option set implied by a level.
func DefaultOptions(l Level) Options {
	opts := Options{
		Level:      l,
		WireEscape: l.AtLeast(LevelWire),
		Split:      l.AtLeast(LevelDeep),
		Scrub:      l.AtLeast(LevelStandard),
		ScrubScope: "leading",
		Separator:  SepHyphen,
	}
	return opts
}

// Guard holds the immutable rule table plus the resolved options and is safe
// for concurrent use: per-request state lives in Session.
type Guard struct {
	opts      Options
	rules     *RuleSet
	sep       string
	maxMasked int
}

// New builds a Guard from opts.
func New(opts Options) (*Guard, error) {
	if opts.ScrubScope == "" {
		opts.ScrubScope = "leading"
	}
	if opts.ScrubScope != "leading" && opts.ScrubScope != "all" {
		return nil, fmt.Errorf("invalid anti-route scrub scope: %q (must be 'leading' or 'all')", opts.ScrubScope)
	}
	sep := opts.Separator
	if sep == "" {
		sep = SepHyphen
	}
	if sep != SepHyphen && sep != SepWordJoiner {
		return nil, fmt.Errorf("invalid anti-route separator: %q", opts.Separator)
	}
	g := &Guard{opts: opts, sep: sep}
	if !opts.Split && !opts.Scrub && !opts.WireEscape && !opts.DemoteHistory {
		return g, nil
	}
	rules, err := LoadRules(opts.RulesPath)
	if err != nil {
		return nil, err
	}
	g.rules = rules
	if opts.Split {
		for _, r := range rules.Split {
			rs := []rune(r.Term)
			masked := string(rs[:len(rs)/2]) + sep + string(rs[len(rs)/2:])
			if n := len([]byte(masked)); n > g.maxMasked {
				g.maxMasked = n
			}
		}
	}
	return g, nil
}

// Options returns the resolved options.
func (g *Guard) Options() Options {
	if g == nil {
		return Options{}
	}
	return g.opts
}

// Enabled reports whether any mutation is active.
func (g *Guard) Enabled() bool {
	if g == nil {
		return false
	}
	return g.opts.WireEscape || g.opts.Split || g.opts.Scrub || g.opts.DemoteHistory
}

// NewSession returns per-request state.
func (g *Guard) NewSession() *Session {
	if g == nil {
		return nil
	}
	return &Session{g: g}
}

// Stats reports what a session changed.
type Stats struct {
	Split     int
	Scrub     int
	Demoted   int
	Escaped   bool
	Restored  int
	Mutated   bool
	RuleCount int
}

type pair struct {
	masked   string
	original string
}

// Session tracks the reversible substitutions made for one downstream request
// so the matching response can be put back into its original wording.
type Session struct {
	g *Guard

	mu      sync.Mutex
	pairs   []pair
	split   int
	scrub   int
	demoted int
	escaped bool
}

// Stats returns a snapshot of the session counters.
func (s *Session) Stats() Stats {
	if s == nil {
		return Stats{}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	st := Stats{
		Split:   s.split,
		Scrub:   s.scrub,
		Demoted: s.demoted,
		Escaped: s.escaped,
		Mutated: s.split > 0 || s.scrub > 0 || s.demoted > 0 || s.escaped,
	}
	if s.g != nil && s.g.rules != nil {
		st.RuleCount = len(s.g.rules.Split) + len(s.g.rules.Scrub)
	}
	return st
}

// Protect rewrites the outbound JSON body. encode is used to re-serialize the
// mutated document so the caller keeps its own layout randomization; passing
// nil falls back to encoding/json.
//
// A body that is not a JSON object is returned untouched: the guard never
// guesses at non-JSON payloads.
func (s *Session) Protect(body []byte, encode func(map[string]any) ([]byte, error)) ([]byte, error) {
	if s == nil || s.g == nil || !s.g.Enabled() || len(body) == 0 {
		return body, nil
	}
	if !json.Valid(body) {
		return body, nil
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var m map[string]any
	if err := dec.Decode(&m); err != nil {
		return body, nil
	}

	changed := s.protectMap(m)

	needsEncode := changed > 0 || s.g.opts.WireEscape
	if !needsEncode {
		return body, nil
	}

	var (
		out []byte
		err error
	)
	if encode != nil {
		out, err = encode(m)
	} else {
		out, err = json.Marshal(m)
	}
	if err != nil {
		return body, err
	}
	if s.g.opts.WireEscape {
		out = EscapeNonASCII(out)
		s.mu.Lock()
		s.escaped = true
		s.mu.Unlock()
	}
	return out, nil
}

func (s *Session) protectMap(m map[string]any) int {
	total := 0
	if sys, ok := m["system"].(string); ok && sys != "" {
		rewritten, n := s.rewriteText(sys, true)
		if n > 0 {
			m["system"] = rewritten
			total += n
		}
	}
	msgs, ok := m["messages"].([]any)
	if !ok || len(msgs) == 0 {
		return total
	}

	leadingDone := false
	for _, raw := range msgs {
		msg, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		role, _ := msg["role"].(string)
		isLeading := role == "system" || role == "developer" || !leadingDone
		if role != "system" && role != "developer" && role != "" {
			leadingDone = true
		}

		if content, ok := msg["content"].(string); ok {
			rewritten, n := s.rewriteText(content, isLeading)
			if n > 0 {
				msg["content"] = rewritten
				total += n
			}
		} else if parts, ok := msg["content"].([]any); ok {
			for _, p := range parts {
				part, ok := p.(map[string]any)
				if !ok {
					continue
				}
				text, ok := part["text"].(string)
				if !ok || text == "" {
					continue
				}
				rewritten, n := s.rewriteText(text, isLeading)
				if n > 0 {
					part["text"] = rewritten
					total += n
				}
			}
		}
	}

	if s.g.opts.DemoteHistory {
		total += s.demotePrefill(msgs)
	}
	return total
}

// rewriteText applies scrub (instruction blocks only) then split (anywhere).
func (s *Session) rewriteText(text string, instructionBlock bool) (string, int) {
	if text == "" {
		return text, 0
	}
	count := 0
	out := text
	if s.g.opts.Scrub && (instructionBlock || s.g.opts.ScrubScope == "all") {
		for _, r := range s.g.rules.Scrub {
			if !r.re.MatchString(out) {
				continue
			}
			next := r.re.ReplaceAllString(out, r.replace)
			if next != out {
				out = next
				count++
			}
		}
	}
	if s.g.opts.Split {
		for _, r := range s.g.rules.Split {
			if !strings.Contains(out, r.Term) {
				continue
			}
			rs := []rune(r.Term)
			masked := string(rs[:len(rs)/2]) + s.g.sep + string(rs[len(rs)/2:])
			n := strings.Count(out, r.Term)
			if n == 0 {
				continue
			}
			out = strings.ReplaceAll(out, r.Term, masked)
			count += n
			s.record(pair{masked: masked, original: r.Term}, n, false)
		}
	}
	return out, count
}

func (s *Session) record(p pair, n int, scrub bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if scrub {
		s.scrub += n
		return
	}
	s.split += n
	for _, existing := range s.pairs {
		if existing.masked == p.masked {
			return
		}
	}
	s.pairs = append(s.pairs, p)
	sort.SliceStable(s.pairs, func(i, j int) bool {
		return len(s.pairs[i].masked) > len(s.pairs[j].masked)
	})
}

// demotePrefill rewrites assistant turns that precede the first user turn into
// system turns. Some upstreams reject or penalize a leading model turn; the
// option is off by default because most chat APIs accept prefills.
func (s *Session) demotePrefill(msgs []any) int {
	count := 0
	for _, raw := range msgs {
		msg, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		role, _ := msg["role"].(string)
		if role == "user" {
			break
		}
		if role == "assistant" || role == "model" {
			msg["role"] = "system"
			count++
		}
	}
	if count > 0 {
		s.mu.Lock()
		s.demoted += count
		s.mu.Unlock()
	}
	return count
}

// Restore puts a masked term back to its original spelling.
func (s *Session) RestoreString(v string) string {
	if s == nil || v == "" {
		return v
	}
	s.mu.Lock()
	pairs := s.pairs
	s.mu.Unlock()
	out := v
	for _, p := range pairs {
		if strings.Contains(out, p.masked) {
			out = strings.ReplaceAll(out, p.masked, p.original)
		}
	}
	return out
}

// RestoreBytes restores a complete response body.
func (s *Session) RestoreBytes(b []byte) []byte {
	if s == nil || len(b) == 0 {
		return b
	}
	s.mu.Lock()
	pairs := s.pairs
	s.mu.Unlock()
	if len(pairs) == 0 {
		return b
	}
	out := b
	for _, p := range pairs {
		if bytes.Contains(out, []byte(p.masked)) {
			out = bytes.ReplaceAll(out, []byte(p.masked), []byte(p.original))
		}
	}
	return out
}

// WireEscape re-encodes non-ASCII characters as JSON \uXXXX escapes. Applying
// it twice is a no-op because the second pass sees only ASCII.
func (s *Session) WireEscape(b []byte) []byte {
	if s == nil || s.g == nil || !s.g.opts.WireEscape || len(b) == 0 {
		return b
	}
	return EscapeNonASCII(b)
}

// EscapeNonASCII rewrites every non-ASCII byte in a JSON document as a
// \uXXXX escape. Because JSON structural characters are all ASCII, this can
// never change parsing: the decoded document is byte-identical in UTF-8.
func EscapeNonASCII(b []byte) []byte {
	if !hasNonASCII(b) {
		return b
	}
	const hex = "0123456789ABCDEF"
	out := make([]byte, 0, len(b)+len(b)/2)
	for i := 0; i < len(b); {
		c := b[i]
		if c < 0x80 {
			out = append(out, c)
			i++
			continue
		}
		r, size := utf8.DecodeRune(b[i:])
		if r == 0xFFFD && size == 1 {
			// Invalid UTF-8: keep the byte so the document stays intact.
			out = append(out, c)
			i++
			continue
		}
		i += size
		if r > 0xFFFF {
			r -= 0x10000
			out = appendU16(out, uint16(0xD800+(r>>10)), hex)
			out = appendU16(out, uint16(0xDC00+(r&0x3FF)), hex)
			continue
		}
		out = appendU16(out, uint16(r), hex)
	}
	return out
}

func appendU16(dst []byte, v uint16, hex string) []byte {
	return append(dst,
		'\\', 'u',
		hex[(v>>12)&0xF], hex[(v>>8)&0xF], hex[(v>>4)&0xF], hex[v&0xF],
	)
}

func hasNonASCII(b []byte) bool {
	for i := 0; i < len(b); i++ {
		if b[i] >= 0x80 {
			return true
		}
	}
	return false
}
