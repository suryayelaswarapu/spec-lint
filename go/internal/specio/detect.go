package specio

import (
	"regexp"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

// SpecKind identifies an input format.
type SpecKind string

const (
	Swagger2  SpecKind = "swagger-2.0"
	OpenAPI30 SpecKind = "openapi-3.0"
	OpenAPI31 SpecKind = "openapi-3.1"
	WSDL11    SpecKind = "wsdl-1.1"
	wsdl20NS           = "http://www.w3.org/ns/wsdl"
)

// Label is the human-readable name.
func (k SpecKind) Label() string {
	return map[SpecKind]string{
		Swagger2: "Swagger 2.0", OpenAPI30: "OpenAPI 3.0", OpenAPI31: "OpenAPI 3.1", WSDL11: "WSDL 1.1",
	}[k]
}

var rootTag = regexp.MustCompile(`(?s)<(?:[\w.-]+:)?(definitions|description)\b[^>]*>`)

// LooksLikeXML reports whether text starts with "<".
func LooksLikeXML(text string) bool {
	return strings.HasPrefix(strings.TrimLeft(text, " \t\r\n"), "<")
}

// DetectXML performs a cheap, parser-free check of the XML root element.
func DetectXML(text string) (SpecKind, error) {
	m := rootTag.FindStringSubmatch(text)
	if m == nil {
		return "", doc.Errorf(doc.ErrUnsupported, "XML input is not a WSDL document (no <definitions> root)")
	}
	if m[1] == "description" || strings.Contains(m[0], wsdl20NS) {
		return "", doc.Errorf(doc.ErrUnsupported, "WSDL 2.0 is not supported yet; only WSDL 1.1")
	}
	return WSDL11, nil
}

// DetectDocument classifies a parsed JSON/YAML document.
func DetectDocument(d *doc.Map) (SpecKind, error) {
	if strings.HasPrefix(doc.Str(d.Get("swagger")), "2") {
		return Swagger2, nil
	}
	version := doc.Str(d.Get("openapi"))
	switch {
	case strings.HasPrefix(version, "3.0"):
		return OpenAPI30, nil
	case strings.HasPrefix(version, "3.1"):
		return OpenAPI31, nil
	case d.Has("swagger") || d.Has("openapi"):
		return "", doc.Errorf(doc.ErrUnsupported, "Unsupported spec version: swagger=%v openapi=%s",
			d.Get("swagger"), version)
	}
	return "", doc.Errorf(doc.ErrUnsupported, "Not an OpenAPI/Swagger document (no 'openapi' or 'swagger' key)")
}
