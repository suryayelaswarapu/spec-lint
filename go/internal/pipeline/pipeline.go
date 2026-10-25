// Package pipeline runs the modernize pipeline shared by the CLI and the HTTP server.
package pipeline

import (
	"github.com/suryayelaswarapu/spec-lint/go/internal/convert"
	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// ModernizeResult is the output of the modernize pipeline.
type ModernizeResult struct {
	Document   *doc.Map
	Text       string
	SourceKind specio.SpecKind
	Warnings   []string
	Fixes      []fix.Applied
	Findings   []lint.Finding
}

// Modernize runs detect -> convert -> auto-fix -> serialise -> lint.
func Modernize(src specio.SourceFile, cfg *lint.Config, doFix bool, format string,
	allowLocalImports bool) (ModernizeResult, error) {
	if cfg == nil {
		cfg = lint.DefaultConfig()
	}
	conv, err := convert.ConvertSource(src, allowLocalImports)
	if err != nil {
		return ModernizeResult{}, err
	}
	d := conv.Document
	fixes := []fix.Applied{}
	if doFix {
		d, fixes = fix.Apply(d, cfg)
	}
	text, err := specio.Dump(d, format)
	if err != nil {
		return ModernizeResult{}, err
	}
	// Lint what was written, so reported line numbers match the output file.
	written, err := specio.ParseStructured(text, "output")
	if err != nil {
		return ModernizeResult{}, err
	}
	findings := lint.Lint(written, cfg, specio.LineIndex(text))
	return ModernizeResult{d, text, conv.SourceKind, conv.Warnings, fixes, findings}, nil
}
