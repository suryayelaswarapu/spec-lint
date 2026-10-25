package lint

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

const minimal = `
openapi: 3.1.0
info:
  title: Demo
  version: 1.0.0
  description: Demo API
  contact: {name: Team}
  license: {name: MIT, identifier: MIT}
servers: [{url: 'https://api.example.com'}]
security: [{key: []}]
tags: [{name: items}]
paths:
  /items/{itemId}:
    get:
      tags: [items]
      summary: Get item
      operationId: getItem
      parameters:
        - {name: itemId, in: path, required: true, description: Item id, schema: {type: string}}
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: {$ref: '#/components/schemas/Item'}
        '404': {$ref: '#/components/responses/NotFound'}
components:
  schemas:
    Item: {type: object, properties: {itemId: {type: string}}}
    Problem: {type: object, properties: {title: {type: string}}}
  responses:
    NotFound:
      description: Not found
      content:
        application/problem+json:
          schema: {$ref: '#/components/schemas/Problem'}
  securitySchemes:
    key: {type: apiKey, in: header, name: X-Key}
`

// Minimal returns a fresh document that passes every rule.
func Minimal(t *testing.T) *Map {
	t.Helper()
	m, err := specio.ParseStructured(minimal, "minimal")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func op(d *Map) *Map { return AsMap(AsMap(AsMap(d.Get("paths")).Get("/items/{itemId}")).Get("get")) }

func ruleSet(d *Map) map[string]bool {
	out := map[string]bool{}
	for _, f := range Lint(d, nil, nil) {
		out[f.Rule] = true
	}
	return out
}

func TestMinimalIsClean(t *testing.T) {
	if f := Lint(Minimal(t), nil, nil); len(f) != 0 {
		t.Fatal(f)
	}
}

func TestEveryRuleDocumentedAndSchemasInSync(t *testing.T) {
	catalog, err := os.ReadFile("../../../docs/rules.md")
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range Rules() {
		if !strings.Contains(string(catalog), "`"+r.ID+"`") {
			t.Error("undocumented", r.ID)
		}
	}
	for _, f := range []string{"oas-3.0.json", "oas-3.1.json"} {
		a, _ := os.ReadFile(filepath.Join("schemas", f))
		b, _ := os.ReadFile(filepath.Join("..", "..", "..", "schemas", "openapi", f))
		if len(a) == 0 || !bytes.Equal(a, b) {
			t.Errorf("%s differs from /schemas/openapi", f)
		}
	}
}

func TestRulesFire(t *testing.T) {
	cases := []struct {
		rule   string
		change func(d *Map)
	}{
		{"openapi-version-3-1", func(d *Map) { d.Set("openapi", "3.0.3") }},
		{"info-description", func(d *Map) { AsMap(d.Get("info")).Delete("description") }},
		{"info-contact", func(d *Map) { AsMap(d.Get("info")).Delete("contact") }},
		{"info-license", func(d *Map) { AsMap(d.Get("info")).Delete("license") }},
		{"info-version-semver", func(d *Map) { AsMap(d.Get("info")).Set("version", "v1") }},
		{"servers-defined", func(d *Map) { d.Delete("servers") }},
		{"servers-https", func(d *Map) { d.Set("servers", []any{M("url", "http://api.example.com")}) }},
		{"security-defined", func(d *Map) { AsMap(d.Get("components")).Delete("securitySchemes") }},
		{"security-defined", func(d *Map) { d.Delete("security") }},
		{"operation-id-required", func(d *Map) { op(d).Delete("operationId") }},
		{"operation-id-camel-case", func(d *Map) { op(d).Set("operationId", "Get_Item") }},
		{"operation-summary", func(d *Map) { op(d).Delete("summary") }},
		{"operation-tags", func(d *Map) { op(d).Delete("tags") }},
		{"operation-tag-defined", func(d *Map) { d.Delete("tags") }},
		{"operation-success-response", func(d *Map) { AsMap(op(d).Get("responses")).Delete("200") }},
		{"operation-error-response", func(d *Map) { AsMap(op(d).Get("responses")).Delete("404") }},
		{"error-response-schema", func(d *Map) { AsMap(op(d).Get("responses")).Set("500", M("description", "x")) }},
		{"parameter-description", func(d *Map) { AsMap(AsList(op(d).Get("parameters"))[0]).Delete("description") }},
		{"parameter-description", func(d *Map) {
			AsMap(d.Get("components")).Set("parameters", M("P", M("name", "p", "in", "query")))
		}},
		{"path-kebab-case", func(d *Map) { AsMap(d.Get("paths")).Set("/Bad_Path", NewMap()) }},
		{"path-no-trailing-slash", func(d *Map) { AsMap(d.Get("paths")).Set("/items/", NewMap()) }},
		{"path-params-defined", func(d *Map) { op(d).Set("parameters", []any{}) }},
		{"path-params-defined", func(d *Map) {
			op(d).Set("parameters", append(AsList(op(d).Get("parameters")),
				M("name", "extra", "in", "path", "required", true, "schema", NewMap(), "description", "x")))
		}},
		{"schema-name-pascal-case", func(d *Map) { AsMap(AsMap(d.Get("components")).Get("schemas")).Set("lower", NewMap()) }},
		{"property-camel-case", func(d *Map) {
			AsMap(AsMap(AsMap(AsMap(d.Get("components")).Get("schemas")).Get("Item")).Get("properties")).Set("snake_case", NewMap())
		}},
		{"no-inline-object-schemas", func(d *Map) {
			AsMap(AsMap(AsMap(AsMap(op(d).Get("responses")).Get("200")).Get("content")).Get("application/json")).Set("schema", M("type", "object"))
		}},
		{"no-legacy-nullable", func(d *Map) {
			AsMap(AsMap(AsMap(d.Get("components")).Get("schemas")).Get("Item")).Set("nullable", true)
		}},
		{"no-unused-components", func(d *Map) { AsMap(AsMap(d.Get("components")).Get("schemas")).Set("Unused", NewMap()) }},
		{"no-remote-refs", func(d *Map) { AsMap(op(d).Get("responses")).Set("201", M("$ref", "other.yaml#/x")) }},
		{"no-remote-refs", func(d *Map) { AsMap(op(d).Get("responses")).Set("201", M("$ref", "https://x/y#/z")) }},
		{"ref-resolves", func(d *Map) { AsMap(op(d).Get("responses")).Set("201", M("$ref", "#/nope")) }},
		{"ref-resolves", func(d *Map) { AsMap(op(d).Get("responses")).Set("201", M("$ref", "#/tags/9")) }},
		{"oas3-valid-schema", func(d *Map) { AsMap(d.Get("info")).Delete("title") }},
		{"oas3-valid-schema", func(d *Map) { d.Set("openapi", "3.0.3"); AsMap(d.Get("info")).Delete("title") }},
	}
	for _, c := range cases {
		d := Minimal(t)
		c.change(d)
		if !ruleSet(d)[c.rule] {
			t.Errorf("%s did not fire", c.rule)
		}
	}
}

func TestMiscRules(t *testing.T) {
	d := Minimal(t)
	dup := CopyMap(op(d))
	dup.Set("parameters", []any{})
	AsMap(d.Get("paths")).Set("/other", M("get", dup))
	if !ruleSet(d)["operation-id-unique"] {
		t.Error("unique")
	}
	junk, _ := specio.ParseStructured("openapi: 3.1.0\npaths: {/a: junk, /b: {get: junk}}\ninfo: {}\n", "j")
	if !ruleSet(junk)["servers-defined"] {
		t.Error("junk")
	}
	src, _ := specio.ReadSource("../../../testdata/security/remote-ref.yaml", specio.DefaultMaxBytes)
	remote, _ := specio.ParseStructured(src.Text, "r")
	if rs := ruleSet(remote); !rs["no-remote-refs"] || rs["oas3-valid-schema"] {
		t.Error(rs)
	}
	many := Minimal(t)
	paths := NewMap()
	for i := 0; i < 30; i++ {
		paths.Set("/p"+string(rune('a'+i)), M("get", M("responses", "bad")))
	}
	many.Set("paths", paths)
	n := 0
	for _, f := range Lint(many, nil, nil) {
		if f.Rule == "oas3-valid-schema" {
			n++
		}
	}
	if n == 0 || n > 20 {
		t.Error("validator count", n)
	}
	if v, ok := ResolvePointer(M("l", []any{1}), "#/l/0"); !ok || v != 1 {
		t.Error("pointer list")
	}
	if _, ok := ResolvePointer(M("l", "x"), "#/l/0"); ok {
		t.Error("pointer scalar")
	}
	if _, ok := ResolvePointer(NewMap(), "x"); ok {
		t.Error("non-local")
	}
	if len(splitPointer("/")) != 0 || splitPointer("/a~1b/c")[0] != "a/b" {
		t.Error("splitPointer")
	}
}

func TestLinesSummaryAndPointer(t *testing.T) {
	d := Minimal(t)
	AsMap(d.Get("info")).Delete("contact")
	findings := Lint(d, nil, map[string]int{"info": 3})
	if findings[0].Line != 3 || findings[0].ToMap().Get("line") != int64(3) || Summarize(findings)["warning"] != 1 {
		t.Error(findings)
	}
	if Pointer([]string{"paths", "/a~b"}) != "/paths/~1a~0b" || (Finding{}).ToMap().Has("line") {
		t.Error("pointer")
	}
	multi := Minimal(t)
	multi.Delete("servers")
	AsMap(multi.Get("info")).Delete("license")
	AsMap(multi.Get("info")).Delete("contact")
	f := Lint(multi, nil, nil)
	for i := 1; i < len(f); i++ {
		if f[i-1].Rule > f[i].Rule {
			t.Error("not sorted")
		}
	}
}

func TestConfig(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, ".speclint.yaml"), []byte("extends: strict\nfail-on: warning\nrules:\n  info-contact: off\n  info-license: error\n"), 0o600)
	cfg, err := LoadConfig("", dir)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Severity("info-contact") != SevOff || cfg.Severity("info-license") != SevError ||
		cfg.Severity("operation-summary") != SevError || cfg.Severity("parameter-description") != SevWarning ||
		cfg.Severity("servers-defined") != SevError || !cfg.Fails(SevWarning) || cfg.Fails(SevInfo) || !cfg.Enabled("ref-resolves") {
		t.Error(cfg)
	}
	d := Minimal(t)
	AsMap(d.Get("info")).Delete("contact")
	AsMap(d.Get("info")).Delete("license")
	if f := Lint(d, cfg, nil); len(f) != 1 || f[0].Rule != "info-license" {
		t.Error(f)
	}
	if (&Config{FailOn: "never"}).Fails(SevError) {
		t.Error("never")
	}
	def, _ := LoadConfig("", filepath.Join(dir, "nowhere"))
	if def.FailOn != "error" {
		t.Error("default")
	}
	cwd, _ := os.Getwd()
	_ = os.Chdir(dir)
	fromCwd, _ := LoadConfig("", "")
	_ = os.Chdir(cwd)
	if fromCwd.Extends != "strict" {
		t.Error("cwd config")
	}
	for _, bad := range []string{"extends: loose\n", "fail-on: sometimes\n", "rules: [a]\n",
		"rules:\n  not-a-rule: off\n", "rules:\n  info-contact: loud\n", "- not a mapping\n"} {
		p := filepath.Join(dir, "c.yaml")
		_ = os.WriteFile(p, []byte(bad), 0o600)
		if _, err := LoadConfig(p, ""); !IsKind(err, ErrConfig) {
			t.Error(bad, err)
		}
	}
	p := filepath.Join(dir, "bool.yaml")
	_ = os.WriteFile(p, []byte("rules:\n  info-contact: false\n"), 0o600)
	if c, err := LoadConfig(p, ""); err != nil || c.Severity("info-contact") != SevOff {
		t.Error(err)
	}
	if _, ok := ParseSeverity("loud"); ok {
		t.Error("ParseSeverity")
	}
}

func TestUnusedComponentsEscapedAndNestedRefs(t *testing.T) {
	d := Minimal(t)
	schemas := AsMap(AsMap(d.Get("components")).Get("schemas"))
	schemas.Set("a/b", NewMap())
	ref := NewMap()
	ref.Set("$ref", "#/components/schemas/a~1b/properties")
	AsMap(AsMap(schemas.Get("Item")).Get("properties")).Set("x", ref)
	if ruleSet(d)["no-unused-components"] {
		t.Fatal("escaped/nested ref should count as a use")
	}
	orphan := NewMap()
	orphan.Set("$ref", "#/components")
	schemas.Set("Orphan", orphan)
	var paths []string
	for _, f := range Lint(d, nil, nil) {
		if f.Rule == "no-unused-components" {
			paths = append(paths, strings.Join(f.Path, "."))
		}
	}
	if len(paths) != 1 || paths[0] != "components.schemas.Orphan" {
		t.Fatalf("got %v", paths)
	}
}
