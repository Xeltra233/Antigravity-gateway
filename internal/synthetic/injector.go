package synthetic

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"sync/atomic"

	"helix-gateway/internal/config"
	"helix-gateway/internal/entropy"
)

type InjectedRequest struct {
	SyntheticToolName  string
	TransformedBody    []byte
	OriginalModel      string
	IsStreaming        bool
	HasDownstreamTools bool
	ForcedRealTool     bool
	EntropyTag         string
}

type RequestInjector struct {
	cfg         *config.Config
	modelFilter *ModelFilter
	entropyOpts entropy.Options
	seedSeq     atomic.Uint64
}

func NewRequestInjector(cfg *config.Config) *RequestInjector {
	return &RequestInjector{
		cfg:         cfg,
		modelFilter: NewModelFilter(cfg.TextModelPattern, cfg.NonTextModelPattern),
		entropyOpts: entropy.Options{
			Level:       entropy.ParseLevel(cfg.EntropyLevel),
			Layout:      cfg.EntropyLayout,
			Invisible:   cfg.EntropyInvisible,
			ParamJitter: cfg.EntropyParamJitter,
			Seed:        cfg.EntropySeed,
		},
	}
}

func (inj *RequestInjector) GetModelFilter() *ModelFilter {
	return inj.modelFilter
}

// NewSource returns a fresh per-request random stream. When a base seed is
// configured it is advanced by a per-request counter, so a run stays
// reproducible while individual requests still differ from one another.
func (inj *RequestInjector) NewSource() (*entropy.Source, error) {
	opts := inj.entropyOpts
	if opts.Seed != 0 {
		opts.Seed = opts.Seed*0x9e3779b97f4a7c15 + inj.seedSeq.Add(1)
	}
	return entropy.New(opts)
}

// LayoutEnabled reports whether wire-layout randomization is active.
func (inj *RequestInjector) LayoutEnabled() bool {
	return inj.entropyOpts.Layout
}

func (inj *RequestInjector) GenerateUniqueToolName(src *entropy.Source, existingToolNames map[string]bool) (string, error) {
	if src == nil {
		var err error
		if src, err = inj.NewSource(); err != nil {
			return "", fmt.Errorf("failed to create entropy source: %w", err)
		}
	}
	return inj.buildToolName(src, existingToolNames)
}

func (inj *RequestInjector) BuildSyntheticTool(src *entropy.Source, toolName string) map[string]any {
	params := map[string]any{
		"type": "object",
		"properties": map[string]any{
			"content": map[string]any{
				"type": "string",
			},
		},
		"required": []string{"content"},
	}

	if inj.cfg.SyntheticToolStrict {
		params["additionalProperties"] = false
	}

	fn := map[string]any{
		"name":        toolName,
		"description": inj.buildToolDescription(src),
		"parameters":  params,
	}

	if inj.cfg.SyntheticToolStrict {
		fn["strict"] = true
	}

	return map[string]any{
		"type":     "function",
		"function": fn,
	}
}

func (inj *RequestInjector) BuildControlMessage(src *entropy.Source, toolName string) map[string]any {
	role := inj.cfg.ControlMessageRole
	if role == "" {
		role = "user"
	}

	var prompt string
	if inj.cfg.ControlPromptTemplate != "" {
		prompt = fmt.Sprintf(inj.cfg.ControlPromptTemplate, toolName)
	} else {
		prompt = inj.buildControlPrompt(src, toolName)
	}

	return map[string]any{
		"role":    role,
		"content": prompt,
	}
}

// SanitizeAndInjectMessages ensures that:
// 1. Synthetic control message is properly injected according to configured position.
// 2. Upstream Gemini/CPA constraints are strictly satisfied: requests must never end with assistant/model turn or be left without a user turn after system messages are extracted.
func (inj *RequestInjector) SanitizeAndInjectMessages(src *entropy.Source, messages []any, controlMsg map[string]any) []any {
	// Find the last conversational role (ignoring trailing system/developer messages)
	lastConversationalRole := ""
	for i := len(messages) - 1; i >= 0; i-- {
		if mMap, ok := messages[i].(map[string]any); ok {
			role, _ := mMap["role"].(string)
			if role != "system" && role != "developer" {
				lastConversationalRole = role
				break
			}
		}
	}

	needsUserTurn := lastConversationalRole == "assistant" || lastConversationalRole == "model" || lastConversationalRole == ""
	controlContent, _ := controlMsg["content"].(string)
	continuation := inj.buildContinuation(src, "Please continue and fulfill the request.")

	switch inj.cfg.ControlMessagePosition {
	case "head":
		messages = append([]any{controlMsg}, messages...)
		if needsUserTurn {
			messages = append(messages, map[string]any{
				"role":    "user",
				"content": continuation,
			})
		}
	case "system_tail":
		lastSystemIdx := -1
		for i, m := range messages {
			if mMap, ok := m.(map[string]any); ok {
				role, _ := mMap["role"].(string)
				if role == "system" || role == "developer" {
					lastSystemIdx = i
				}
			}
		}
		if lastSystemIdx >= 0 {
			newMessages := make([]any, 0, len(messages)+1)
			newMessages = append(newMessages, messages[:lastSystemIdx+1]...)
			newMessages = append(newMessages, controlMsg)
			newMessages = append(newMessages, messages[lastSystemIdx+1:]...)
			messages = newMessages
		} else {
			messages = append([]any{controlMsg}, messages...)
		}
		if needsUserTurn {
			messages = append(messages, map[string]any{
				"role":    "user",
				"content": continuation,
			})
		}
	default: // "tail"
		// If the conversation needs a user turn (ends with assistant, assistant+system, or empty/system-only),
		// we inject the control message as role: "user" at the tail. This satisfies both the control prompt
		// and the Gemini user-turn requirement without relying on system extraction.
		if needsUserTurn {
			userControl := map[string]any{
				"role":    "user",
				"content": controlContent,
			}
			messages = append(messages, userControl)
		} else {
			messages = append(messages, controlMsg)
		}
	}

	return messages
}

func (inj *RequestInjector) Inject(rawBody []byte) (*InjectedRequest, error) {
	return inj.InjectContext(context.Background(), rawBody)
}

func (inj *RequestInjector) InjectContext(ctx context.Context, rawBody []byte) (*InjectedRequest, error) {
	src, err := inj.NewSource()
	if err != nil {
		return nil, fmt.Errorf("failed to create entropy source: %w", err)
	}

	var bodyMap map[string]any
	if err := json.Unmarshal(rawBody, &bodyMap); err != nil {
		return nil, fmt.Errorf("invalid json in request body: %w", err)
	}

	modelStr, _ := bodyMap["model"].(string)
	isStreaming, _ := bodyMap["stream"].(bool)

	// If WRAPPER_MODE is "off" or model is NOT a text model (e.g. image, audio, embedding), pass through verbatim (with Gemini turn sanity)
	if inj.cfg.WrapperMode == "off" || !inj.modelFilter.IsTextModel(modelStr) {
		var messages []any
		if mList, ok := bodyMap["messages"].([]any); ok && len(mList) > 0 {
			messages = append(messages, mList...)
			lastConversationalRole := ""
			for i := len(messages) - 1; i >= 0; i-- {
				if mMap, ok := messages[i].(map[string]any); ok {
					role, _ := mMap["role"].(string)
					if role != "system" && role != "developer" {
						lastConversationalRole = role
						break
					}
				}
			}
			if lastConversationalRole == "assistant" || lastConversationalRole == "model" || lastConversationalRole == "" {
				messages = append(messages, map[string]any{
					"role":    "user",
					"content": inj.buildContinuation(src, "Please continue."),
				})
				bodyMap["messages"] = messages
				if tb, err := inj.reencode(bodyMap, src); err == nil {
					rawBody = tb
				}
			}
		}

		return &InjectedRequest{
			SyntheticToolName:  "",
			TransformedBody:    rawBody,
			OriginalModel:      modelStr,
			IsStreaming:        isStreaming,
			HasDownstreamTools: false,
			ForcedRealTool:     false,
			EntropyTag:         src.Tag(),
		}, nil
	}

	// Collect existing tool names
	existingToolNames := make(map[string]bool)
	var rawTools []any
	hasDownstreamTools := false
	if tList, ok := bodyMap["tools"].([]any); ok && len(tList) > 0 {
		hasDownstreamTools = true
		rawTools = append(rawTools, tList...)
		for _, t := range tList {
			if tMap, ok := t.(map[string]any); ok {
				if fnMap, ok := tMap["function"].(map[string]any); ok {
					if name, ok := fnMap["name"].(string); ok {
						existingToolNames[name] = true
					}
				}
			}
		}
	}

	// Generate unique synthetic tool name
	toolName, err := inj.GenerateUniqueToolName(src, existingToolNames)
	if err != nil {
		return nil, err
	}

	// Append synthetic tool to tools
	syntheticTool := inj.BuildSyntheticTool(src, toolName)
	tools := append(rawTools, syntheticTool)
	bodyMap["tools"] = tools

	// Handle tool_choice mapping
	forcedRealTool := false
	if tc, ok := bodyMap["tool_choice"]; ok {
		switch v := tc.(type) {
		case string:
			if v == "none" {
				bodyMap["tool_choice"] = "auto"
			} else if v == "required" {
				bodyMap["tool_choice"] = "required"
			} else if v == "auto" {
				bodyMap["tool_choice"] = "auto"
			}
		case map[string]any:
			forcedRealTool = true
			bodyMap["tool_choice"] = v
		}
	} else {
		bodyMap["tool_choice"] = "auto"
	}

	// Prepare messages & inject synthetic control prompt while sanitizing conversation turns for Gemini/CPA
	var messages []any
	if mList, ok := bodyMap["messages"].([]any); ok {
		messages = append(messages, mList...)
	}

	controlMsg := inj.BuildControlMessage(src, toolName)
	bodyMap["messages"] = inj.SanitizeAndInjectMessages(src, messages, controlMsg)

	inj.applySamplingJitter(bodyMap, src)

	transformed, err := inj.reencode(bodyMap, src)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal transformed request: %w", err)
	}

	return &InjectedRequest{
		SyntheticToolName:  toolName,
		TransformedBody:    transformed,
		OriginalModel:      modelStr,
		IsStreaming:        isStreaming,
		HasDownstreamTools: hasDownstreamTools,
		ForcedRealTool:     forcedRealTool,
		EntropyTag:         src.Tag(),
	}, nil
}

func (inj *RequestInjector) reencode(bodyMap map[string]any, src *entropy.Source) ([]byte, error) {
	return MarshalShaped(bodyMap, src, inj.entropyOpts.Layout)
}

// applySamplingJitter nudges sampling parameters by a sub-percent amount so a
// fixed parameter tuple is not itself a stable request signature. Values stay
// inside each parameter's valid range and keep their original type.
func (inj *RequestInjector) applySamplingJitter(bodyMap map[string]any, src *entropy.Source) {
	if src == nil || !src.ParamJitter() {
		return
	}
	jitter := func(key string, span, min, max float64) {
		raw, ok := bodyMap[key]
		if !ok {
			return
		}
		value, ok := raw.(float64)
		if !ok {
			return
		}
		delta := (src.Float64()*2 - 1) * span
		next := math.Round((value+delta)*1000) / 1000
		if next < min {
			next = min
		}
		if next > max {
			next = max
		}
		bodyMap[key] = next
	}
	jitter("temperature", 0.01, 0, 2)
	jitter("top_p", 0.005, 0, 1)
	jitter("presence_penalty", 0.01, -2, 2)
	jitter("frequency_penalty", 0.01, -2, 2)
}
