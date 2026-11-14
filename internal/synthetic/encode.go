package synthetic

import (
	"bytes"
	"encoding/json"
	"sort"

	"antigravity-gateway/internal/entropy"
)

// jsonStyle selects the wire layout used for an outbound request body.
type jsonStyle int

const (
	styleCompact jsonStyle = iota
	styleSpaced
	stylePretty
)

// MarshalShaped encodes v as JSON while varying purely presentational details.
// Object keys are emitted in a random order and whitespace follows one of
// several valid layouts, so two semantically identical bodies do not share a
// stable byte pattern. Decoded values are unchanged.
//
// When src is nil, or layout randomization is disabled, the standard encoder is
// used.
func MarshalShaped(v any, src *entropy.Source, layout bool) ([]byte, error) {
	if src == nil || !layout {
		return json.Marshal(v)
	}
	s := &shaper{
		src:    src,
		style:  jsonStyle(src.IntN(3)),
		indent: 1 + src.IntN(3),
	}
	if err := s.writeValue(v, 0); err != nil {
		return nil, err
	}
	return s.buf.Bytes(), nil
}

type shaper struct {
	src    *entropy.Source
	buf    bytes.Buffer
	style  jsonStyle
	indent int
}

func (s *shaper) newline(depth int) {
	s.buf.WriteByte('\n')
	for i := 0; i < depth*s.indent; i++ {
		s.buf.WriteByte(' ')
	}
}

func (s *shaper) colon() {
	switch s.style {
	case styleCompact:
		s.buf.WriteByte(':')
	default:
		s.buf.WriteString(": ")
	}
}

func (s *shaper) comma() {
	s.buf.WriteByte(',')
	if s.style == stylePretty {
		return
	}
	if s.style == styleSpaced {
		s.buf.WriteByte(' ')
	}
}

func (s *shaper) writeScalar(v any) error {
	encoded, err := json.Marshal(v)
	if err != nil {
		return err
	}
	s.buf.Write(encoded)
	return nil
}

func (s *shaper) writeValue(v any, depth int) error {
	switch typed := v.(type) {
	case map[string]any:
		return s.writeObject(typed, depth)
	case []any:
		return s.writeArray(typed, depth)
	default:
		// Strings, numbers, booleans, nil and any remaining concrete type keep
		// the standard encoder's exact representation.
		return s.writeScalar(v)
	}
}

func (s *shaper) writeObject(obj map[string]any, depth int) error {
	if len(obj) == 0 {
		s.buf.WriteString("{}")
		return nil
	}
	keys := make([]string, 0, len(obj))
	for k := range obj {
		keys = append(keys, k)
	}
	// Start from a sorted base so the permutation is the only source of order,
	// then permute for the wire.
	sort.Strings(keys)
	perm := s.src.ShuffleInts(len(keys))

	s.buf.WriteByte('{')
	for i, idx := range perm {
		if i > 0 {
			s.comma()
		}
		if s.style == stylePretty {
			s.newline(depth + 1)
		}
		encodedKey, err := json.Marshal(keys[idx])
		if err != nil {
			return err
		}
		s.buf.Write(encodedKey)
		s.colon()
		if err := s.writeValue(obj[keys[idx]], depth+1); err != nil {
			return err
		}
	}
	if s.style == stylePretty {
		s.newline(depth)
	}
	s.buf.WriteByte('}')
	return nil
}

func (s *shaper) writeArray(arr []any, depth int) error {
	if len(arr) == 0 {
		s.buf.WriteString("[]")
		return nil
	}
	s.buf.WriteByte('[')
	for i, item := range arr {
		if i > 0 {
			s.comma()
		}
		if s.style == stylePretty {
			s.newline(depth + 1)
		}
		if err := s.writeValue(item, depth+1); err != nil {
			return err
		}
	}
	if s.style == stylePretty {
		s.newline(depth)
	}
	s.buf.WriteByte(']')
	return nil
}
