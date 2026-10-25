// Package server exposes the spec-lint pipeline as a REST API plus a small web UI, using only
// the standard library (net/http). The server never reads its own file system for user input
// (local XSD imports are disabled) and never fetches remote references.
package server

import (
	"regexp"
	"sort"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/convert"
	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/pipeline"
	"github.com/suryayelaswarapu/spec-lint/go/internal/report"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// Implementation identifies this server in /health.
const Implementation = "go"

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9._ -]`)

// SafeFilename keeps only a short, plain base name (used in messages and the report only).
func SafeFilename(name any) string {
	base := ""
	if s, ok := name.(string); ok {
		base = strings.ReplaceAll(s, "\\", "/")
	}
	if i := strings.LastIndex(base, "/"); i >= 0 {
		base = base[i+1:]
	}
	base = unsafeName.ReplaceAllString(strings.TrimSpace(base), "_")
	if len(base) > 200 {
		base = base[:200]
	}
	if base == "" {
		return "spec"
	}
	return base
}

func source(body *doc.Map) (specio.SourceFile, error) {
	spec, ok := body.Get("spec").(string)
	if !ok || strings.TrimSpace(spec) == "" {
		return specio.SourceFile{}, doc.Errorf(doc.ErrInput, "'spec' must be a non-empty string")
	}
	// A virtual path: nothing is ever read from it (local imports are disabled).
	return specio.SourceFile{Path: SafeFilename(body.Get("filename")), Text: spec}, nil
}

func config(body *doc.Map) (*lint.Config, error) {
	raw := body.Get("config")
	if raw == nil {
		return lint.DefaultConfig(), nil
	}
	m := doc.AsMap(raw)
	if m == nil {
		return nil, doc.Errorf(doc.ErrInput, "'config' must be an object")
	}
	picked := doc.NewMap()
	for _, k := range []string{"extends", "rules"} {
		if m.Has(k) {
			picked.Set(k, m.Get(k))
		}
	}
	return lint.ConfigFromMap(picked)
}

func requireOAS3(src specio.SourceFile) (*doc.Map, error) {
	if specio.LooksLikeXML(src.Text) {
		return nil, doc.Errorf(doc.ErrInput, "Input is WSDL; use /api/v1/modernize or /api/v1/convert")
	}
	d, err := specio.ParseStructured(src.Text, src.Name())
	if err != nil {
		return nil, err
	}
	kind, err := specio.DetectDocument(d)
	if err != nil {
		return nil, err
	}
	if kind == specio.Swagger2 {
		return nil, doc.Errorf(doc.ErrInput, "Input is Swagger 2.0; use /api/v1/modernize or /api/v1/convert")
	}
	return d, nil
}

func findingList(findings []lint.Finding) []any {
	out := []any{}
	for _, f := range findings {
		out = append(out, f.ToMap())
	}
	return out
}

func fixList(fixes []fix.Applied) []any {
	out := []any{}
	for _, f := range fixes {
		out = append(out, doc.M("rule", f.Rule, "message", f.Message, "path", f.Path))
	}
	return out
}

func summary(findings []lint.Finding) *doc.Map {
	c := lint.Summarize(findings)
	return doc.M("error", int64(c["error"]), "warning", int64(c["warning"]), "info", int64(c["info"]))
}

func strings2any(s []string) []any {
	out := make([]any, len(s))
	for i, v := range s {
		out[i] = v
	}
	return out
}

// Health is the /health payload.
func Health() *doc.Map {
	return doc.M("status", "UP", "version", report.Version, "implementation", Implementation)
}

// Rules is the /api/v1/rules payload.
func Rules() *doc.Map {
	rules := append([]lint.Rule(nil), lint.Rules()...)
	sort.Slice(rules, func(i, j int) bool { return rules[i].ID < rules[j].ID })
	out := []any{}
	for _, r := range rules {
		out = append(out, doc.M("id", r.ID, "severity", string(r.Severity), "fixable", r.Fixable,
			"description", r.Description))
	}
	return doc.M("rules", out)
}

// Convert handles POST /api/v1/convert.
func Convert(body *doc.Map) (*doc.Map, error) {
	src, err := source(body)
	if err != nil {
		return nil, err
	}
	res, err := convert.ConvertSource(src, false)
	if err != nil {
		return nil, err
	}
	return doc.M("sourceKind", string(res.SourceKind), "openapi", res.Document,
		"yaml", specio.MustDump(res.Document, "yaml"), "warnings", strings2any(res.Warnings)), nil
}

// Lint handles POST /api/v1/lint.
func Lint(body *doc.Map) (*doc.Map, error) {
	src, err := source(body)
	if err != nil {
		return nil, err
	}
	d, err := requireOAS3(src)
	if err != nil {
		return nil, err
	}
	cfg, err := config(body)
	if err != nil {
		return nil, err
	}
	findings := lint.Lint(d, cfg, specio.LineIndex(src.Text))
	return doc.M("summary", summary(findings), "findings", findingList(findings)), nil
}

// Fix handles POST /api/v1/fix.
func Fix(body *doc.Map) (*doc.Map, error) {
	src, err := source(body)
	if err != nil {
		return nil, err
	}
	d, err := requireOAS3(src)
	if err != nil {
		return nil, err
	}
	cfg, err := config(body)
	if err != nil {
		return nil, err
	}
	fixed, applied := fix.Apply(d, cfg)
	return doc.M("openapi", fixed, "yaml", specio.MustDump(fixed, "yaml"), "fixes", fixList(applied)), nil
}

// Modernize handles POST /api/v1/modernize.
func Modernize(body *doc.Map) (*doc.Map, error) {
	src, err := source(body)
	if err != nil {
		return nil, err
	}
	doFix := true
	if body.Has("fix") {
		b, ok := body.Get("fix").(bool)
		if !ok {
			return nil, doc.Errorf(doc.ErrInput, "'fix' must be a boolean")
		}
		doFix = b
	}
	cfg, err := config(body)
	if err != nil {
		return nil, err
	}
	res, err := pipeline.Modernize(src, cfg, doFix, "yaml", false)
	if err != nil {
		return nil, err
	}
	name := src.Name()
	stem := name
	if i := strings.LastIndex(name, "."); i >= 0 {
		stem = name[:i]
	}
	md := report.Markdown(name, res.SourceKind.Label(), stem+".openapi.yaml", res.Warnings, res.Fixes, res.Findings)
	return doc.M(
		"sourceKind", string(res.SourceKind),
		"openapi", res.Document,
		"yaml", res.Text,
		"warnings", strings2any(res.Warnings),
		"fixes", fixList(res.Fixes),
		"summary", summary(res.Findings),
		"findings", findingList(res.Findings),
		"report", md), nil
}
