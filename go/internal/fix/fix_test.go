package fix

import (
	"reflect"
	"testing"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

const messy = `
openapi: 3.1.0
components:
  schemas:
    old_name: {type: object, nullable: true}
    Keep: {$ref: '#/components/schemas/old_name/properties'}
info: {title: x, version: 1.0.0}
paths:
  /v1/Stations/{id}:
    get:
      responses:
        '200':
          description: ok
          content:
            application/json: {schema: {type: object}}
            application/xml: {schema: {type: object}}
        '400': {description: bad}
        '401': {$ref: '#/components/responses/X'}
    post:
      operationId: Create_Station
      requestBody:
        content:
          application/json: {schema: {properties: {a: {}}}}
          text/plain: {schema: {type: string}}
          application/x: {schema: {$ref: '#/components/schemas/old_name'}}
          bad: x
      responses: {'201': {description: c}}
    put: {operationId: create_station, responses: {}}
  /{only}:
    delete:
      responses: {'404': {description: nf, content: {a/b: {}}}}
`

func parse(t *testing.T, s string) *Map {
	t.Helper()
	m, err := specio.ParseStructured(s, "t")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func get(v any, keys ...string) any {
	for _, k := range keys {
		v = AsMap(v).Get(k)
	}
	return v
}

func TestApply(t *testing.T) {
	fixed, applied := Apply(parse(t, messy), nil)
	rules := map[string]bool{}
	for _, a := range applied {
		rules[a.Rule] = true
	}
	for _, r := range []string{"no-legacy-nullable", "operation-id-required", "operation-id-camel-case",
		"operation-tags", "operation-tag-defined", "schema-name-pascal-case", "no-inline-object-schemas",
		"error-response-schema", "operation-error-response"} {
		if !rules[r] {
			t.Error("missing fix", r)
		}
	}
	if k := fixed.Keys(); k[0] != "openapi" || k[1] != "info" || k[2] != "tags" || k[3] != "paths" {
		t.Error(k)
	}
	item := get(fixed, "paths", "/v1/Stations/{id}")
	if get(item, "get", "operationId") != "getV1StationsById" || get(item, "put", "operationId") != "createStation" ||
		get(item, "post", "operationId") != "createStation2" ||
		!reflect.DeepEqual(get(item, "get", "tags"), []any{"stations"}) ||
		!reflect.DeepEqual(get(fixed, "paths", "/{only}", "delete", "tags"), []any{"default"}) {
		t.Error(specio.MustDump(fixed, "yaml"))
	}
	schemas := AsMap(get(fixed, "components", "schemas"))
	if !schemas.Has("OldName") || schemas.Has("old_name") ||
		get(schemas, "Keep", "$ref") != "#/components/schemas/OldName/properties" {
		t.Error(schemas.Keys())
	}
	content := get(item, "get", "responses", "200", "content")
	if !reflect.DeepEqual(ToPlain(get(content, "application/json", "schema")), ToPlain(get(content, "application/xml", "schema"))) {
		t.Error("dedupe")
	}
	if !AsMap(get(fixed, "paths", "/{only}", "delete", "responses", "404", "content")).Has(ProblemMedia) {
		t.Error("problem body")
	}
	var leftovers []string
	for _, f := range lint.Lint(fixed, nil, nil) {
		if rules[f.Rule] {
			leftovers = append(leftovers, f.Rule+"@"+f.Path[len(f.Path)-1])
		}
	}
	if !reflect.DeepEqual(leftovers, []string{"error-response-schema@401"}) {
		t.Error(leftovers)
	}
}

func TestDisabledAndClean(t *testing.T) {
	cfg := lint.DefaultConfig()
	for _, r := range lint.Rules() {
		cfg.Overrides[r.ID] = lint.SevOff
	}
	fixed, applied := Apply(parse(t, messy), cfg)
	if len(applied) != 0 || !AsMap(get(fixed, "components", "schemas")).Has("old_name") {
		t.Error(applied)
	}
	empty, _ := Apply(M("openapi", "3.1.0", "paths", NewMap()), nil)
	if empty.Has("tags") {
		t.Error("empty tags kept")
	}
}
