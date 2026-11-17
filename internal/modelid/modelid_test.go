package modelid

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func TestIdentifyFamilies(t *testing.T) {
	cases := []struct {
		id     string
		family string
	}{
		{"gemini-2.5-pro", "google"},
		{"gemini-3.0-flash-thinking", "google"},
		{"claude-sonnet-4-20250514", "anthropic"},
		{"gpt-5", "openai"},
		{"o3-mini", "openai"},
		{"deepseek-r1", "deepseek"},
		{"deepseek-chat", "deepseek"},
		{"qwen-max", "qwen"},
		{"llama-4-maverick", "meta"},
		{"mistral-large", "mistral"},
		{"grok-4", "xai"},
		{"kimi-k2", "moonshot"},
		{"glm-4.5", "zhipu"},
		{"command-r-plus", "cohere"},
		{"totally-unknown-model", "unknown"},
	}
	for _, c := range cases {
		got := Identify(c.id)
		if got.Family != c.family {
			t.Errorf("Identify(%q).Family = %q, want %q", c.id, got.Family, c.family)
		}
	}
}

func TestIdentifyCapabilities(t *testing.T) {
	cases := []struct {
		id   string
		want []string
	}{
		{"text-embedding-3-large", []string{CapEmbedding}},
		{"dall-e-3", []string{CapImage}},
		{"whisper-1", []string{CapAudio}},
		{"gemini-2.5-pro", []string{CapText, CapVision, CapReasoning, CapTools}},
		{"gpt-4o", []string{CapText, CapVision, CapTools}},
	}
	for _, c := range cases {
		got := Identify(c.id).Caps
		if strings.Join(got, ",") != strings.Join(c.want, ",") {
			t.Errorf("Identify(%q).Caps = %v, want %v", c.id, got, c.want)
		}
	}
}

func TestIdentifyVersion(t *testing.T) {
	cases := map[string]string{
		"gemini-2.5-pro":    "2.5",
		"claude-sonnet-4-0": "4",
		"gpt-5":             "5",
		"deepseek-r1":       "1",
	}
	for id, want := range cases {
		if got := Identify(id).Version; got != want {
			t.Errorf("Identify(%q).Version = %q, want %q", id, got, want)
		}
	}
}

func TestRequestedFromPrefersModelField(t *testing.T) {
	body := []byte(`{"model":"gemini-2.5-pro","google_model":"gemini-2.5-flash"}`)
	if got := RequestedFrom(body); got != "gemini-2.5-pro" {
		t.Fatalf("RequestedFrom = %q", got)
	}
}

func TestRequestedFromFallsBackToBackendFields(t *testing.T) {
	cases := map[string]string{
		`{"google_model":"gemini-2.5-flash"}`:              "gemini-2.5-flash",
		`{"custom_model":"my-local-model"}`:                "my-local-model",
		`{"openrouter_model":"anthropic/claude-sonnet-4"}`: "anthropic/claude-sonnet-4",
		`{"claude_model":"claude-opus-4"}`:                 "claude-opus-4",
		`{"model_name":"deepseek-chat"}`:                   "deepseek-chat",
	}
	for body, want := range cases {
		if got := RequestedFrom([]byte(body)); got != want {
			t.Errorf("RequestedFrom(%s) = %q, want %q", body, got, want)
		}
	}
	if got := RequestedFrom([]byte(`{"stream":true}`)); got != "" {
		t.Errorf("RequestedFrom without a model field = %q, want empty", got)
	}
	if got := RequestedFrom([]byte("not json")); got != "" {
		t.Errorf("RequestedFrom on invalid json = %q, want empty", got)
	}
}

func TestWithResponsePrefersResponseBody(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	h := http.Header{}
	h.Set("X-Model", "gemini-2.5-flash")

	got := info.WithResponse(h, []byte(`{"model":"gemini-3.0-pro"}`))
	if got.Model != "gemini-3.0-pro" {
		t.Fatalf("Model = %q, want the response body value", got.Model)
	}
	if got.Resolved != "gemini-3.0-pro" {
		t.Fatalf("Resolved = %q", got.Resolved)
	}
	if got.Source != SourceBody {
		t.Fatalf("Source = %q, want %q", got.Source, SourceBody)
	}
	if got.Requested != "gemini-2.5-pro" {
		t.Fatalf("Requested lost: %q", got.Requested)
	}
	if got.Version != "3.0" {
		t.Fatalf("Version = %q, want 3.0", got.Version)
	}
}

func TestWithResponseFallsBackToHeader(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	h := http.Header{}
	h.Set("OpenAI-Model", "gemini-2.5-flash")
	got := info.WithResponse(h, nil)
	if got.Model != "gemini-2.5-flash" || got.Source != SourceHeader {
		t.Fatalf("got model=%q source=%q", got.Model, got.Source)
	}
}

func TestWithResponseKeepsRequestValue(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	got := info.WithResponse(nil, nil)
	if got.Model != "gemini-2.5-pro" || got.Source != SourceRequest {
		t.Fatalf("got model=%q source=%q", got.Model, got.Source)
	}
}

func TestHeadersRendered(t *testing.T) {
	info := Identify("gemini-2.5-pro").WithResponse(nil, []byte(`{"model":"gemini-3.0-pro"}`))
	h := info.Headers()
	if h.Get(HeaderModel) != "gemini-3.0-pro" {
		t.Fatalf("%s = %q", HeaderModel, h.Get(HeaderModel))
	}
	if h.Get(HeaderModelRequested) != "gemini-2.5-pro" {
		t.Fatalf("%s = %q", HeaderModelRequested, h.Get(HeaderModelRequested))
	}
	if h.Get(HeaderModelFamily) != "google" {
		t.Fatalf("%s = %q", HeaderModelFamily, h.Get(HeaderModelFamily))
	}
	if h.Get(HeaderModelSource) != string(SourceBody) {
		t.Fatalf("%s = %q", HeaderModelSource, h.Get(HeaderModelSource))
	}
	if !strings.Contains(h.Get(HeaderModelCaps), CapText) {
		t.Fatalf("%s = %q", HeaderModelCaps, h.Get(HeaderModelCaps))
	}
}

func TestHeadersSkipRequestedWhenIdentical(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	if got := info.Headers().Get(HeaderModelRequested); got != "" {
		t.Fatalf("%s must be omitted when nothing was rewritten, got %q", HeaderModelRequested, got)
	}
}

func TestInjectBody(t *testing.T) {
	info := Identify("claude-sonnet-4").WithResponse(nil, []byte(`{"model":"claude-sonnet-4-20250514"}`))
	body := []byte(`{"id":"x","choices":[]}`)
	out := info.InjectBody(body, "helix_model")
	var m map[string]any
	if err := json.Unmarshal(out, &m); err != nil {
		t.Fatalf("injected body is not valid JSON: %v", err)
	}
	obj, ok := m["helix_model"].(map[string]any)
	if !ok {
		t.Fatalf("missing identification object: %s", out)
	}
	if obj["model"] != "claude-sonnet-4-20250514" {
		t.Fatalf("model = %v", obj["model"])
	}
	if obj["family"] != "anthropic" {
		t.Fatalf("family = %v", obj["family"])
	}
	// Existing keys must survive untouched.
	if m["id"] != "x" {
		t.Fatalf("existing keys were modified: %s", out)
	}
}

func TestInjectBodyRespectsExistingKey(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	body := []byte(`{"helix_model":{"model":"already-set"}}`)
	out := info.InjectBody(body, "helix_model")
	if string(out) != string(body) {
		t.Fatalf("existing key must not be overwritten: %s", out)
	}
}

func TestInjectBodySkipsNonJSON(t *testing.T) {
	info := Identify("gemini-2.5-pro")
	body := []byte("plain text")
	if out := info.InjectBody(body, "k"); string(out) != "plain text" {
		t.Fatalf("non-JSON body must be untouched: %s", out)
	}
}

func TestRouteFromPath(t *testing.T) {
	cases := map[string]string{
		"/v1/chat/completions": "chat-completions",
		"/v1/text-completions": "text-completions",
		"/v1/messages":         "messages",
		"/v1/models":           "models",
		"/something-else":      "other",
	}
	for path, want := range cases {
		if got := RouteFromPath(path); got != want {
			t.Errorf("RouteFromPath(%q) = %q, want %q", path, got, want)
		}
	}
}

func TestModelFromStream(t *testing.T) {
	sse := []byte("data: {\"id\":\"x\",\"model\":\"gemini-3.1-pro-preview\",\"choices\":[]}\n\n")
	if got := ModelFromStream(sse); got != "gemini-3.1-pro-preview" {
		t.Fatalf("ModelFromStream = %q", got)
	}
	if got := ModelFromStream([]byte("{\"model\":\"claude-sonnet-4\"}")); got != "claude-sonnet-4" {
		t.Fatalf("bare JSON span = %q", got)
	}
	if got := ModelFromStream([]byte("data: [DONE]\n\n")); got != "" {
		t.Fatalf("DONE must yield no model, got %q", got)
	}
	if got := ModelFromStream(nil); got != "" {
		t.Fatalf("empty chunk = %q", got)
	}
	// A chunk that only carries a role delta has no model to report.
	if got := ModelFromStream([]byte("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n")); got != "" {
		t.Fatalf("no model in chunk, got %q", got)
	}
}

func TestWithModelUpgradesProvenance(t *testing.T) {
	info := Identify("[ant]gemini-3.1-pro")
	got := info.WithModel("gemini-3.1-pro-preview", SourceStream)
	if got.Model != "gemini-3.1-pro-preview" || got.Source != SourceStream {
		t.Fatalf("model=%q source=%q", got.Model, got.Source)
	}
	if got.Requested != "[ant]gemini-3.1-pro" {
		t.Fatalf("requested lost: %q", got.Requested)
	}
	if got.Family != "google" || got.Version != "3.1" {
		t.Fatalf("family=%q version=%q", got.Family, got.Version)
	}
	if h := got.Headers().Get(HeaderModelRequested); h != "[ant]gemini-3.1-pro" {
		t.Fatalf("%s = %q", HeaderModelRequested, h)
	}
	// An empty model must not clobber the existing identification.
	if same := info.WithModel("", SourceStream); same.Model != info.Model || same.Source != SourceRequest {
		t.Fatalf("empty model changed the record: %+v", same)
	}
}
