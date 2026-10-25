package lint

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

// The official OpenAPI JSON Schemas (copies of /schemas/openapi, kept identical by a test).
//
//go:embed schemas/oas-3.0.json schemas/oas-3.1.json
var schemaFS embed.FS

const maxValidatorErrors = 20

var (
	schemaOnce sync.Once
	schema30   *jsonschema.Schema
	schema31   *jsonschema.Schema
	schemaErr  error
)

func compile(file, id string) (*jsonschema.Schema, error) {
	raw, err := schemaFS.ReadFile("schemas/" + file)
	if err != nil {
		return nil, err
	}
	parsed, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	c := jsonschema.NewCompiler()
	if err := c.AddResource(id, parsed); err != nil {
		return nil, err
	}
	return c.Compile(id)
}

func loadSchemas() {
	schema30, schemaErr = compile("oas-3.0.json", "https://spec.openapis.org/oas/3.0/schema/2021-09-28")
	if schemaErr == nil {
		schema31, schemaErr = compile("oas-3.1.json", "https://spec.openapis.org/oas/3.1/schema/2022-10-07")
	}
}

// oasValid implements oas3-valid-schema against the bundled official schemas; nothing is fetched.
func oasValid(d *doc.Map) []Hit {
	for _, r := range Refs(d) {
		if _, ok := ResolvePointer(d, r.Ref); !strings.HasPrefix(r.Ref, "#") || !ok {
			return nil // never validate unvetted references
		}
	}
	schemaOnce.Do(loadSchemas)
	if schemaErr != nil {
		return []Hit{{"Invalid OpenAPI: " + schemaErr.Error(), []string{}}}
	}
	sch := schema31
	if strings.HasPrefix(doc.Str(d.Get("openapi")), "3.0") {
		sch = schema30
	}
	raw, err := json.Marshal(d)
	if err != nil {
		return []Hit{{"Invalid OpenAPI: " + err.Error(), []string{}}}
	}
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return []Hit{{"Invalid OpenAPI: " + err.Error(), []string{}}}
	}
	verr, ok := sch.Validate(inst).(*jsonschema.ValidationError)
	if !ok || verr == nil {
		return nil
	}
	var out []Hit
	for _, unit := range verr.BasicOutput().Errors {
		if unit.Error == nil {
			continue
		}
		msg, _ := json.Marshal(unit.Error)
		var text string
		_ = json.Unmarshal(msg, &text)
		out = append(out, Hit{fmt.Sprintf("Invalid OpenAPI: %s", text), splitPointer(unit.InstanceLocation)})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Message < out[j].Message })
	if len(out) > maxValidatorErrors {
		out = out[:maxValidatorErrors]
	}
	return out
}

func splitPointer(ptr string) []string {
	out := []string{}
	if ptr == "" || ptr == "/" {
		return out
	}
	for _, p := range strings.Split(strings.TrimPrefix(ptr, "/"), "/") {
		out = append(out, strings.ReplaceAll(strings.ReplaceAll(p, "~1", "/"), "~0", "~"))
	}
	return out
}
