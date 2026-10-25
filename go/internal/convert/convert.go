package convert

import (
	"path/filepath"

	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// ConvertSource detects the input format and converts it to OpenAPI 3.1.
func ConvertSource(src specio.SourceFile, allowLocalImports bool) (Result, error) {
	if specio.LooksLikeXML(src.Text) {
		if _, err := specio.DetectXML(src.Text); err != nil {
			return Result{}, err
		}
		base, _ := filepath.Abs(filepath.Dir(src.Path))
		return NewWSDLConverter(base, allowLocalImports).Convert(src.Text)
	}
	d, err := specio.ParseStructured(src.Text, src.Name())
	if err != nil {
		return Result{}, err
	}
	kind, err := specio.DetectDocument(d)
	if err != nil {
		return Result{}, err
	}
	switch kind {
	case specio.Swagger2:
		return NewSwagger2Converter(d).Convert(), nil
	case specio.OpenAPI30:
		return UpgradeOpenAPI30(d), nil
	}
	return Result{Document: d, SourceKind: specio.OpenAPI31, Warnings: []string{}}, nil
}
