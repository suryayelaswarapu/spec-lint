package report

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
)

var findings = []lint.Finding{
	{Rule: "info-contact", Severity: lint.SevWarning, Message: "info.contact is missing", Path: []string{"info"}, Line: 2},
	{Rule: "ref-resolves", Severity: lint.SevError, Message: "bad | ref", Path: []string{"paths", "/a"}},
	{Rule: "info-license", Severity: lint.SevInfo, Message: "no license", Path: []string{}},
}

func TestText(t *testing.T) {
	out := Text(findings, "api.yaml")
	if !strings.Contains(out, "api.yaml:2  warning") || !strings.Contains(out, "3 problems (1 errors, 1 warnings, 1 info)") {
		t.Error(out)
	}
	if !strings.Contains(Text(nil, "a"), "no problems found") || !strings.Contains(Text(findings[:1], "a"), "1 problem (") {
		t.Error("summary")
	}
}

func TestJSONAndSARIF(t *testing.T) {
	var j map[string]any
	if err := json.Unmarshal([]byte(JSON(findings, "api.yaml")), &j); err != nil {
		t.Fatal(err)
	}
	if j["summary"].(map[string]any)["error"] != 1.0 || j["findings"].([]any)[1].(map[string]any)["path"] != "/paths/~1a" {
		t.Error(j)
	}
	var s map[string]any
	if err := json.Unmarshal([]byte(SARIF(findings, "api.yaml")), &s); err != nil {
		t.Fatal(err)
	}
	run := s["runs"].([]any)[0].(map[string]any)
	rules := run["tool"].(map[string]any)["driver"].(map[string]any)["rules"].([]any)
	results := run["results"].([]any)
	if s["version"] != "2.1.0" || rules[0].(map[string]any)["id"] != "info-contact" ||
		results[2].(map[string]any)["level"] != "note" {
		t.Error(s)
	}
}

func TestMarkdown(t *testing.T) {
	md := Markdown("in.wsdl", "WSDL 1.1", "out.yaml", []string{"w|1"},
		[]fix.Applied{{Rule: "operation-tags", Message: "Tagged x"}}, findings)
	if !strings.Contains(md, `bad \| ref`) || !strings.Contains(md, `w\|1`) || !strings.Contains(md, "operation-tags") {
		t.Error(md)
	}
	clean := Markdown("a", "b", "c", nil, nil, nil)
	if !strings.Contains(clean, "meets the standard") || !strings.Contains(clean, "_None._") {
		t.Error(clean)
	}
}
