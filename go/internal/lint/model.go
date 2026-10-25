// Package lint runs the shared spec-lint ruleset (see docs/rules.md) over OpenAPI documents.
package lint

import (
	"sort"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

// Severity of a finding; Off disables a rule.
type Severity string

const (
	SevError   Severity = "error"
	SevWarning Severity = "warning"
	SevInfo    Severity = "info"
	SevOff     Severity = "off"
)

// Rank orders severities (error highest).
func (s Severity) Rank() int {
	return map[Severity]int{SevError: 3, SevWarning: 2, SevInfo: 1, SevOff: 0}[s]
}

// ParseSeverity validates a severity string.
func ParseSeverity(v string) (Severity, bool) {
	switch Severity(v) {
	case SevError, SevWarning, SevInfo, SevOff:
		return Severity(v), true
	}
	return "", false
}

// Hit is a raw rule result before severity and line numbers are attached.
type Hit struct {
	Message string
	Path    []string
}

// Rule is a registered lint rule.
type Rule struct {
	ID          string
	Severity    Severity
	Description string
	Fixable     bool
	Check       func(*doc.Map) []Hit
}

// Finding is one lint result. Line is 0 when unknown.
type Finding struct {
	Rule     string
	Severity Severity
	Message  string
	Path     []string
	Line     int
}

// Pointer encodes a path as an RFC 6901 JSON Pointer.
func Pointer(path []string) string {
	var sb strings.Builder
	for _, p := range path {
		sb.WriteByte('/')
		sb.WriteString(strings.ReplaceAll(strings.ReplaceAll(p, "~", "~0"), "/", "~1"))
	}
	return sb.String()
}

// Pointer of the finding's path.
func (f Finding) Pointer() string { return Pointer(f.Path) }

// ToMap renders the finding for JSON output.
func (f Finding) ToMap() *doc.Map {
	m := doc.M("rule", f.Rule, "severity", string(f.Severity), "message", f.Message,
		"path", f.Pointer())
	if f.Line > 0 {
		m.Set("line", int64(f.Line))
	}
	return m
}

// Lint runs every enabled rule and returns findings sorted by line, rule and pointer.
func Lint(d *doc.Map, cfg *Config, lines map[string]int) []Finding {
	if cfg == nil {
		cfg = DefaultConfig()
	}
	var out []Finding
	for _, r := range Rules() {
		sev := cfg.Severity(r.ID)
		if sev == SevOff {
			continue
		}
		for _, h := range r.Check(d) {
			line := 0
			for depth := len(h.Path); depth >= 0 && len(lines) > 0; depth-- {
				if l, ok := lines[strings.Join(h.Path[:depth], "\x00")]; ok {
					line = l
					break
				}
			}
			out = append(out, Finding{r.ID, sev, h.Message, h.Path, line})
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.Line != b.Line {
			return a.Line < b.Line
		}
		if a.Rule != b.Rule {
			return a.Rule < b.Rule
		}
		return a.Pointer() < b.Pointer()
	})
	return out
}

// Summarize counts findings by severity.
func Summarize(findings []Finding) map[string]int {
	c := map[string]int{"error": 0, "warning": 0, "info": 0}
	for _, f := range findings {
		c[string(f.Severity)]++
	}
	return c
}
