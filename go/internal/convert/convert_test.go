package convert

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

func td(rel string) string { return filepath.Join("..", "..", "..", "testdata", rel) }

func yml(t *testing.T, text string) *Map {
	t.Helper()
	m, err := specio.ParseStructured(text, "test")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

// at walks maps and lists by key/index.
func at(v any, keys ...string) any {
	for _, k := range keys {
		if l, ok := v.([]any); ok {
			i := 0
			for _, c := range k {
				i = i*10 + int(c-'0')
			}
			if i >= len(l) {
				return nil
			}
			v = l[i]
		} else {
			v = AsMap(v).Get(k)
		}
	}
	return v
}

func eq(a, b any) bool { return reflect.DeepEqual(ToPlain(a), ToPlain(b)) }

func TestSwaggerServersAndSecurity(t *testing.T) {
	r := NewSwagger2Converter(yml(t, `
swagger: '2.0'
info: {title: T, version: '1'}
paths: {}
securityDefinitions:
  basic: {type: basic, description: d}
  key: {type: apiKey, in: query, name: k}
  pw: {type: oauth2, flow: password, tokenUrl: 'https://t', scopes: {}}
  app: {type: oauth2, flow: application, tokenUrl: 'https://t'}
  code: {type: oauth2, flow: accessCode, authorizationUrl: 'https://a', tokenUrl: 'https://t'}
  bad: {type: oauth2, flow: weird}
  odd: {type: mystery}
`)).Convert()
	if at(r.Document, "servers", "0", "url") != "/" || len(r.Warnings) != 3 {
		t.Fatal(r.Warnings)
	}
	s := at(r.Document, "components", "securitySchemes")
	if at(s, "basic", "scheme") != "basic" || at(s, "basic", "description") != "d" ||
		!AsMap(at(s, "pw", "flows")).Has("password") || !AsMap(at(s, "app", "flows")).Has("clientCredentials") ||
		!AsMap(at(s, "code", "flows")).Has("authorizationCode") || !AsMap(at(s, "bad", "flows")).Has("implicit") ||
		at(s, "odd", "type") != "mystery" {
		t.Error(s)
	}
	h := NewSwagger2Converter(yml(t, "swagger: '2.0'\nhost: h.example.com\npaths: {}\nx-top: 1\n")).Convert()
	if at(h.Document, "servers", "0", "url") != "https://h.example.com" || at(h.Document, "info", "title") != "Untitled API" ||
		!h.Document.Has("x-top") || !strings.Contains(strings.Join(h.Warnings, " "), "schemes") {
		t.Error(h.Document, h.Warnings)
	}
}

func TestSwaggerOperations(t *testing.T) {
	out := NewSwagger2Converter(yml(t, `
swagger: '2.0'
host: h
schemes: [https]
consumes: [application/json, application/xml]
parameters:
  Limit: {name: limit, in: query, type: integer}
  Body: {name: body, in: body, schema: {$ref: '#/definitions/Item'}}
responses:
  NotFound: {description: nf}
definitions:
  Item: {type: object, x-nullable: true}
paths:
  /items:
    parameters: [{$ref: '#/parameters/Limit'}]
    x-path-ext: 1
    post:
      operationId: createItem
      parameters: [{$ref: '#/parameters/Body'}, {$ref: '#/parameters/Body'}, notamap]
      responses:
        '201': {description: ok, schema: {$ref: '#/definitions/Item'}}
        '404': {$ref: '#/responses/NotFound'}
        '500': junk
      schemes: [http]
      x-internal: true
  /a:
    $ref: other.yaml
    post:
      parameters:
        - {name: b, in: body, required: true, description: d, schema: {type: object}}
      responses: {}
  /f:
    parameters: [{name: n, in: formData, type: string}]
    post:
      consumes: [application/x-www-form-urlencoded]
      responses: {}
  /u:
    post:
      parameters:
        - {name: file, in: formData, type: file, required: true, description: up}
      responses:
        '200':
          description: ok
          schema: {type: string}
          examples: {application/json: hi}
          headers: {X-Rate: {type: integer, description: r}, X-Other: {type: string}}
  /q/{id}:
    get:
      parameters:
        - {name: q, in: query, type: array, items: {type: string}, collectionFormat: multi, x-ext: 1}
        - {name: h, in: header, type: array, items: {type: string}, collectionFormat: csv}
        - {name: t, in: header, type: array, items: {type: string}, collectionFormat: tsv}
        - {name: id, in: path, type: string, x-nullable: true}
      responses: {}
  /bad: nope
`)).Convert().Document
	if at(out, "paths", "/items", "post", "requestBody", "$ref") != "#/components/requestBodies/Body" ||
		at(out, "paths", "/items", "post", "responses", "404", "$ref") != "#/components/responses/NotFound" ||
		AsMap(at(out, "paths", "/items", "post", "responses")).Has("500") ||
		!eq(at(out, "components", "schemas", "Item", "type"), []any{"object", "null"}) ||
		at(out, "paths", "/a", "post", "requestBody", "required") != true ||
		!AsMap(at(out, "paths", "/f", "post", "requestBody", "content")).Has("application/x-www-form-urlencoded") ||
		at(out, "paths", "/u", "post", "requestBody", "content", "multipart/form-data", "schema", "properties", "file", "format") != "binary" ||
		at(out, "paths", "/u", "post", "responses", "200", "content", "application/json", "example") != "hi" ||
		AsMap(at(out, "paths")).Has("/bad") {
		t.Fatal(specio.MustDump(out, "yaml"))
	}
	params := AsList(at(out, "paths", "/q/{id}", "get", "parameters"))
	if at(params, "0", "style") != "form" || at(params, "0", "explode") != true ||
		at(params, "1", "style") != "simple" || AsMap(params[2]).Has("style") || at(params, "3", "required") != true {
		t.Error(params)
	}
}

func TestUpgradeSchema(t *testing.T) {
	out := UpgradeSchema(yml(t, `
type: object
discriminator: kind
properties:
  a: {type: integer, minimum: 1, exclusiveMinimum: true}
  b: {type: integer, maximum: 5, exclusiveMaximum: false}
  c: {type: file}
  d: {$ref: '#/x', nullable: true}
  e: {type: string, enum: [x], nullable: true}
  f: {type: [string, 'null'], x-nullable: true}
allOf: [{type: object}]
patternProperties: {'^x': {type: string}}
items: {type: string, example: z}
`))
	if !eq(at(out, "properties", "a"), M("type", "integer", "exclusiveMinimum", 1)) ||
		AsMap(at(out, "properties", "b")).Has("exclusiveMaximum") ||
		at(out, "properties", "c", "format") != "binary" || !AsMap(at(out, "properties", "d")).Has("anyOf") ||
		!eq(at(out, "properties", "e", "enum"), []any{"x", nil}) ||
		!eq(at(out, "properties", "f", "type"), []any{"string", "null"}) ||
		at(out, "discriminator", "propertyName") != "kind" || !eq(at(out, "items", "examples"), []any{"z"}) {
		t.Error(specio.MustDump(AsMap(out), "yaml"))
	}
	if UpgradeSchema("x") != "x" {
		t.Error("scalar")
	}
	if !eq(RewriteRefs([]any{M("$ref", 3)}, nil), []any{M("$ref", 3)}) {
		t.Error("rewrite")
	}
}

func TestUpgradeOpenAPI30(t *testing.T) {
	r := UpgradeOpenAPI30(yml(t, `
openapi: 3.0.3
paths:
  /a:
    parameters: [{name: p, in: query, schema: {type: string, nullable: true}}]
    get:
      parameters: [{name: q, in: query, schema: {type: string, nullable: true}}]
      requestBody: {content: {a/b: {schema: {type: string, nullable: true}}}}
      responses:
        '200':
          content: {a/b: {schema: {type: string, nullable: true}}}
          headers: {X: {schema: {type: string, nullable: true}}}
        '204': junk
  /skip: null
components:
  schemas: {S: {type: string, nullable: true}}
  parameters: {P: {schema: {type: string, nullable: true}}}
  requestBodies: {B: {content: {a/b: {schema: {type: string, nullable: true}}}}}
  responses: {R: {content: {a/b: {schema: {type: string, nullable: true}}}}}
`))
	if r.Document.Get("openapi") != "3.1.0" || strings.Contains(specio.MustDump(r.Document, "json"), "nullable") {
		t.Error(specio.MustDump(r.Document, "yaml"))
	}
}

func TestConvertSourceDispatchAndSecurity(t *testing.T) {
	r, err := ConvertSource(specio.SourceFile{Path: "s.yaml", Text: "openapi: 3.1.0\ninfo: {title: x, version: 1.0.0}\n"}, true)
	if err != nil || r.SourceKind != specio.OpenAPI31 {
		t.Error(err)
	}
	for _, text := range []string{"{bad", "openapi: 9", "<root/>"} {
		if _, err := ConvertSource(specio.SourceFile{Path: "s", Text: text}, true); err == nil {
			t.Error(text)
		}
	}
	for _, name := range []string{"xxe.wsdl", "billion-laughs.wsdl"} {
		src, _ := specio.ReadSource(td("security/"+name), specio.DefaultMaxBytes)
		if _, err := ConvertSource(src, true); !IsKind(err, ErrSecurity) {
			t.Error(name, err)
		}
	}
	src, _ := specio.ReadSource(td("security/path-traversal.wsdl"), specio.DefaultMaxBytes)
	if _, err := ConvertSource(src, true); !IsKind(err, ErrSecurity) || !strings.Contains(err.Error(), "escapes") {
		t.Error(err)
	}
	src, _ = specio.ReadSource(td("security/remote-import.wsdl"), specio.DefaultMaxBytes)
	if r, err := ConvertSource(src, true); err != nil || !strings.Contains(strings.Join(r.Warnings, " "), "not fetched") {
		t.Error(err)
	}
	if _, err := ParseXML("<a><b></a>"); !IsKind(err, ErrInput) {
		t.Error(err)
	}
	if _, err := ParseXML(""); err == nil {
		t.Error("empty xml")
	}
	if _, err := ParseXML(`<?xml version="1.0" encoding="ISO-8859-1"?><a/>`); err == nil {
		t.Error("latin1 accepted")
	}
	if _, err := ParseXML(`<?xml version="1.0" encoding="UTF-8"?><a/>`); err != nil {
		t.Error(err)
	}
}

func TestWeatherFixture(t *testing.T) {
	src, _ := specio.ReadSource(td("weather/weather-service.wsdl"), specio.DefaultMaxBytes)
	r, err := ConvertSource(src, true)
	if err != nil || at(r.Document, "paths", "/get-forecast", "post", "x-soap", "soapVersion") != "1.1" {
		t.Error(err)
	}
}

func TestWritesLocalImports(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "x.xsd"), []byte(`<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>`), 0o600)
	c := NewWSDLConverter(dir, true)
	if err := c.loadExternal("x.xsd"); err != nil || len(c.loaded) != 1 {
		t.Error(err)
	}
}
