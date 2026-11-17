package synthetic

import (
	"encoding/json"
	"regexp"
	"strings"
	"testing"

	"helix-gateway/internal/config"
	"helix-gateway/internal/entropy"
)

var toolNamePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func entropyCfg(level entropy.Level, layout bool) *config.Config {
	return &config.Config{
		ControlMessageRole:     "system",
		ControlMessagePosition: "tail",
		WrapperMode:            "prefer",
		EntropyLevel:           string(level),
		EntropyLayout:          layout,
		EntropyInvisible:       entropy.DefaultOptions(level).Invisible,
		EntropyParamJitter:     entropy.DefaultOptions(level).ParamJitter,
	}
}

func decodeBody(t *testing.T, raw []byte) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatalf("transformed body is not valid JSON: %v\n%s", err, raw)
	}
	return out
}

func controlContent(t *testing.T, body map[string]any) string {
	t.Helper()
	msgs, ok := body["messages"].([]any)
	if !ok || len(msgs) == 0 {
		t.Fatalf("messages missing: %#v", body["messages"])
	}
	for i := len(msgs) - 1; i >= 0; i-- {
		m, _ := msgs[i].(map[string]any)
		if m == nil {
			continue
		}
		if content, _ := m["content"].(string); content != "" {
			return content
		}
	}
	t.Fatal("no content in messages")
	return ""
}

func TestEntropyToolNameShapeAndVariety(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelStandard, true))
	raw := []byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}]}`)

	seen := map[string]bool{}
	for i := 0; i < 300; i++ {
		res, err := inj.Inject(raw)
		if err != nil {
			t.Fatalf("inject: %v", err)
		}
		if !toolNamePattern.MatchString(res.SyntheticToolName) {
			t.Fatalf("tool name %q does not match the wire pattern", res.SyntheticToolName)
		}
		seen[res.SyntheticToolName] = true
	}
	if len(seen) < 290 {
		t.Fatalf("tool names are too repetitive: %d unique of 300", len(seen))
	}
}

func TestEntropyControlPromptVariety(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelStandard, true))
	raw := []byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}`)

	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		res, err := inj.Inject(raw)
		if err != nil {
			t.Fatalf("inject: %v", err)
		}
		body := decodeBody(t, res.TransformedBody)
		content := controlContent(t, body)
		if !strings.Contains(content, res.SyntheticToolName) {
			t.Fatalf("control prompt does not reference the tool name: %q", content)
		}
		if !strings.Contains(content, "content") {
			t.Fatalf("control prompt does not reference the content argument: %q", content)
		}
		seen[content] = true
	}
	if len(seen) < 60 {
		t.Fatalf("control prompts are too repetitive: %d unique of 200", len(seen))
	}
}

func TestEntropyRepeatedRequestsDiffer(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelStandard, true))
	raw := []byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"same"}]}`)

	first, err := inj.Inject(raw)
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	second, err := inj.Inject(raw)
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	if string(first.TransformedBody) == string(second.TransformedBody) {
		t.Fatal("two requests produced identical bytes")
	}
	if first.EntropyTag == second.EntropyTag {
		t.Fatal("two requests shared an entropy tag")
	}
}

func TestEntropyOffKeepsCanonicalShape(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelOff, false))
	raw := []byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hi"}]}`)

	for i := 0; i < 5; i++ {
		res, err := inj.Inject(raw)
		if err != nil {
			t.Fatalf("inject: %v", err)
		}
		if !strings.HasPrefix(res.SyntheticToolName, "agw_emit_") {
			t.Fatalf("entropy off should keep the legacy prefix, got %q", res.SyntheticToolName)
		}
		if len(res.SyntheticToolName) != len("agw_emit_")+24 {
			t.Fatalf("entropy off should keep the legacy nonce length, got %d", len(res.SyntheticToolName))
		}
		body := decodeBody(t, res.TransformedBody)
		content := controlContent(t, body)
		if !strings.Contains(content, "Always use tool") {
			t.Fatalf("entropy off should use the canonical control prompt, got %q", content)
		}
	}
}

func TestEntropyLayoutKeepsSemantics(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelStandard, true))
	raw := []byte(`{"model":"gpt-4o-mini","temperature":0.7,"messages":[{"role":"user","content":"keep me"}]}`)

	res, err := inj.Inject(raw)
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	body := decodeBody(t, res.TransformedBody)
	if body["model"] != "gpt-4o-mini" {
		t.Fatalf("model changed: %#v", body["model"])
	}
	if body["temperature"] != 0.7 {
		t.Fatalf("standard level must not jitter temperature: %#v", body["temperature"])
	}
	msgs, _ := body["messages"].([]any)
	if len(msgs) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(msgs))
	}
	first, _ := msgs[0].(map[string]any)
	if first["content"] != "keep me" {
		t.Fatalf("downstream content changed: %#v", first["content"])
	}
}

func TestEntropyAggressiveJitterAndInvisible(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelAggressive, true))
	raw := []byte(`{"model":"gpt-4o-mini","temperature":0.7,"top_p":0.9,"messages":[{"role":"user","content":"keep me"}]}`)

	jittered := false
	invisible := false
	for i := 0; i < 20; i++ {
		res, err := inj.Inject(raw)
		if err != nil {
			t.Fatalf("inject: %v", err)
		}
		body := decodeBody(t, res.TransformedBody)
		temp, _ := body["temperature"].(float64)
		if temp < 0.68 || temp > 0.72 {
			t.Fatalf("temperature jitter out of range: %v", temp)
		}
		if temp != 0.7 {
			jittered = true
		}
		content := controlContent(t, body)
		if entropy.StripInvisible(content) != content {
			invisible = true
		}
		if entropy.StripInvisible(content) == "" {
			t.Fatal("control prompt lost all text")
		}
	}
	if !jittered {
		t.Error("aggressive level never jittered the sampling parameters")
	}
	if !invisible {
		t.Error("aggressive level never injected invisible separators")
	}
}

func TestBuildCorrectionMessageReferencesTool(t *testing.T) {
	inj := NewRequestInjector(entropyCfg(entropy.LevelStandard, true))
	src := entropy.NewSeeded(123, entropy.LevelStandard)
	msg := inj.BuildCorrectionMessage(src, "emit_xyz")
	if !strings.Contains(msg, "emit_xyz") || !strings.Contains(msg, "content") {
		t.Fatalf("correction message missing references: %q", msg)
	}
}

func TestConfiguredPrefixIsHonored(t *testing.T) {
	cfg := entropyCfg(entropy.LevelStandard, true)
	cfg.SyntheticToolPrefix = "custom_prefix_"
	inj := NewRequestInjector(cfg)
	res, err := inj.Inject([]byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"x"}]}`))
	if err != nil {
		t.Fatalf("inject: %v", err)
	}
	if !strings.HasPrefix(res.SyntheticToolName, "custom_prefix_") {
		t.Fatalf("configured prefix ignored: %q", res.SyntheticToolName)
	}
	if len(res.SyntheticToolName) != len("custom_prefix_")+24 {
		t.Fatalf("configured prefix should keep the 96-bit nonce: %q", res.SyntheticToolName)
	}
}

func TestFixedSeedIsReproducibleButNotRepeating(t *testing.T) {
	cfg := entropyCfg(entropy.LevelStandard, true)
	cfg.EntropySeed = 777

	raw := []byte(`{"model":"gpt-4o-mini","messages":[{"role":"user","content":"same"}]}`)

	collect := func() []string {
		inj := NewRequestInjector(cfg)
		names := make([]string, 0, 5)
		for i := 0; i < 5; i++ {
			res, err := inj.Inject(raw)
			if err != nil {
				t.Fatalf("inject: %v", err)
			}
			names = append(names, res.SyntheticToolName)
		}
		return names
	}

	first := collect()
	second := collect()

	for i := range first {
		if first[i] != second[i] {
			t.Fatalf("run not reproducible at step %d: %q vs %q", i, first[i], second[i])
		}
	}
	for i := 1; i < len(first); i++ {
		if first[i] == first[i-1] {
			t.Fatalf("consecutive requests repeated the same name: %q", first[i])
		}
	}
}
