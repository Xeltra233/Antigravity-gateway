package synthetic

import (
	"fmt"
	"strings"

	"antigravity-gateway/internal/entropy"
)

// The pools below only describe the gateway's own transport scaffolding. They
// never touch downstream conversation content, so client prompts keep their
// exact bytes.

var toolNameVerbs = []string{
	"emit", "deliver", "output", "answer", "reply", "present", "return",
	"send", "handoff", "convey", "surface", "channel", "route", "package",
	"publish", "carry", "relay", "render",
}

var toolNameObjects = []string{
	"content", "answer", "reply", "final", "message", "result",
	"text", "payload", "body", "output", "response", "pass",
}

// controlTemplates always reference the tool and its content argument, so the
// protocol stays unambiguous regardless of the chosen wording.
var controlTemplates = []string{
	"Always use tool `%[1]s` for the final reply and put the complete user-visible answer in its `content` argument. %[2]s",
	"Deliver the final answer through tool `%[1]s`: place the entire reply in `content`. %[2]s",
	"Route the finished response through `%[1]s` with the whole answer inside `content`. %[2]s",
	"Use the `%[1]s` tool for every final reply. Its `content` argument carries the complete answer. %[2]s",
	"Your final answer must be sent via tool `%[1]s`, with the full text in `content`. %[2]s",
	"Send the completed answer through the `%[1]s` tool. Everything the user should read belongs in `content`. %[2]s",
	"Wrap the final reply in a single call to `%[1]s` and fill `content` with the entire answer. %[2]s",
	"Reply only through tool `%[1]s`; the `content` field holds the complete user-facing text. %[2]s",
	"When the answer is ready, call `%[1]s` once and pass the full reply as `content`. %[2]s",
	"All final output goes through `%[1]s`. Put the finished answer in `content`. %[2]s",
	"Call tool `%[1]s` to emit the final response, placing the complete answer in `content`. %[2]s",
	"Present the final answer with `%[1]s`; `content` must contain the whole user-visible reply. %[2]s",
}

var controlConstraints = []string{
	"Call it exactly once per turn.",
	"Do not emit assistant text outside the call.",
	"Never nest real tool calls inside it.",
	"Keep the call to a single invocation.",
	"Send no prose outside the tool call.",
	"Do not repeat the call after it succeeds.",
	"The call replaces any normal assistant message.",
	"Only one such call is allowed for this turn.",
	"Real function calls must not be wrapped in it.",
	"Skip the call only when the turn needs a genuine tool invocation.",
	"Do not split the answer across several calls.",
	"Everything outside the call is discarded.",
}

var controlClosers = []string{
	"This rule applies to every turn.",
	"The same rule holds for all turns.",
	"Apply this consistently for the whole conversation.",
	"",
	"",
}

var toolDescriptions = []string{
	"Use this transport tool exactly once for the final user-visible answer. Put the complete answer in content. Never wrap genuine tool calls in it.",
	"Transport channel for the final reply. Call it once with the whole user-visible answer in content; do not place real tool calls inside it.",
	"Delivers the finished answer to the user. Fill content with the complete reply and invoke it a single time per turn.",
	"Final answer carrier. One call, complete text in content, no assistant prose outside the call, and no nested function calls.",
	"Hands the final response back to the caller. Use it once with the entire answer in content; genuine tool calls stay separate.",
	"Output channel for the user-facing reply. Provide the full text as content in exactly one call, never wrapping real tool invocations.",
	"Single-use transport for the final message. The content argument must hold the complete answer; real function calls are never nested here.",
	"Passes the finished reply to the user. Invoke once with the whole answer in content and emit nothing else alongside it.",
	"Terminal transport step. Call it once, put the complete final answer in content, and never route genuine tool calls through it.",
}

var continuationPrompts = []string{
	"Please continue.",
	"Continue.",
	"Proceed with the answer.",
	"Please go ahead.",
	"Continue from here.",
	"Complete the response.",
	"Please carry on.",
	"Go on with the reply.",
}

var correctionTemplates = []string{
	"Your previous response had formatting issues in the transport tool arguments. Please call the transport tool `%s` with valid JSON format `{\"content\": \"...\"}`.",
	"The previous transport call could not be parsed. Retry with `%s` and pass a valid JSON object of the form `{\"content\": \"...\"}`.",
	"Formatting was invalid in the last transport call. Use `%s` once more and supply properly escaped JSON: `{\"content\": \"...\"}`.",
	"The last reply did not produce decodable transport arguments. Send the answer again through `%s` as valid JSON `{\"content\": \"...\"}`.",
}

// canonicalTransportDescription is the stable description used when request
// shape entropy is disabled.
const canonicalTransportDescription = "Use this transport tool exactly once for the final user-visible answer. Put the complete answer in content. Never wrap genuine tool calls in it."

// canonicalControlPrompt is the stable control prompt used when request shape
// entropy is disabled.
const canonicalControlPrompt = "Always use tool `%[1]s` for the final reply and put the complete user-visible answer in its `content` argument. %[2]s"

// buildToolName returns a synthetic tool name. An explicit configured prefix is
// honored verbatim; otherwise the name is assembled from randomized pools.
func (inj *RequestInjector) buildToolName(src *entropy.Source, existing map[string]bool) (string, error) {
	prefix := strings.TrimSpace(inj.cfg.SyntheticToolPrefix)
	if prefix == "" && !inj.entropyOpts.Level.Enabled() {
		prefix = "agw_emit_"
	}
	for attempt := 0; attempt < 100; attempt++ {
		var name string
		if prefix != "" {
			// Backwards-compatible shape: fixed prefix and a 96-bit hex nonce.
			name = prefix + src.Hex(12)
		} else {
			name = randomToolName(src)
		}
		if name == "" || len(name) > 64 {
			continue
		}
		if existing != nil && existing[name] {
			continue
		}
		return name, nil
	}
	return "", fmt.Errorf("failed to generate unique synthetic tool name after 100 attempts")
}

func randomToolName(src *entropy.Source) string {
	verb := src.Pick(toolNameVerbs)
	object := src.Pick(toolNameObjects)
	switch src.IntN(5) {
	case 0:
		return verb + "_" + src.Hex(10)
	case 1:
		return verb + "-" + src.AlphaNum(14)
	case 2:
		return verb + strings.ToUpper(object[:1]) + object[1:] + src.Hex(8)
	case 3:
		return verb + "_" + object + "_" + src.Digits(6)
	default:
		return object + "_" + verb + src.AlphaNum(10)
	}
}

// buildToolDescription returns a randomized description for the transport tool.
func (inj *RequestInjector) buildToolDescription(src *entropy.Source) string {
	if !inj.entropyOpts.Level.AtLeast(entropy.LevelStandard) {
		return canonicalTransportDescription
	}
	desc := src.Pick(toolDescriptions)
	if src.Invisible() {
		desc = src.Sprinkle(desc, 60)
	}
	return desc
}

// buildControlPrompt assembles the injected control message text.
func (inj *RequestInjector) buildControlPrompt(src *entropy.Source, toolName string) string {
	if !inj.entropyOpts.Level.AtLeast(entropy.LevelStandard) {
		return fmt.Sprintf(canonicalControlPrompt, toolName, "Call it exactly once per turn.")
	}
	template := src.Pick(controlTemplates)
	constraint := src.Pick(controlConstraints)
	prompt := fmt.Sprintf(template, toolName, constraint)
	if closer := src.Pick(controlClosers); closer != "" {
		prompt += " " + closer
	}
	if src.Invisible() {
		prompt = src.Sprinkle(prompt, 60)
	}
	return prompt
}

// buildContinuation returns a randomized turn-completion prompt.
func (inj *RequestInjector) buildContinuation(src *entropy.Source, canonical string) string {
	if !inj.entropyOpts.Level.AtLeast(entropy.LevelStandard) {
		return canonical
	}
	return src.Pick(continuationPrompts)
}

// BuildCorrectionMessage returns a randomized format-correction prompt.
func (inj *RequestInjector) BuildCorrectionMessage(src *entropy.Source, toolName string) string {
	if !inj.entropyOpts.Level.AtLeast(entropy.LevelStandard) {
		return fmt.Sprintf(correctionTemplates[0], toolName)
	}
	msg := fmt.Sprintf(src.Pick(correctionTemplates), toolName)
	if src.Invisible() {
		msg = src.Sprinkle(msg, 60)
	}
	return msg
}
