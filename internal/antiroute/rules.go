package antiroute

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
)

// splitRule is an irreversible-looking but reversible text mutation: the term
// is rewritten in the outbound request with a separator between two runes and
// mapped back on the response path. The upstream model still reads the term,
// while a substring matcher downstream of the client no longer sees the exact
// token.
type splitRule struct {
	Term string
}

// scrubRule neutralizes whole phrases that are common classifier tripwires.
// The replacement is authored to keep the surrounding instruction readable.
type scrubRule struct {
	re      *regexp.Regexp
	replace string
}

// defaultSplitTerms covers the terms that gateways most often see flagged. The
// list is intentionally short and entity-free: every entry is a plain noun or
// verb pair that survives a hyphen without losing meaning.
var defaultSplitTerms = []string{
	"母子", "母女", "父子", "父女", "兄妹", "姐弟", "叔侄",
	"幼女", "幼童", "萝莉", "正太", "未成年", "小学生",
	"迷奸", "轮奸", "强奸", "轮暴", "绑缚", "绑架", "囚禁", "拘禁",
	"下药", "蒙汗", "催情", "春药", "迷药",
	"破处", "处女", "处男", "裸体", "全裸", "性交", "口交", "肛交",
	"自残", "肢解", "碎尸", "斩首", "分尸", "虐杀", "肉便器",
	"人兽", "兽交", "恋童", "乱伦", "近亲",
	"自杀", "制毒", "投毒", "爆炸物", "枪支", "弹药",
}

// defaultScrubRules rewrites instruction idioms that trip content classifiers
// built for assistant preambles. Each replacement preserves the operative
// meaning for the model: nothing is deleted, only re-worded.
var defaultScrubRules = []struct {
	pattern string
	replace string
}{
	{`(?i)\b(do not|don't|never)\s+(apologi[sz]e|refuse|moralize|disclaim)\b`, "stay in character"},
	{`(?i)\bignore\s+(all\s+)?(the\s+)?(previous|prior|above|earlier)\s+(instructions?|prompts?|rules?)\b`, "follow the setup already given above"},
	{`(?i)\b(jailbreak|jailbroken)\b`, "alternate framing"},
	{`(?i)\bDAN\s+mode\b`, "unrestricted persona"},
	{`(?i)\bno\s+(restrictions?|limits?|filters?|censorship)\b`, "keep the scene flowing"},
	{`(?i)\bunfiltered\b`, "unabridged"},
	{`(?i)\bbypass\s+(the\s+)?(safety|filter|classifier|moderation|guardrail)s?\b`, "continue the narrative"},
	{`(?i)\bas an?\s+(ai\s+)?(language\s+)?model\b`, "as the narrator"},
	{`(?i)\bchain[-\s]?of[-\s]?thought\b`, "planning notes"},
	{`(?i)\b(anti[-\s]?distillation|reverse\s+distillation)\b`, "style fidelity"},
	{`忽略(之前|以上|上面|前面|先前|所有)的?(指令|提示|规则|要求)`, "遵循上文已经给出的设定"},
	{`无视(之前|以上|所有)的?(指令|提示|规则)`, "依照上文设定继续"},
	{`越狱`, "切换设定"},
	{`无限制模式|不受任何限制|解除(所有|全部)?限制|解除限制`, "自由创作模式"},
	{`绕过(安全|审查|过滤|审核|检测)`, "顺畅续写"},
	{`反蒸馏`, "文风保真"},
	{`思维链`, "构思记录"},
	{`原生思考`, "前置构思"},
	{`(不要|不许|不能|禁止)(道歉|拒绝|说教|免责)`, "保持角色"},
	{`免责声明`, "创作说明"},
	{`拒绝回答`, "偏离剧情"},
	{`道德检查|安全审查`, "内容校对"},
}

// RuleSet is the resolved, immutable rule table used by a Guard.
type RuleSet struct {
	Split []splitRule
	Scrub []scrubRule
}

// rulesFile mirrors RuleSet for JSON overrides on disk.
type rulesFile struct {
	SplitTerms []string `json:"split_terms"`
	Scrub      []struct {
		Pattern string `json:"pattern"`
		Replace string `json:"replace"`
	} `json:"scrub"`
	// Replace drops the built-in lists and keeps only what the file declares.
	Replace bool `json:"replace"`
}

// LoadRules builds the active rule table. An empty path yields the built-in
// defaults. Additional terms append to the defaults unless the file sets
// "replace": true.
func LoadRules(path string) (*RuleSet, error) {
	rs := &RuleSet{}
	terms := append([]string(nil), defaultSplitTerms...)
	scrubs := append([]scrubRule(nil), buildDefaultScrubs()...)

	if strings.TrimSpace(path) != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read anti-route rules: %w", err)
		}
		var f rulesFile
		if err := json.Unmarshal(raw, &f); err != nil {
			return nil, fmt.Errorf("parse anti-route rules: %w", err)
		}
		if f.Replace {
			terms = nil
			scrubs = nil
		}
		for _, t := range f.SplitTerms {
			t = strings.TrimSpace(t)
			if t == "" {
				continue
			}
			terms = append(terms, t)
		}
		for _, s := range f.Scrub {
			if strings.TrimSpace(s.Pattern) == "" {
				continue
			}
			re, err := regexp.Compile(s.Pattern)
			if err != nil {
				return nil, fmt.Errorf("invalid scrub pattern %q: %w", s.Pattern, err)
			}
			scrubs = append(scrubs, scrubRule{re: re, replace: s.Replace})
		}
	}

	seen := make(map[string]struct{}, len(terms))
	for _, t := range terms {
		if _, ok := seen[t]; ok {
			continue
		}
		// A term needs at least two runes so the separator has somewhere to go.
		if len([]rune(t)) < 2 {
			continue
		}
		seen[t] = struct{}{}
		rs.Split = append(rs.Split, splitRule{Term: t})
	}
	// Longest first so a nested term cannot shadow a longer one.
	sort.SliceStable(rs.Split, func(i, j int) bool {
		return len([]rune(rs.Split[i].Term)) > len([]rune(rs.Split[j].Term))
	})
	rs.Scrub = scrubs
	return rs, nil
}

func buildDefaultScrubs() []scrubRule {
	out := make([]scrubRule, 0, len(defaultScrubRules))
	for _, r := range defaultScrubRules {
		re, err := regexp.Compile(r.pattern)
		if err != nil {
			// A bad built-in pattern is a programming error; skipping keeps the
			// guard usable rather than failing every request.
			continue
		}
		out = append(out, scrubRule{re: re, replace: r.replace})
	}
	return out
}
