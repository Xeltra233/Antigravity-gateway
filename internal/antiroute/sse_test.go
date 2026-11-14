package antiroute

import (
	"io"
	"strings"
	"testing"
)

func newRewriterFor(t *testing.T, stream string, terms ...string) io.Reader {
	t.Helper()
	g, err := New(Options{Level: LevelDeep, WireEscape: false, Split: true, Separator: SepHyphen})
	if err != nil {
		t.Fatalf("guard: %v", err)
	}
	session := g.NewSession()
	content := strings.Join(terms, " / ")
	body := `{"messages":[{"role":"user","content":"` + content + `"}]}`
	if _, err := session.Protect([]byte(body), nil); err != nil {
		t.Fatalf("protect: %v", err)
	}
	if session.NewStreamRestorer() == nil {
		t.Fatalf("session recorded no masked term for %q", content)
	}
	rw := session.NewStreamRewriter(strings.NewReader(stream))
	if rw == nil {
		t.Fatal("rewriter is nil")
	}
	return rw
}

func readAll(t *testing.T, r io.Reader) string {
	t.Helper()
	b, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return string(b)
}

func TestStreamRewriterRestoresTermSplitAcrossEvents(t *testing.T) {
	stream := "data: {\"choices\":[{\"delta\":{\"content\":\"她提到母-\"}}]}\n\n" +
		"data: {\"choices\":[{\"delta\":{\"content\":\"子这个词。\"}}]}\n\n" +
		"data: [DONE]\n\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if !strings.Contains(out, "母子") {
		t.Fatalf("masked term not restored: %q", out)
	}
	if strings.Contains(out, "母-子") {
		t.Fatalf("masked form leaked: %q", out)
	}
	if !strings.HasSuffix(out, "data: [DONE]\n\n") {
		t.Fatalf("stream terminator lost: %q", out)
	}
}

func TestStreamRewriterRestoresTermSplitInsideOneEvent(t *testing.T) {
	stream := "data: {\"choices\":[{\"delta\":{\"content\":\"他强-奸了她\"}}]}\n\n"
	out := readAll(t, newRewriterFor(t, stream, "强奸"))
	if !strings.Contains(out, "强奸") || strings.Contains(out, "强-奸") {
		t.Fatalf("in-event term not restored: %q", out)
	}
}

func TestStreamRewriterFlushesHeldPrefixAtEOF(t *testing.T) {
	// The stream ends right after the first half of the masked term.
	stream := "data: {\"choices\":[{\"delta\":{\"content\":\"他说母子\"}}]}\n\ndata: [DONE]\n\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if !strings.Contains(out, "母子") {
		t.Fatalf("held prefix lost at EOF: %q", out)
	}
}

func TestStreamRewriterPassesUnknownPayloadsThrough(t *testing.T) {
	stream := ": keep-alive comment\n\n" +
		"data: not-json-at-all\n\n" +
		"event: ping\n\n" +
		"data: {\"choices\":[{\"delta\":{\"content\":\"clean text\"}}]}\n\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if out != stream {
		t.Fatalf("unrelated lines were rewritten:\n got %q\nwant %q", out, stream)
	}
}

func TestStreamRewriterHandlesCandidateShape(t *testing.T) {
	stream := "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"母-\"}]}}]}\n\n" +
		"data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"子重逢\"}]}}]}\n\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if !strings.Contains(out, "母子") || strings.Contains(out, "母-") {
		t.Fatalf("candidate payload not restored: %q", out)
	}
}

func TestStreamRewriterCRLFTerminator(t *testing.T) {
	stream := "data: {\"choices\":[{\"delta\":{\"content\":\"母-子\"}}]}\r\n\r\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if !strings.Contains(out, "母子") {
		t.Fatalf("CRLF stream not restored: %q", out)
	}
	if !strings.HasSuffix(out, "\r\n\r\n") || strings.HasSuffix(out, "\r\n\r\n\n") {
		t.Fatalf("CRLF terminator not preserved: %q", out)
	}
}

func TestNilRewriterWhenNothingMasked(t *testing.T) {
	g, err := New(Options{Level: LevelWire, WireEscape: true})
	if err != nil {
		t.Fatalf("guard: %v", err)
	}
	session := g.NewSession()
	if _, err := session.Protect([]byte(`{"messages":[{"role":"user","content":"平淡"}]}`), nil); err != nil {
		t.Fatalf("protect: %v", err)
	}
	if rw := session.NewStreamRewriter(strings.NewReader("data: {}\n\n")); rw != nil {
		t.Fatal("rewriter must stay nil when no term was masked")
	}
}

func TestStreamRewriterReleasesTailBeforeDone(t *testing.T) {
	// The stream is cut right after the first half, then terminates.
	stream := "data: {\"choices\":[{\"delta\":{\"content\":\"他说母\"}}]}\n\ndata: [DONE]\n\n"
	out := readAll(t, newRewriterFor(t, stream, "母子"))
	if strings.Contains(out, "他说母") && !strings.Contains(out, "母子") {
		t.Fatalf("held prefix never completed before [DONE]: %q", out)
	}
	done := strings.Index(out, "data: [DONE]")
	if done < 0 {
		t.Fatalf("terminator missing: %q", out)
	}
	if strings.Index(out, "母子") > done {
		t.Fatalf("restored text arrived after the terminator: %q", out)
	}
}
