package modelid

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
)

// Source records where the reported model id came from.
type Source string

const (
	SourceRequest  Source = "request"
	SourceBody     Source = "response-body"
	SourceHeader   Source = "response-header"
	SourceStream   Source = "response-stream"
	SourceFallback Source = "fallback"
)

// Info is the identification result for one exchanged request.
type Info struct {
	// Requested is the model field the downstream client asked for.
	Requested string
	// Resolved is the model id the upstream actually reported, when it said
	// anything at all.
	Resolved string
	// Model is the id to report downstream: Resolved when present, else the
	// value derived from the request.
	Model string
	// Family is the vendor family derived from the model id.
	Family string
	// Version is the best-effort version token found in the id.
	Version string
	// Caps lists capability tags derived from the id.
	Caps []string
	// Source is the strongest evidence behind Model.
	Source Source
	// Route is the backend surface the request used.
	Route string
}

// Capability tags.
const (
	CapText      = "text"
	CapTools     = "tools"
	CapVision    = "vision"
	CapReasoning = "reasoning"
	CapImage     = "image"
	CapAudio     = "audio"
	CapEmbedding = "embedding"
)

// requestModelKeys are the field names different SillyTavern backends use to
// carry the selected model.
var requestModelKeys = []string{
	"model",
	"google_model",
	"custom_model",
	"openrouter_model",
	"claude_model",
	"model_name",
}

var (
	reVersion = regexp.MustCompile(`(?i)(?:^|[-_/])(\d+(?:\.\d+){0,2})(?:$|[-_/])`)

	familyPatterns = []struct {
		family string
		re     *regexp.Regexp
	}{
		{"google", regexp.MustCompile(`(?i)(gemini|gemma|palm|bard|vertex|learnlm|imagen)`)},
		{"anthropic", regexp.MustCompile(`(?i)(claude|opus|sonnet|haiku)`)},
		{"openai", regexp.MustCompile(`(?i)(gpt|^o1|^o3|^o4|davinci|chatgpt|codex|dall-e|whisper|tts-1|text-embedding)`)},
		{"deepseek", regexp.MustCompile(`(?i)(deepseek|r1-|deepseek-r1)`)},
		{"qwen", regexp.MustCompile(`(?i)(qwen|qwq|tongyi|qianwen)`)},
		{"meta", regexp.MustCompile(`(?i)(llama|code\s*llama)`)},
		{"mistral", regexp.MustCompile(`(?i)(mistral|mixtral|codestral|magistral)`)},
		{"xai", regexp.MustCompile(`(?i)(grok)`)},
		{"moonshot", regexp.MustCompile(`(?i)(moonshot|kimi)`)},
		{"zhipu", regexp.MustCompile(`(?i)(glm|chatglm|zhipu)`)},
		{"baidu", regexp.MustCompile(`(?i)(ernie|wenxin)`)},
		{"nvidia", regexp.MustCompile(`(?i)(nemotron)`)},
		{"amazon", regexp.MustCompile(`(?i)(titan|nova-)`)},
		{"cohere", regexp.MustCompile(`(?i)(command-r|cohere)`)},
		{"perplexity", regexp.MustCompile(`(?i)(sonar|perplexity)`)},
	}

	capPatterns = []struct {
		cap string
		re  *regexp.Regexp
	}{
		{CapImage, regexp.MustCompile(`(?i)(dall-e|imagen|sdxl|flux|stable-diffusion|midjourney|image)`)},
		{CapAudio, regexp.MustCompile(`(?i)(whisper|tts|audio|speech|realtime-audio)`)},
		{CapEmbedding, regexp.MustCompile(`(?i)(embed|embedding)`)},
		{CapVision, regexp.MustCompile(`(?i)(vision|vl-|-vl|multimodal|gpt-4o|gpt-4\.1|gpt-5|gemini|claude|llava|pixtral)`)},
		{CapReasoning, regexp.MustCompile(`(?i)(thinking|reason|^o1|^o3|^o4|-r1|qwq|deepseek-r1|magistral|gemini-[\d.]+-pro|sonnet|opus)`)},
		{CapTools, regexp.MustCompile(`(?i)(gemini|claude|gpt|qwen|deepseek|llama|mistral|grok|glm|kimi|command-r|nova)`)},
	}
)

// Normalize trims and lowercases a model id for comparison.
func Normalize(id string) string {
	return strings.ToLower(strings.TrimSpace(id))
}

// RequestedFrom extracts the requested model id from a request body. It is
// tolerant of the several field names SillyTavern backends emit.
func RequestedFrom(body []byte) string {
	if len(body) == 0 {
		return ""
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(body, &m); err != nil {
		return ""
	}
	for _, key := range requestModelKeys {
		raw, ok := m[key]
		if !ok {
			continue
		}
		var s string
		if err := json.Unmarshal(raw, &s); err != nil {
			continue
		}
		if s = strings.TrimSpace(s); s != "" {
			return s
		}
	}
	return ""
}

// Identify builds the identification result for a requested model id.
func Identify(requested string) Info {
	id := strings.TrimSpace(requested)
	info := Info{Requested: id, Model: id, Source: SourceRequest}
	if id == "" {
		info.Source = SourceFallback
		return info
	}
	info.Family = detectFamily(id)
	info.Version = detectVersion(id)
	info.Caps = detectCaps(id)
	return info
}

// WithResponse upgrades the identification using what the upstream reported.
func (i Info) WithResponse(headers http.Header, body []byte) Info {
	if resolved := ModelFromBody(body); resolved != "" {
		i.Resolved = resolved
		i.Model = resolved
		i.Source = SourceBody
	} else if resolved := ModelFromHeader(headers); resolved != "" {
		i.Resolved = resolved
		i.Model = resolved
		i.Source = SourceHeader
	} else if i.Model == "" {
		i.Source = SourceFallback
	}
	if i.Resolved != "" {
		if f := detectFamily(i.Resolved); f != "unknown" {
			i.Family = f
		}
		if v := detectVersion(i.Resolved); v != "" {
			i.Version = v
		}
		if caps := detectCaps(i.Resolved); len(caps) > 0 {
			i.Caps = caps
		}
	}
	return i
}

// ModelFromStream sniffs the model id out of the first chunk of an SSE stream.
// Chunks arrive as "data: {...}" lines; a bare JSON object is accepted too.
func ModelFromStream(chunk []byte) string {
	if len(chunk) == 0 {
		return ""
	}
	if m := ModelFromBody(chunk); m != "" {
		return m
	}
	for _, line := range strings.Split(string(chunk), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "" || payload == "[DONE]" {
			continue
		}
		if m := ModelFromBody([]byte(payload)); m != "" {
			return m
		}
	}
	return ""
}

// WithModel re-resolves the identification against an explicit model name and
// provenance, which is what a streaming response needs: the model shows up in
// the first chunk instead of the response body or a header.
func (i Info) WithModel(model string, source Source) Info {
	if strings.TrimSpace(model) != "" {
		i.Resolved = strings.TrimSpace(model)
		i.Model = i.Resolved
		i.Source = source
	}
	if i.Resolved != "" {
		if f := detectFamily(i.Resolved); f != "unknown" {
			i.Family = f
		}
		if v := detectVersion(i.Resolved); v != "" {
			i.Version = v
		}
		if caps := detectCaps(i.Resolved); len(caps) > 0 {
			i.Caps = caps
		}
	}
	return i
}

// ModelFromBody reads the model id out of an OpenAI-style or Gemini-style
// response body.
func ModelFromBody(body []byte) string {
	if len(body) == 0 {
		return ""
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(body, &m); err != nil {
		return ""
	}
	for _, key := range []string{"model", "modelVersion", "model_name"} {
		raw, ok := m[key]
		if !ok {
			continue
		}
		var s string
		if err := json.Unmarshal(raw, &s); err == nil && strings.TrimSpace(s) != "" {
			return strings.TrimSpace(s)
		}
	}
	return ""
}

// ModelFromHeader reads the model id out of the response headers upstreams use
// to advertise routing.
func ModelFromHeader(h http.Header) string {
	if h == nil {
		return ""
	}
	for _, key := range []string{"X-Model", "OpenAI-Model", "X-Upstream-Model", "X-Gemini-Model", "Model"} {
		if v := strings.TrimSpace(h.Get(key)); v != "" {
			return v
		}
	}
	return ""
}

func detectFamily(id string) string {
	for _, p := range familyPatterns {
		if p.re.MatchString(id) {
			return p.family
		}
	}
	return "unknown"
}

func detectVersion(id string) string {
	if m := reVersion.FindStringSubmatch(id); len(m) > 1 {
		return m[1]
	}
	// Fall back to a trailing digit run such as "gemini-3-pro".
	if m := regexp.MustCompile(`(\d+(?:\.\d+)?)`).FindStringSubmatch(id); len(m) > 1 {
		return m[1]
	}
	return ""
}

func detectCaps(id string) []string {
	caps := make([]string, 0, 4)
	for _, p := range capPatterns {
		if p.re.MatchString(id) {
			caps = append(caps, p.cap)
		}
	}
	has := func(c string) bool {
		for _, v := range caps {
			if v == c {
				return true
			}
		}
		return false
	}
	// Text is the default unless the id clearly belongs to another modality
	// only.
	if !has(CapEmbedding) && !has(CapImage) && !has(CapAudio) {
		caps = append([]string{CapText}, caps...)
	}
	return caps
}

// HeaderKeys are the response header names the gateway sets.
const (
	HeaderModel          = "X-AGW-Model"
	HeaderModelRequested = "X-AGW-Model-Requested"
	HeaderModelFamily    = "X-AGW-Model-Family"
	HeaderModelVersion   = "X-AGW-Model-Version"
	HeaderModelCaps      = "X-AGW-Model-Caps"
	HeaderModelSource    = "X-AGW-Model-Source"
	HeaderRoute          = "X-AGW-Route"
)

// Headers renders the identification as response headers.
func (i Info) Headers() http.Header {
	h := http.Header{}
	if i.Model != "" {
		h.Set(HeaderModel, i.Model)
	}
	if i.Requested != "" && Normalize(i.Requested) != Normalize(i.Model) {
		h.Set(HeaderModelRequested, i.Requested)
	}
	if i.Family != "" {
		h.Set(HeaderModelFamily, i.Family)
	}
	if i.Version != "" {
		h.Set(HeaderModelVersion, i.Version)
	}
	if len(i.Caps) > 0 {
		h.Set(HeaderModelCaps, strings.Join(i.Caps, ","))
	}
	h.Set(HeaderModelSource, string(i.Source))
	if i.Route != "" {
		h.Set(HeaderRoute, i.Route)
	}
	return h
}

// ApplyHeaders copies the identification headers onto a response writer.
func (i Info) ApplyHeaders(dst http.Header) {
	if dst == nil {
		return
	}
	for k, vs := range i.Headers() {
		for _, v := range vs {
			dst.Set(k, v)
		}
	}
}

// AsMap renders the identification as a JSON-serializable object for clients
// that prefer a body parameter over headers.
func (i Info) AsMap() map[string]any {
	out := map[string]any{
		"model":  i.Model,
		"family": i.Family,
		"source": string(i.Source),
	}
	if i.Requested != "" {
		out["requested"] = i.Requested
	}
	if i.Resolved != "" {
		out["resolved"] = i.Resolved
	}
	if i.Version != "" {
		out["version"] = i.Version
	}
	if len(i.Caps) > 0 {
		out["capabilities"] = i.Caps
	}
	if i.Route != "" {
		out["route"] = i.Route
	}
	return out
}

// InjectBody adds the identification object to a JSON response body under key.
// Existing keys are left untouched; an empty model id is never injected.
func (i Info) InjectBody(body []byte, key string) []byte {
	if len(body) == 0 || i.Model == "" || key == "" {
		return body
	}
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		return body
	}
	if _, exists := m[key]; exists {
		return body
	}
	m[key] = i.AsMap()
	out, err := json.Marshal(m)
	if err != nil {
		return body
	}
	return out
}

// RouteFromPath infers the backend surface from a request path.
func RouteFromPath(path string) string {
	switch {
	case strings.Contains(path, "/text-completions"):
		return "text-completions"
	case strings.Contains(path, "/chat/completions"):
		return "chat-completions"
	case strings.Contains(path, "/messages"):
		return "messages"
	case strings.Contains(path, "/models"):
		return "models"
	default:
		return "other"
	}
}
