// Package entropy provides a per-request cryptographic random source plus the
// small primitives used to keep outbound request shapes from collapsing into a
// single stable, comparable byte pattern.
//
// The source is seeded from crypto/rand and driven by a ChaCha8 stream, so a
// request's shape cannot be predicted from its neighbours while remaining
// reproducible when an explicit seed is configured.
package entropy

import (
	crand "crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"math/rand/v2"
	"strings"
)

// Level selects how much request-shape entropy is applied.
type Level string

const (
	// LevelOff disables all request-shape entropy.
	LevelOff Level = "off"
	// LevelLight randomizes identifiers and wire layout.
	LevelLight Level = "light"
	// LevelStandard additionally randomizes wording of gateway-authored prompts.
	LevelStandard Level = "standard"
	// LevelAggressive additionally injects invisible separators and sampling jitter.
	LevelAggressive Level = "aggressive"
)

// ParseLevel normalizes a configured level, defaulting to LevelStandard.
func ParseLevel(v string) Level {
	switch Level(strings.ToLower(strings.TrimSpace(v))) {
	case LevelOff:
		return LevelOff
	case LevelLight:
		return LevelLight
	case LevelAggressive:
		return LevelAggressive
	case LevelStandard:
		return LevelStandard
	default:
		return LevelStandard
	}
}

func (l Level) rank() int {
	switch l {
	case LevelLight:
		return 1
	case LevelStandard:
		return 2
	case LevelAggressive:
		return 3
	default:
		return 0
	}
}

// Enabled reports whether any entropy is applied.
func (l Level) Enabled() bool { return l.rank() > 0 }

// AtLeast reports whether this level is at or above the given level.
func (l Level) AtLeast(min Level) bool { return l.rank() >= min.rank() }

// Options is the resolved entropy configuration for a request.
type Options struct {
	Level       Level
	Layout      bool   // randomize JSON key order and whitespace
	Invisible   bool   // sprinkle invisible separators into gateway-authored text
	ParamJitter bool   // micro-jitter sampling parameters
	Seed        uint64 // 0 derives a fresh seed from crypto/rand
}

// DefaultOptions derives per-level defaults.
func DefaultOptions(level Level) Options {
	o := Options{Level: level}
	switch level {
	case LevelOff:
	case LevelLight:
		o.Layout = true
	case LevelStandard:
		o.Layout = true
	case LevelAggressive:
		o.Layout = true
		o.Invisible = true
		o.ParamJitter = true
	}
	return o
}

// Source is a per-request random stream. It is not safe for concurrent use.
type Source struct {
	rnd  *rand.Rand
	opts Options
	tag  string
}

// New builds a source. When Options.Seed is zero a fresh cryptographic seed is
// drawn; otherwise the value is expanded deterministically for reproducible
// tests and for pinning a fixed shape.
func New(opts Options) (*Source, error) {
	var seed [32]byte
	if opts.Seed != 0 {
		expandSeed(seed[:], opts.Seed)
	} else if _, err := crand.Read(seed[:]); err != nil {
		return nil, err
	}
	return fromSeed(seed, opts), nil
}

// NewSeeded is a convenience constructor for deterministic sources.
func NewSeeded(seed uint64, level Level) *Source {
	opts := DefaultOptions(level)
	opts.Seed = seed
	var raw [32]byte
	expandSeed(raw[:], seed)
	return fromSeed(raw, opts)
}

func fromSeed(seed [32]byte, opts Options) *Source {
	return &Source{
		rnd:  rand.New(rand.NewChaCha8(seed)),
		opts: opts,
		tag:  hex.EncodeToString(seed[:4]),
	}
}

// expandSeed spreads a single uint64 over a 32-byte seed with splitmix64 so
// that adjacent seeds produce unrelated streams.
func expandSeed(dst []byte, seed uint64) {
	x := seed
	for i := 0; i+8 <= len(dst); i += 8 {
		x += 0x9e3779b97f4a7c15
		z := x
		z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9
		z = (z ^ (z >> 27)) * 0x94d049bb133111eb
		z ^= z >> 31
		binary.LittleEndian.PutUint64(dst[i:], z)
	}
}

// Tag returns a short stable identifier for the drawn seed, useful in logs.
func (s *Source) Tag() string { return s.tag }

// Level returns the configured level.
func (s *Source) Level() Level { return s.opts.Level }

// Layout reports whether wire-layout randomization is enabled.
func (s *Source) Layout() bool { return s.opts.Layout }

// Invisible reports whether invisible separators may be injected.
func (s *Source) Invisible() bool { return s.opts.Invisible }

// ParamJitter reports whether sampling parameters may be micro-jittered.
func (s *Source) ParamJitter() bool { return s.opts.ParamJitter }

// Uint64 returns a random 64-bit value.
func (s *Source) Uint64() uint64 { return s.rnd.Uint64() }

// IntN returns a value in [0, n). It returns 0 for n <= 0.
func (s *Source) IntN(n int) int {
	if n <= 0 {
		return 0
	}
	return s.rnd.IntN(n)
}

// Bool returns true with probability 1/2.
func (s *Source) Bool() bool { return s.rnd.IntN(2) == 1 }

// Float64 returns a value in [0, 1).
func (s *Source) Float64() float64 { return s.rnd.Float64() }

// Pick returns one element, or "" when the slice is empty.
func (s *Source) Pick(items []string) string {
	if len(items) == 0 {
		return ""
	}
	return items[s.rnd.IntN(len(items))]
}

// PickN returns up to n distinct elements in random order.
func (s *Source) PickN(items []string, n int) []string {
	if n <= 0 || len(items) == 0 {
		return nil
	}
	if n > len(items) {
		n = len(items)
	}
	idx := s.rnd.Perm(len(items))[:n]
	out := make([]string, 0, n)
	for _, i := range idx {
		out = append(out, items[i])
	}
	return out
}

// ShuffleStrings returns a randomly permuted copy.
func (s *Source) ShuffleStrings(items []string) []string {
	out := append([]string(nil), items...)
	s.rnd.Shuffle(len(out), func(i, j int) { out[i], out[j] = out[j], out[i] })
	return out
}

// ShuffleInts returns a random permutation of [0, n).
func (s *Source) ShuffleInts(n int) []int {
	if n <= 0 {
		return nil
	}
	return s.rnd.Perm(n)
}

const hexAlphabet = "0123456789abcdef"
const alphaNumAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
const alphaNumMixed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

// Hex returns nBytes encoded as lowercase hex (2*nBytes characters).
func (s *Source) Hex(nBytes int) string {
	if nBytes <= 0 {
		return ""
	}
	var b strings.Builder
	b.Grow(nBytes * 2)
	for i := 0; i < nBytes*2; i++ {
		b.WriteByte(hexAlphabet[s.rnd.IntN(len(hexAlphabet))])
	}
	return b.String()
}

// AlphaNum returns n lowercase alphanumeric characters.
func (s *Source) AlphaNum(n int) string {
	return s.draw(alphaNumAlphabet, n)
}

// AlphaNumMixed returns n mixed-case alphanumeric characters.
func (s *Source) AlphaNumMixed(n int) string {
	return s.draw(alphaNumMixed, n)
}

// Digits returns n decimal digits.
func (s *Source) Digits(n int) string {
	return s.draw("0123456789", n)
}

func (s *Source) draw(alphabet string, n int) string {
	if n <= 0 {
		return ""
	}
	var b strings.Builder
	b.Grow(n)
	for i := 0; i < n; i++ {
		b.WriteByte(alphabet[s.rnd.IntN(len(alphabet))])
	}
	return b.String()
}

var invisiblePool = []string{"\u200b", "\u200c", "\u200d", "\u2060", "\ufeff"}

// Sprinkle inserts short invisible-character runs at a fraction of the word
// boundaries in text. Visible characters keep their exact order and count, so
// the rendered meaning is unchanged. Only boundaries between an ASCII word
// character and a non-word character are considered; the end of the string is
// never touched.
//
// permille is the rough number of touched boundaries per 1000 candidates.
func (s *Source) Sprinkle(text string, permille int) string {
	if !s.opts.Invisible || text == "" || permille <= 0 {
		return text
	}
	isWord := func(b byte) bool {
		return b == '_' ||
			(b >= '0' && b <= '9') ||
			(b >= 'a' && b <= 'z') ||
			(b >= 'A' && b <= 'Z')
	}
	var b strings.Builder
	b.Grow(len(text) + len(text)/16)
	for i := 0; i < len(text); i++ {
		b.WriteByte(text[i])
		if i+1 >= len(text) {
			continue
		}
		if !isWord(text[i]) || isWord(text[i+1]) {
			continue
		}
		if s.rnd.IntN(1000) >= permille {
			continue
		}
		n := 1 + s.rnd.IntN(2)
		for j := 0; j < n; j++ {
			b.WriteString(invisiblePool[s.rnd.IntN(len(invisiblePool))])
		}
	}
	return b.String()
}

// StripInvisible removes the invisible characters used by Sprinkle.
func StripInvisible(text string) string {
	var b strings.Builder
	b.Grow(len(text))
	for _, r := range text {
		switch r {
		case '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff':
			continue
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}
