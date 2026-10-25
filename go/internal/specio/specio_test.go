package specio

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

func td(rel string) string { return filepath.Join("..", "..", "..", "testdata", rel) }

func TestParse(t *testing.T) {
	m, err := ParseStructured("openapi: 3.1.0\ninfo: {version: 2024-01-01}\nresponses: {200: ok}\nf: 1.5\nb: true\nn: null\nbig: 99999999999999999999\nl: [1, x]\n", "t")
	if err != nil {
		t.Fatal(err)
	}
	if doc.AsMap(m.Get("info")).Get("version") != "2024-01-01" || !doc.AsMap(m.Get("responses")).Has("200") {
		t.Error(m)
	}
	if m.Get("f") != 1.5 || m.Get("b") != true || m.Get("n") != nil || doc.Str(m.Get("big")) != "99999999999999999999" {
		t.Error("scalars")
	}
	if j, err := ParseStructured(`{"swagger": "2.0"}`, "t"); err != nil || j.Get("swagger") != "2.0" {
		t.Error(err)
	}
	for _, bad := range []string{"- a\n- b\n", "just a string", "{bad json", "a: !!python/object x\n", "", "a: !!float abc\n", "a: !!bool maybe\n"} {
		if _, err := ParseStructured(bad, "t"); !doc.IsKind(err, doc.ErrInput) {
			t.Errorf("%q: %v", bad, err)
		}
	}
	deep := strings.Repeat("[", 250) + strings.Repeat("]", 250)
	if _, err := ParseStructured("a: "+deep, "t"); err == nil {
		t.Error("deep nesting accepted")
	}
}

func TestAliasBomb(t *testing.T) {
	src, err := ReadSource(td("security/yaml-aliases.yaml"), DefaultMaxBytes)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseStructured(src.Text, "t"); !doc.IsKind(err, doc.ErrSecurity) {
		t.Fatal(err)
	}
	if _, err := ParseStructured("a: &x 1\nb: *x\n", "t"); err != nil {
		t.Error(err)
	}
}

func TestReadSource(t *testing.T) {
	dir := t.TempDir()
	big := filepath.Join(dir, "big.yaml")
	_ = os.WriteFile(big, []byte("a: "+strings.Repeat("x", 100)), 0o600)
	if _, err := ReadSource(big, 10); !doc.IsKind(err, doc.ErrSecurity) {
		t.Error(err)
	}
	if _, err := ReadSource(filepath.Join(dir, "nope"), 10); !doc.IsKind(err, doc.ErrInput) {
		t.Error(err)
	}
	bin := filepath.Join(dir, "b.yaml")
	_ = os.WriteFile(bin, []byte{0xff, 0xfe, 0, 'x'}, 0o600)
	if _, err := ReadSource(bin, DefaultMaxBytes); !doc.IsKind(err, doc.ErrInput) {
		t.Error(err)
	}
	bom := filepath.Join(dir, "bom.yaml")
	_ = os.WriteFile(bom, []byte("\xef\xbb\xbfa: 1\n"), 0o600)
	if s, err := ReadSource(bom, DefaultMaxBytes); err != nil || s.Text != "a: 1\n" || s.Name() != "bom.yaml" {
		t.Error(s, err)
	}
}

func TestDump(t *testing.T) {
	shared := doc.M("type", "string")
	m := doc.M("openapi", "3.1.0", "info", doc.M("description", "l1\nl2"), "a", shared, "b", shared,
		"n", int64(1), "i", 2, "f", 60.0, "g", 0.5, "t", true, "z", nil, "code", "200", "x", struct{}{})
	y, err := Dump(m, "yaml")
	if err != nil || !strings.Contains(y, "|") || strings.Contains(y, "&") || !strings.Contains(y, "\"200\"") {
		t.Fatal(y, err)
	}
	back, err := ParseStructured(y, "t")
	if err != nil || back.Get("f") != 60.0 || back.Get("code") != "200" {
		t.Error(back, err)
	}
	j, err := Dump(doc.M("x", "<a>"), "json")
	if err != nil || !strings.Contains(j, `"x": "<a>"`) {
		t.Error(j)
	}
	if _, err := Dump(m, "xml"); err == nil {
		t.Error("xml accepted")
	}
	defer func() {
		if recover() == nil {
			t.Error("MustDump did not panic")
		}
	}()
	MustDump(m, "xml")
}

func TestFormatAndLines(t *testing.T) {
	if FormatForPath("a.JSON") != "json" || FormatForPath("a.yml") != "yaml" || FormatForPath("") != "yaml" {
		t.Error("format")
	}
	idx := LineIndex("openapi: 3.1.0\npaths:\n  /a:\n    get: {}\nlist:\n  - x\n")
	if idx[PathKey([]string{"paths", "/a", "get"})] != 4 || idx[PathKey([]string{"list", "0"})] != 6 {
		t.Error(idx)
	}
	if len(LineIndex("")) != 0 || len(LineIndex("a: [")) != 0 {
		t.Error("bad input index")
	}
}

func TestDetect(t *testing.T) {
	for v, want := range map[string]SpecKind{"swagger: '2.0'": Swagger2, "openapi: 3.0.3": OpenAPI30, "openapi: 3.1.0": OpenAPI31} {
		m, _ := ParseStructured(v, "t")
		if k, err := DetectDocument(m); err != nil || k != want || k.Label() == "" {
			t.Error(v, k, err)
		}
	}
	for _, v := range []string{"swagger: '1.2'", "openapi: '4.0'", "title: x"} {
		m, _ := ParseStructured(v, "t")
		if _, err := DetectDocument(m); !doc.IsKind(err, doc.ErrUnsupported) {
			t.Error(v, err)
		}
	}
	if k, err := DetectXML(`<wsdl:definitions xmlns:wsdl="x"/>`); err != nil || k != WSDL11 {
		t.Error(err)
	}
	if _, err := DetectXML(`<description xmlns="http://www.w3.org/ns/wsdl"/>`); err == nil {
		t.Error("wsdl 2.0")
	}
	if _, err := DetectXML(`<root/>`); err == nil {
		t.Error("root")
	}
	if !LooksLikeXML("  <a/>") || LooksLikeXML("a: 1") || WSDL11.Label() != "WSDL 1.1" {
		t.Error("LooksLikeXML")
	}
}
