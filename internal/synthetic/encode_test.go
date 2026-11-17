package synthetic

import (
	"encoding/json"
	"reflect"
	"testing"

	"helix-gateway/internal/entropy"
)

func sampleBody() map[string]any {
	return map[string]any{
		"model":       "gpt-4o-mini",
		"stream":      true,
		"temperature": 0.7,
		"messages": []any{
			map[string]any{"role": "system", "content": "You are \"helpful\".\nBe brief.\u00e9"},
			map[string]any{"role": "user", "content": "hello <world> & friends"},
		},
		"tools": []any{
			map[string]any{
				"type": "function",
				"function": map[string]any{
					"name":        "emit_abc",
					"description": "desc",
					"parameters": map[string]any{
						"type":       "object",
						"properties": map[string]any{"content": map[string]any{"type": "string"}},
						"required":   []string{"content"},
					},
				},
			},
		},
		"metadata": map[string]any{"nested": map[string]any{"a": 1.5, "b": []any{true, false, nil}}},
	}
}

func TestMarshalShapedRoundTrip(t *testing.T) {
	body := sampleBody()
	plain, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("baseline marshal: %v", err)
	}
	var want any
	if err := json.Unmarshal(plain, &want); err != nil {
		t.Fatalf("baseline unmarshal: %v", err)
	}
	for seed := uint64(1); seed <= 50; seed++ {
		src := entropy.NewSeeded(seed, entropy.LevelStandard)
		out, err := MarshalShaped(body, src, true)
		if err != nil {
			t.Fatalf("seed %d: %v", seed, err)
		}
		var got any
		if err := json.Unmarshal(out, &got); err != nil {
			t.Fatalf("seed %d: output is not valid JSON: %v\n%s", seed, err, out)
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("seed %d: round trip changed the value\ngot: %#v\nwant: %#v", seed, got, want)
		}
	}
}

func TestMarshalShapedVariesBytes(t *testing.T) {
	body := sampleBody()
	seen := map[string]bool{}
	for seed := uint64(1); seed <= 30; seed++ {
		src := entropy.NewSeeded(seed, entropy.LevelStandard)
		out, err := MarshalShaped(body, src, true)
		if err != nil {
			t.Fatalf("seed %d: %v", seed, err)
		}
		seen[string(out)] = true
	}
	if len(seen) < 25 {
		t.Fatalf("only %d distinct encodings for 30 seeds", len(seen))
	}
}

func TestMarshalShapedKeyOrderIsPermuted(t *testing.T) {
	body := map[string]any{"alpha": 1.0, "beta": 2.0, "gamma": 3.0, "delta": 4.0}
	orders := map[string]bool{}
	for seed := uint64(1); seed <= 40; seed++ {
		src := entropy.NewSeeded(seed, entropy.LevelStandard)
		out, err := MarshalShaped(body, src, true)
		if err != nil {
			t.Fatalf("seed %d: %v", seed, err)
		}
		orders[string(out)] = true
	}
	if len(orders) < 10 {
		t.Fatalf("key ordering rarely changed (%d variants)", len(orders))
	}
}

func TestMarshalShapedDisabledMatchesStdlib(t *testing.T) {
	body := sampleBody()
	src := entropy.NewSeeded(3, entropy.LevelStandard)
	shaped, err := MarshalShaped(body, src, false)
	if err != nil {
		t.Fatalf("shaped: %v", err)
	}
	plain, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("stdlib: %v", err)
	}
	if string(shaped) != string(plain) {
		t.Fatalf("layout-disabled output should equal json.Marshal")
	}
}

func TestMarshalShapedNilSourceMatchesStdlib(t *testing.T) {
	body := sampleBody()
	shaped, err := MarshalShaped(body, nil, true)
	if err != nil {
		t.Fatalf("shaped: %v", err)
	}
	plain, _ := json.Marshal(body)
	if string(shaped) != string(plain) {
		t.Fatalf("nil source output should equal json.Marshal")
	}
}

func TestMarshalShapedEmptyContainers(t *testing.T) {
	body := map[string]any{"obj": map[string]any{}, "arr": []any{}}
	src := entropy.NewSeeded(9, entropy.LevelStandard)
	out, err := MarshalShaped(body, src, true)
	if err != nil {
		t.Fatalf("shaped: %v", err)
	}
	var got any
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	plain, _ := json.Marshal(body)
	var want any
	if err := json.Unmarshal(plain, &want); err != nil {
		t.Fatalf("baseline unmarshal: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("round trip mismatch: %#v", got)
	}
}
