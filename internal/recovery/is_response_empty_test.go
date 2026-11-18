package recovery

import "testing"

func TestIsResponseEmpty(t *testing.T) {
	cases := []struct {
		name string
		body string
		want bool
	}{
		{"empty body", "", true},
		{"null choices", `{"choices":[]}`, true},
		{"empty content", `{"choices":[{"message":{"content":""}}]}`, true},
		{"whitespace content", `{"choices":[{"message":{"content":"   "}}]}`, true},
		{"text content", `{"choices":[{"message":{"content":"hi"}}]}`, false},
		{"reasoning only", `{"choices":[{"message":{"reasoning_content":"thinking..."}}]}`, false},
		{"tool call", `{"choices":[{"message":{"tool_calls":[{"id":"1"}]}}]}`, false},
		{"gemini candidates", `{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}`, false},
		{"gemini empty parts", `{"candidates":[{"content":{"parts":[]}}]}`, true},
		{"upstream error", `{"error":{"message":"boom"}}`, false},
		{"non-json", `not json`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isResponseEmpty([]byte(tc.body)); got != tc.want {
				t.Fatalf("isResponseEmpty(%s) = %v, want %v", tc.body, got, tc.want)
			}
		})
	}
}
