// Package report renders lint results as text, JSON, SARIF 2.1.0 or a Markdown report.
package report

import (
	"fmt"
	"sort"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// Version of the tool, kept in step with the Python and Java implementations.
const Version = "0.1.0"

// RepoURL is the project home.
const RepoURL = "https://github.com/suryayelaswarapu/spec-lint"

var sarifLevel = map[lint.Severity]string{lint.SevError: "error", lint.SevWarning: "warning",
	lint.SevInfo: "note"}

// Text renders a human-readable report.
func Text(findings []lint.Finding, filename string) string {
	var sb strings.Builder
	for _, f := range findings {
		loc := filename
		if f.Line > 0 {
			loc = fmt.Sprintf("%s:%d", filename, f.Line)
		}
		fmt.Fprintf(&sb, "%s  %-7s  %-26s  %s\n", loc, f.Severity, f.Rule, f.Message)
	}
	c := lint.Summarize(findings)
	total := c["error"] + c["warning"] + c["info"]
	if total == 0 {
		sb.WriteString(filename + ": no problems found\n")
		return sb.String()
	}
	plural := "s"
	if total == 1 {
		plural = ""
	}
	fmt.Fprintf(&sb, "\n%d problem%s (%d errors, %d warnings, %d info)\n", total, plural,
		c["error"], c["warning"], c["info"])
	return sb.String()
}

func summary(findings []lint.Finding) *doc.Map {
	c := lint.Summarize(findings)
	return doc.M("error", int64(c["error"]), "warning", int64(c["warning"]), "info", int64(c["info"]))
}

// JSON renders a machine-readable report.
func JSON(findings []lint.Finding, filename string) string {
	items := []any{}
	for _, f := range findings {
		items = append(items, f.ToMap())
	}
	return specio.MustDump(doc.M("tool", "speclint", "version", Version, "file", filename,
		"summary", summary(findings), "findings", items), "json")
}

// SARIF renders SARIF 2.1.0 for GitHub code scanning.
func SARIF(findings []lint.Finding, filename string) string {
	usedSet := map[string]bool{}
	for _, f := range findings {
		usedSet[f.Rule] = true
	}
	var used []string
	for k := range usedSet {
		used = append(used, k)
	}
	sort.Strings(used)
	index := map[string]int64{}
	rules := []any{}
	for i, id := range used {
		index[id] = int64(i)
		r, _ := lint.RuleByID(id)
		rules = append(rules, doc.M("id", id,
			"shortDescription", doc.M("text", r.Description),
			"helpUri", RepoURL+"/blob/main/docs/rules.md#"+id,
			"defaultConfiguration", doc.M("level", sarifLevel[r.Severity])))
	}
	results := []any{}
	for _, f := range findings {
		physical := doc.M("artifactLocation", doc.M("uri", filename))
		if f.Line > 0 {
			physical.Set("region", doc.M("startLine", int64(f.Line)))
		}
		name := f.Pointer()
		if name == "" {
			name = "/"
		}
		results = append(results, doc.M(
			"ruleId", f.Rule, "ruleIndex", index[f.Rule], "level", sarifLevel[f.Severity],
			"message", doc.M("text", f.Message),
			"locations", []any{doc.M("physicalLocation", physical,
				"logicalLocations", []any{doc.M("fullyQualifiedName", name)})}))
	}
	return specio.MustDump(doc.M(
		"$schema", "https://json.schemastore.org/sarif-2.1.0.json",
		"version", "2.1.0",
		"runs", []any{doc.M(
			"tool", doc.M("driver", doc.M("name", "speclint", "version", Version,
				"informationUri", RepoURL, "rules", rules)),
			"results", results)}), "json")
}

func md(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, "|", "\\|"), "\n", " ")
}

// Markdown renders the modernize migration report.
func Markdown(source, sourceKind, output string, warnings []string, fixes []fix.Applied,
	findings []lint.Finding) string {
	c := lint.Summarize(findings)
	out := []string{
		"# spec-lint migration report", "",
		"| Source | Detected format | Output |\n|---|---|---|\n| `" + source + "` | " + sourceKind +
			" | `" + output + "` (OpenAPI 3.1) |",
		"", "## Summary", "",
		fmt.Sprintf("- Conversion notes: **%d**", len(warnings)),
		fmt.Sprintf("- Auto-fixes applied: **%d**", len(fixes)),
		fmt.Sprintf("- Remaining findings: **%d** errors, **%d** warnings, **%d** info",
			c["error"], c["warning"], c["info"]),
		"", "## Conversion notes", "",
	}
	if len(warnings) == 0 {
		out = append(out, "_None._")
	}
	for _, w := range warnings {
		out = append(out, "- "+md(w))
	}
	out = append(out, "", "## Auto-fixes applied", "")
	if len(fixes) == 0 {
		out = append(out, "_None._")
	} else {
		out = append(out, "| Rule | Change |", "|---|---|")
		for _, f := range fixes {
			out = append(out, "| `"+f.Rule+"` | "+md(f.Message)+" |")
		}
	}
	out = append(out, "", "## Remaining findings", "")
	if len(findings) == 0 {
		out = append(out, "_No findings: the contract meets the standard._ ✅")
	} else {
		out = append(out, "| Severity | Rule | Line | Message |", "|---|---|---|---|")
		for _, f := range findings {
			line := ""
			if f.Line > 0 {
				line = fmt.Sprint(f.Line)
			}
			out = append(out, fmt.Sprintf("| %s | `%s` | %s | %s |", f.Severity, f.Rule, line, md(f.Message)))
		}
	}
	out = append(out, "", "<sub>Generated by spec-lint "+Version+"</sub>", "")
	return strings.Join(out, "\n")
}
