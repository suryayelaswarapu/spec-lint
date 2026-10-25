// Package app holds the CLI and the cross-language conformance builder.
package app

import (
	"sort"

	"github.com/suryayelaswarapu/spec-lint/go/internal/convert"
	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// ValidatorRule is excluded from conformance: each language uses its own validator.
const ValidatorRule = "oas3-valid-schema"

// BuildCase builds a cross-language conformance record (see testdata/conformance).
func BuildCase(testdata, rel string) (*doc.Map, error) {
	src, err := specio.ReadSource(testdata+"/"+rel, specio.DefaultMaxBytes)
	if err != nil {
		return nil, err
	}
	conv, err := convert.ConvertSource(src, true)
	if err != nil {
		return nil, err
	}
	fixed, applied := fix.Apply(conv.Document, nil)
	fixes := []string{}
	for _, a := range applied {
		fixes = append(fixes, a.Rule+"|"+a.Path)
	}
	sort.Strings(fixes)
	findings := []string{}
	for _, f := range lint.Lint(fixed, nil, nil) {
		if f.Rule != ValidatorRule {
			findings = append(findings, f.Rule+"|"+string(f.Severity)+"|"+f.Pointer())
		}
	}
	sort.Strings(findings)
	toAny := func(s []string) []any {
		out := make([]any, len(s))
		for i, v := range s {
			out[i] = v
		}
		return out
	}
	return doc.M("input", rel, "sourceKind", string(conv.SourceKind),
		"warnings", int64(len(conv.Warnings)), "converted", conv.Document, "modernized", fixed,
		"fixes", toAny(fixes), "findings", toAny(findings)), nil
}
