package synthetic

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"helix-gateway/internal/config"
)

type StreamStats struct {
	SyntheticHit       bool
	RealToolCallCount  int
	SyntheticCallCount int
	ContentConflict    bool
	BytesWritten       int64
	// Heartbeats counts the keep-alive comments the gateway emitted itself.
	Heartbeats int
	// CommentsForwarded counts upstream SSE comment frames passed downstream.
	CommentsForwarded int
	// UpstreamError reports that the upstream sent an error frame. No [DONE]
	// is written in that case, so clients can tell the failure apart from a
	// normal end of stream.
	UpstreamError bool
	// SawDone and SawFinish record what the upstream signalled before the
	// connection ended. A stream that ends without either one is truncated.
	SawDone   bool
	SawFinish bool
}

type ChoiceStreamState struct {
	Index              int
	HasSynthetic       bool
	SyntheticDecoder   *IncrementalJSONStringDecoder
	SyntheticToolIndex int
	SideBuffer         strings.Builder
	SideBufferEmitted  bool
	RealToolsSeen      map[int]int // original index -> remapped index
	NextRealIndex      int
	RoleEmitted        bool
}

type StreamTransformer struct {
	cfg               *config.Config
	syntheticToolName string
}

func NewStreamTransformer(cfg *config.Config, syntheticToolName string) *StreamTransformer {
	return &StreamTransformer{
		cfg:               cfg,
		syntheticToolName: syntheticToolName,
	}
}

type RawChunk struct {
	ID      string           `json:"id"`
	Object  string           `json:"object"`
	Created int64            `json:"created"`
	Model   string           `json:"model"`
	Choices []RawChunkChoice `json:"choices"`
	Usage   any              `json:"usage,omitempty"`
	Error   any              `json:"error,omitempty"`
}

type RawChunkChoice struct {
	Index        int           `json:"index"`
	Delta        RawChunkDelta `json:"delta"`
	FinishReason any           `json:"finish_reason"`
}

type RawChunkDelta struct {
	Role             string             `json:"role,omitempty"`
	Content          any                `json:"content,omitempty"` // string or nil
	ReasoningContent any                `json:"reasoning_content,omitempty"`
	Reasoning        any                `json:"reasoning,omitempty"`
	ToolCalls        []RawChunkToolCall `json:"tool_calls,omitempty"`
}

type RawChunkToolCall struct {
	Index    int                      `json:"index"`
	ID       string                   `json:"id,omitempty"`
	Type     string                   `json:"type,omitempty"`
	Function RawChunkFunctionCallDesc `json:"function"`
}

type RawChunkFunctionCallDesc struct {
	Name      string `json:"name,omitempty"`
	Arguments string `json:"arguments,omitempty"`
}

// Transform forwards an upstream SSE stream to the downstream client.
//
// Everything the upstream frames explicitly is preserved: comment frames and
// event/id/retry field lines reach the client unchanged, because dropping them
// removes the only liveness signal a long-running generation has. Payloads the
// transformer cannot parse are forwarded verbatim instead of being discarded.
//
// The stream terminator is only written for a stream that actually finished.
// A truncated or failed upstream leaves the downstream without [DONE], which is
// what client-side anti-truncation logic keys on.
func (st *StreamTransformer) Transform(upstreamReader io.Reader, w io.Writer, flusher http.Flusher) (*StreamStats, error) {
	stats := &StreamStats{}

	hb := NewHeartbeatWriter(w, flusher, st.cfg.StreamHeartbeat)
	defer func() {
		if hb != nil {
			hb.Close()
			stats.Heartbeats = hb.HeartbeatsSent()
		}
	}()

	var out io.Writer = w
	if hb != nil {
		out = hb
	}

	emit := func(s string) error {
		n, err := io.WriteString(out, s)
		stats.BytesWritten += int64(n)
		if flusher != nil {
			flusher.Flush()
		}
		return err
	}

	scanner := bufio.NewScanner(upstreamReader)
	// Buffer size up to max response chunk
	buf := make([]byte, 64*1024)
	scanner.Buffer(buf, int(st.cfg.MaxResponseBytes))

	states := make(map[int]*ChoiceStreamState)

	getState := func(idx int) *ChoiceStreamState {
		if s, ok := states[idx]; ok {
			return s
		}
		s := &ChoiceStreamState{
			Index:              idx,
			SyntheticDecoder:   NewIncrementalJSONStringDecoder(),
			SyntheticToolIndex: -1,
			RealToolsSeen:      make(map[int]int),
		}
		states[idx] = s
		return s
	}

	// prefix holds the SSE field lines (event/id/retry) that belong to the
	// payload arriving next. They are emitted ahead of that payload so the
	// frame keeps its original shape.
	var prefix []string

	emitFrame := func(fieldLines []string, data []byte) error {
		var sb strings.Builder
		for _, p := range fieldLines {
			sb.WriteString(p)
			sb.WriteString("\n")
		}
		sb.WriteString("data: ")
		sb.Write(data)
		sb.WriteString("\n\n")
		return emit(sb.String())
	}

	writeSSEData := func(data any) error {
		b, err := json.Marshal(data)
		if err != nil {
			return err
		}
		return emitFrame(prefix, b)
	}

	writeStreamError := func(msg string) {
		payload, _ := json.Marshal(map[string]any{
			"error": map[string]any{
				"message": msg,
				"type":    "upstream_stream_error",
				"code":    "stream_interrupted",
			},
		})
		_ = emit("event: error\ndata: " + string(payload) + "\n\n")
	}

	var readErr error

	for scanner.Scan() {
		line := scanner.Text()
		trimmed := strings.TrimSpace(line)

		if trimmed == "" {
			// End of an SSE frame. Any field lines still buffered were not
			// followed by a payload, so they are passed through as-is.
			if len(prefix) > 0 {
				var sb strings.Builder
				for _, p := range prefix {
					sb.WriteString(p)
					sb.WriteString("\n")
				}
				sb.WriteString("\n")
				_ = emit(sb.String())
				prefix = nil
			}
			continue
		}

		if strings.HasPrefix(trimmed, ":") {
			// SSE comment / keep-alive. Forward it: this is the upstream's own
			// liveness signal and the client may rely on it.
			stats.CommentsForwarded++
			_ = emit(line + "\n\n")
			continue
		}

		switch {
		case strings.HasPrefix(trimmed, "event:"),
			strings.HasPrefix(trimmed, "id:"),
			strings.HasPrefix(trimmed, "retry:"):
			prefix = append(prefix, trimmed)
			continue
		case !strings.HasPrefix(trimmed, "data:"):
			// Unknown field line; keep it rather than silently losing framing.
			prefix = append(prefix, trimmed)
			continue
		}

		dataContent := strings.TrimSpace(strings.TrimPrefix(trimmed, "data:"))
		if dataContent == "[DONE]" {
			stats.SawDone = true
			prefix = nil
			// The terminator is written once, after the loop, so both paths
			// produce the same framing.
			break
		}

		var chunk RawChunk
		if err := json.Unmarshal([]byte(dataContent), &chunk); err != nil {
			// Not a payload this gateway understands. Forward it verbatim so a
			// provider-specific frame is not lost in translation.
			_ = emitFrame(prefix, []byte(dataContent))
			prefix = nil
			continue
		}

		// Upstream error frame (no choices, error object present).
		if chunk.Error != nil && len(chunk.Choices) == 0 {
			stats.UpstreamError = true
			_ = emitFrame(prefix, []byte(dataContent))
			prefix = nil
			continue
		}

		// If chunk has no choices but has usage info, forward as usage chunk
		if len(chunk.Choices) == 0 && chunk.Usage != nil {
			usageChunk := map[string]any{
				"id":      chunk.ID,
				"object":  "chat.completion.chunk",
				"created": chunk.Created,
				"model":   chunk.Model,
				"choices": []any{},
				"usage":   chunk.Usage,
			}
			_ = writeSSEData(usageChunk)
			prefix = nil
			continue
		}

		// Process choices
		for _, c := range chunk.Choices {
			state := getState(c.Index)

			// Emit initial role chunk if present
			if c.Delta.Role != "" && !state.RoleEmitted {
				roleChunk := map[string]any{
					"id":      chunk.ID,
					"object":  "chat.completion.chunk",
					"created": chunk.Created,
					"model":   chunk.Model,
					"choices": []map[string]any{
						{
							"index": c.Index,
							"delta": map[string]any{
								"role": c.Delta.Role,
							},
							"finish_reason": nil,
						},
					},
				}
				_ = writeSSEData(roleChunk)
				state.RoleEmitted = true
			}

			// Process reasoning content delta (e.g. thinking models)
			var reasoningStr string
			if c.Delta.ReasoningContent != nil {
				if s, ok := c.Delta.ReasoningContent.(string); ok && s != "" {
					reasoningStr = s
				}
			} else if c.Delta.Reasoning != nil {
				if s, ok := c.Delta.Reasoning.(string); ok && s != "" {
					reasoningStr = s
				}
			}
			if reasoningStr != "" {
				reasoningChunk := map[string]any{
					"id":      chunk.ID,
					"object":  "chat.completion.chunk",
					"created": chunk.Created,
					"model":   chunk.Model,
					"choices": []map[string]any{
						{
							"index": c.Index,
							"delta": map[string]any{
								"reasoning_content": reasoningStr,
							},
							"finish_reason": nil,
						},
					},
				}
				_ = writeSSEData(reasoningChunk)
			}

			// Process tool calls
			if len(c.Delta.ToolCalls) > 0 {
				var realToolCallsToEmit []map[string]any

				for _, tc := range c.Delta.ToolCalls {
					tIdx := tc.Index

					// Check if this tool index is synthetic
					if state.SyntheticToolIndex == tIdx || (tc.Function.Name != "" && tc.Function.Name == st.syntheticToolName) {
						state.HasSynthetic = true
						state.SyntheticToolIndex = tIdx
						stats.SyntheticHit = true

						// Discard side buffer on synthetic hit
						if state.SideBuffer.Len() > 0 && !state.SideBufferEmitted {
							stats.ContentConflict = true
							state.SideBuffer.Reset()
						} else if state.SideBufferEmitted {
							stats.ContentConflict = true
						}

						if tc.Function.Arguments != "" {
							emitted, _, err := state.SyntheticDecoder.Feed(tc.Function.Arguments)
							if err == nil && emitted != "" {
								contentChunk := map[string]any{
									"id":      chunk.ID,
									"object":  "chat.completion.chunk",
									"created": chunk.Created,
									"model":   chunk.Model,
									"choices": []map[string]any{
										{
											"index": c.Index,
											"delta": map[string]any{
												"content": emitted,
											},
											"finish_reason": nil,
										},
									},
								}
								_ = writeSSEData(contentChunk)
							}
						}
					} else {
						// Real tool call
						stats.RealToolCallCount++
						remappedIdx, exists := state.RealToolsSeen[tIdx]
						if !exists {
							remappedIdx = state.NextRealIndex
							state.RealToolsSeen[tIdx] = remappedIdx
							state.NextRealIndex++
						}

						fnMap := map[string]any{}
						if tc.Function.Name != "" {
							fnMap["name"] = tc.Function.Name
						}
						if tc.Function.Arguments != "" {
							fnMap["arguments"] = tc.Function.Arguments
						}

						tcMap := map[string]any{
							"index":    remappedIdx,
							"function": fnMap,
						}
						if tc.ID != "" {
							tcMap["id"] = tc.ID
						}
						if tc.Type != "" {
							tcMap["type"] = tc.Type
						}

						realToolCallsToEmit = append(realToolCallsToEmit, tcMap)
					}
				}

				if len(realToolCallsToEmit) > 0 {
					toolChunk := map[string]any{
						"id":      chunk.ID,
						"object":  "chat.completion.chunk",
						"created": chunk.Created,
						"model":   chunk.Model,
						"choices": []map[string]any{
							{
								"index": c.Index,
								"delta": map[string]any{
									"tool_calls": realToolCallsToEmit,
								},
								"finish_reason": nil,
							},
						},
					}
					_ = writeSSEData(toolChunk)
				}
			}

			// Process standard content delta with true streaming
			if c.Delta.Content != nil {
				if contentStr, ok := c.Delta.Content.(string); ok && contentStr != "" {
					if state.HasSynthetic {
						// Drop duplicate standard content when synthetic tool is already active
						stats.ContentConflict = true
					} else {
						if st.cfg.StreamSideBufferBytes > 0 && !state.SideBufferEmitted {
							state.SideBuffer.WriteString(contentStr)
							if state.SideBuffer.Len() >= st.cfg.StreamSideBufferBytes {
								flushChunk := map[string]any{
									"id":      chunk.ID,
									"object":  "chat.completion.chunk",
									"created": chunk.Created,
									"model":   chunk.Model,
									"choices": []map[string]any{
										{
											"index": c.Index,
											"delta": map[string]any{
												"content": state.SideBuffer.String(),
											},
											"finish_reason": nil,
										},
									},
								}
								_ = writeSSEData(flushChunk)
								state.SideBufferEmitted = true
								state.SideBuffer.Reset()
							}
						} else {
							// True streaming: emit immediately!
							if state.SideBuffer.Len() > 0 {
								contentStr = state.SideBuffer.String() + contentStr
								state.SideBuffer.Reset()
							}
							contentChunk := map[string]any{
								"id":      chunk.ID,
								"object":  "chat.completion.chunk",
								"created": chunk.Created,
								"model":   chunk.Model,
								"choices": []map[string]any{
									{
										"index": c.Index,
										"delta": map[string]any{
											"content": contentStr,
										},
										"finish_reason": nil,
									},
								},
							}
							_ = writeSSEData(contentChunk)
							state.SideBufferEmitted = true
						}
					}
				}
			}

			// Process finish_reason
			if c.FinishReason != nil {
				finishReasonStr, _ := c.FinishReason.(string)
				stats.SawFinish = true

				// If choice had synthetic call, check if remaining finish reason was tool_calls
				if state.HasSynthetic {
					// Check remaining buffer repair
					rem, _ := state.SyntheticDecoder.Finish(int(st.cfg.MaxResponseBytes))
					if rem != "" {
						remChunk := map[string]any{
							"id":      chunk.ID,
							"object":  "chat.completion.chunk",
							"created": chunk.Created,
							"model":   chunk.Model,
							"choices": []map[string]any{
								{
									"index": c.Index,
									"delta": map[string]any{
										"content": rem,
									},
									"finish_reason": nil,
								},
							},
						}
						_ = writeSSEData(remChunk)
					}

					if finishReasonStr == "tool_calls" {
						if len(state.RealToolsSeen) == 0 {
							finishReasonStr = "stop"
						}
					}
				} else {
					// No synthetic call: flush remaining side buffer if any
					if state.SideBuffer.Len() > 0 {
						fbChunk := map[string]any{
							"id":      chunk.ID,
							"object":  "chat.completion.chunk",
							"created": chunk.Created,
							"model":   chunk.Model,
							"choices": []map[string]any{
								{
									"index": c.Index,
									"delta": map[string]any{
										"content": state.SideBuffer.String(),
									},
									"finish_reason": nil,
								},
							},
						}
						_ = writeSSEData(fbChunk)
						state.SideBufferEmitted = true
						state.SideBuffer.Reset()
					}
				}
				finalChunk := map[string]any{
					"id":      chunk.ID,
					"object":  "chat.completion.chunk",
					"created": chunk.Created,
					"model":   chunk.Model,
					"choices": []map[string]any{
						{
							"index":         c.Index,
							"delta":         map[string]any{},
							"finish_reason": finishReasonStr,
						},
					},
				}
				if chunk.Usage != nil {
					finalChunk["usage"] = chunk.Usage
				}
				_ = writeSSEData(finalChunk)
			}
		}

		// The frame has been consumed, so pending field lines no longer apply.
		prefix = nil
	}

	if err := scanner.Err(); err != nil && err != io.EOF {
		readErr = err
	}

	// Decide how the stream terminates.
	//
	// A terminator is a promise that the response is complete. Writing one for
	// a truncated stream turns a detectable failure into a silent one, so it is
	// only written when the upstream actually finished.
	switch {
	case stats.UpstreamError:
		// The upstream's own error frame was forwarded; leave it as the last
		// thing the client sees.
		return stats, nil
	case readErr != nil:
		writeStreamError("upstream stream interrupted: " + readErr.Error())
		return stats, fmt.Errorf("error reading upstream SSE stream: %w", readErr)
	case !stats.SawDone && !stats.SawFinish:
		writeStreamError("upstream stream ended without a finish_reason or [DONE]")
		return stats, fmt.Errorf("upstream SSE stream ended without terminator")
	}

	// Emit [DONE] for a stream that already carried one, so the client gets the
	// terminating frame from exactly one place.
	n, _ := io.WriteString(out, "data: [DONE]\n\n")
	stats.BytesWritten += int64(n)
	if flusher != nil {
		flusher.Flush()
	}

	return stats, nil
}
