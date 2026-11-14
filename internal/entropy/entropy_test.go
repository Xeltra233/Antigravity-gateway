package entropy

import (
	"strings"
	"testing"
)

func TestParseLevel(t *testing.T) {
	cases := map[string]Level{
		"":           LevelStandard,
		"off":        LevelOff,
		"OFF":        LevelOff,
		" light ":    LevelLight,
		"standard":   LevelStandard,
		"aggressive": LevelAggressive,
		"bogus":      LevelStandard,
	}
	for in, want := range cases {
		if got := ParseLevel(in); got != want {
			t.Errorf("ParseLevel(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestDefaultOptions(t *testing.T) {
	if DefaultOptions(LevelOff).Layout {
		t.Error("off level must not enable layout randomization")
	}
	if !DefaultOptions(LevelLight).Layout {
		t.Error("light level must enable layout randomization")
	}
	std := DefaultOptions(LevelStandard)
	stdLayout, stdInvisible := std.Layout, std.Invisible
	if !stdLayout || stdInvisible {
		t.Errorf("standard defaults = layout:%v invisible:%v", stdLayout, stdInvisible)
	}
	agg := DefaultOptions(LevelAggressive)
	if !agg.Invisible || !agg.ParamJitter {
		t.Errorf("aggressive defaults = %+v", agg)
	}
}

func TestSeededDeterminism(t *testing.T) {
	a := NewSeeded(42, LevelStandard)
	b := NewSeeded(42, LevelStandard)
	for i := 0; i < 100; i++ {
		if a.Uint64() != b.Uint64() {
			t.Fatalf("seeded streams diverged at step %d", i)
		}
	}
	if a.Tag() != b.Tag() {
		t.Errorf("seed tags differ: %s vs %s", a.Tag(), b.Tag())
	}
}

func TestDifferentSeedsDiffer(t *testing.T) {
	a := NewSeeded(1, LevelStandard)
	b := NewSeeded(2, LevelStandard)
	same := 0
	for i := 0; i < 100; i++ {
		if a.Uint64() == b.Uint64() {
			same++
		}
	}
	if same > 0 {
		t.Errorf("independent seeds produced %d identical draws", same)
	}
}

func TestNewUsesCryptoSeed(t *testing.T) {
	a, err := New(Options{Level: LevelStandard, Layout: true})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	b, err := New(Options{Level: LevelStandard, Layout: true})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if a.Tag() == b.Tag() {
		t.Fatalf("two unseeded sources shared tag %s", a.Tag())
	}
}

func TestPickNAndShuffle(t *testing.T) {
	src := NewSeeded(7, LevelStandard)
	items := []string{"a", "b", "c", "d", "e"}
	for i := 0; i < 50; i++ {
		got := src.PickN(items, 3)
		if len(got) != 3 {
			t.Fatalf("PickN returned %d items", len(got))
		}
		seen := map[string]bool{}
		for _, item := range got {
			if seen[item] {
				t.Fatalf("PickN returned duplicate %q in %v", item, got)
			}
			seen[item] = true
		}
	}
	if got := src.PickN(items, 99); len(got) != len(items) {
		t.Errorf("PickN(n>len) returned %d items", len(got))
	}
	perm := src.ShuffleInts(5)
	seen := map[int]bool{}
	for _, v := range perm {
		seen[v] = true
	}
	if len(seen) != 5 {
		t.Errorf("ShuffleInts produced %v", perm)
	}
}

func TestHexAndAlnumShape(t *testing.T) {
	src := NewSeeded(11, LevelStandard)
	if got := src.Hex(12); len(got) != 24 {
		t.Errorf("Hex(12) length = %d", len(got))
	}
	if got := src.AlphaNum(18); len(got) != 18 {
		t.Errorf("AlphaNum(18) length = %d", len(got))
	}
	if got := src.Digits(6); len(got) != 6 || strings.Trim(got, "0123456789") != "" {
		t.Errorf("Digits(6) = %q", got)
	}
}

func TestSprinklePreservesVisibleText(t *testing.T) {
	src := NewSeeded(99, LevelAggressive)
	if !src.Invisible() {
		t.Fatal("aggressive source should allow invisible separators")
	}
	original := "Always use tool `emit_abc` for the final reply. Put the answer in content."
	for i := 0; i < 20; i++ {
		out := src.Sprinkle(original, 500)
		if StripInvisible(out) != original {
			t.Fatalf("visible text changed:\n in: %q\nout: %q", original, out)
		}
		if len(out) > len(original) {
			return
		}
	}
	t.Fatal("Sprinkle never inserted an invisible separator")
}

func TestSprinkleDisabledAtStandard(t *testing.T) {
	src := NewSeeded(5, LevelStandard)
	text := "Some transport instruction with words."
	if got := src.Sprinkle(text, 1000); got != text {
		t.Fatalf("standard level must not inject separators: %q", got)
	}
}

func TestStripInvisible(t *testing.T) {
	in := "a\u200bb\u200cc\u200dd\u2060e\ufefff"
	if got := StripInvisible(in); got != "abcdef" {
		t.Fatalf("StripInvisible = %q", got)
	}
}
