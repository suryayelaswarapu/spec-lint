// Package fix applies safe auto-fixes that never change the wire contract: operationIds, tags,
// component names, schema extraction and documented error bodies. Paths, parameter names and
// property names are never renamed.
package fix

import (
	"reflect"
	"regexp"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/convert"
	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
)

// ProblemMedia is the RFC 9457 media type.
const ProblemMedia = "application/problem+json"

var (
	topLevelOrder = []string{"openapi", "info", "jsonSchemaDialect", "servers", "security", "tags",
		"externalDocs", "paths", "webhooks", "components"}
	versionSeg = regexp.MustCompile(`^v\d+$`)
)

func problemSchema() *Map {
	return M(
		"type", "object",
		"description", "Error body following RFC 9457 (Problem Details for HTTP APIs).",
		"properties", M(
			"type", M("type", "string", "format", "uri-reference"),
			"title", M("type", "string"),
			"status", M("type", "integer", "minimum", int64(100), "maximum", int64(599)),
			"detail", M("type", "string"),
			"instance", M("type", "string", "format", "uri-reference")))
}

// Applied describes one applied fix.
type Applied struct{ Rule, Message, Path string }

type fixer struct {
	doc     *Map
	cfg     *lint.Config
	applied []Applied
}

// Apply returns a fixed copy of d and the fixes applied.
func Apply(d *Map, cfg *lint.Config) (*Map, []Applied) {
	if cfg == nil {
		cfg = lint.DefaultConfig()
	}
	f := &fixer{doc: CopyMap(d), cfg: cfg, applied: []Applied{}}
	f.legacyNullable()
	f.operationIDs()
	f.tags()
	f.schemaNames()
	f.inlineSchemas()
	f.errorResponses()
	f.orderKeys()
	return f.doc, f.applied
}

func (f *fixer) log(rule, msg string, path ...string) {
	f.applied = append(f.applied, Applied{rule, msg, lint.Pointer(path)})
}

func (f *fixer) legacyNullable() {
	if !f.cfg.Enabled("no-legacy-nullable") || len(lint.NoLegacyNullable(f.doc)) == 0 {
		return
	}
	version := f.doc.Get("openapi")
	f.doc = convert.UpgradeOpenAPI30(f.doc).Document
	f.doc.Set("openapi", version)
	f.log("no-legacy-nullable", "Rewrote nullable/x-nullable as type: [T, 'null']")
}

func (f *fixer) operationIDs() {
	ops := lint.Operations(f.doc)
	taken := map[string]bool{}
	for _, o := range ops {
		if Truthy(o.Op.Get("operationId")) {
			taken[Str(o.Op.Get("operationId"))] = true
		}
	}
	if f.cfg.Enabled("operation-id-required") {
		for _, o := range ops {
			if Truthy(o.Op.Get("operationId")) {
				continue
			}
			parts := []string{o.Method}
			for _, seg := range strings.Split(o.Path, "/") {
				if strings.HasPrefix(seg, "{") {
					parts = append(parts, "by", strings.Trim(seg, "{}"))
				} else if seg != "" {
					parts = append(parts, seg)
				}
			}
			id := Unique(ToCamel(strings.Join(parts, "-")), InSet(taken))
			taken[id] = true
			o.Op.Set("operationId", id)
			f.log("operation-id-required", "Added operationId '"+id+"' to "+o.Label(),
				"paths", o.Path, o.Method)
		}
	}
	if f.cfg.Enabled("operation-id-camel-case") {
		for _, o := range ops {
			old := Str(o.Op.Get("operationId"))
			if old == "" || CamelCase.MatchString(old) {
				continue
			}
			delete(taken, old)
			id := Unique(ToCamel(old), InSet(taken))
			taken[id] = true
			o.Op.Set("operationId", id)
			f.log("operation-id-camel-case", "Renamed operationId '"+old+"' -> '"+id+"'",
				"paths", o.Path, o.Method, "operationId")
		}
	}
}

func (f *fixer) tags() {
	if f.cfg.Enabled("operation-tags") {
		for _, o := range lint.Operations(f.doc) {
			if Truthy(o.Op.Get("tags")) {
				continue
			}
			tag := "default"
			for _, s := range strings.Split(o.Path, "/") {
				if s != "" && !strings.HasPrefix(s, "{") && !versionSeg.MatchString(s) {
					tag = ToKebab(s)
					break
				}
			}
			o.Op.Set("tags", []any{tag})
			f.log("operation-tags", "Tagged "+o.Label()+" as '"+tag+"'", "paths", o.Path, o.Method)
		}
	}
	if f.cfg.Enabled("operation-tag-defined") {
		declared := AsList(f.doc.Get("tags"))
		names := map[any]bool{}
		for _, t := range declared {
			if m := AsMap(t); m != nil {
				names[m.Get("name")] = true
			}
		}
		for _, o := range lint.Operations(f.doc) {
			for _, tag := range AsList(o.Op.Get("tags")) {
				if !names[tag] {
					names[tag] = true
					declared = append(declared, M("name", tag))
					f.log("operation-tag-defined", "Declared tag '"+Str(tag)+"'", "tags")
				}
			}
		}
		if len(declared) == 0 {
			f.doc.Delete("tags")
		} else {
			f.doc.Set("tags", declared)
		}
	}
}

func (f *fixer) schemaNames() {
	if !f.cfg.Enabled("schema-name-pascal-case") {
		return
	}
	schemas := AsMap(AsMap(f.doc.Get("components")).Get("schemas"))
	renames := NewMap()
	taken := map[string]bool{}
	for _, k := range schemas.Keys() {
		taken[k] = true
	}
	for _, name := range schemas.Keys() {
		if PascalCase.MatchString(name) {
			continue
		}
		delete(taken, name)
		id := Unique(ToPascal(name), InSet(taken))
		taken[id] = true
		renames.Set(name, id)
	}
	if renames.Len() == 0 {
		return
	}
	renamed := NewMap()
	schemas.Each(func(k string, v any) {
		if renames.Has(k) {
			k = Str(renames.Get(k))
		}
		renamed.Set(k, v)
	})
	AsMap(f.doc.Get("components")).Set("schemas", renamed)
	rewrite(f.doc, renames)
	renames.Each(func(old string, id any) {
		f.log("schema-name-pascal-case", "Renamed schema '"+old+"' -> '"+Str(id)+"'",
			"components", "schemas", Str(id))
	})
}

func rewrite(node any, renames *Map) {
	const prefix = "#/components/schemas/"
	switch x := node.(type) {
	case *Map:
		if ref, ok := x.Get("$ref").(string); ok && strings.HasPrefix(ref, prefix) {
			rest := ref[len(prefix):]
			head, tail := rest, ""
			if i := strings.Index(rest, "/"); i >= 0 {
				head, tail = rest[:i], rest[i:]
			}
			if renames.Has(head) {
				x.Set("$ref", prefix+Str(renames.Get(head))+tail)
			}
		}
		x.Each(func(_ string, v any) { rewrite(v, renames) })
	case []any:
		for _, e := range x {
			rewrite(e, renames)
		}
	}
}

type body struct {
	suffix string
	holder *Map
}

func (f *fixer) inlineSchemas() {
	if !f.cfg.Enabled("no-inline-object-schemas") {
		return
	}
	for _, o := range lint.Operations(f.doc) {
		stemSrc := o.Method + "-" + o.Path
		if Truthy(o.Op.Get("operationId")) {
			stemSrc = Str(o.Op.Get("operationId"))
		}
		stem := ToPascal(stemSrc)
		var bodies []body
		if rb := AsMap(o.Op.Get("requestBody")); rb != nil {
			bodies = append(bodies, body{"Request", rb})
		}
		AsMap(o.Op.Get("responses")).Each(func(code string, resp any) {
			if r := AsMap(resp); r != nil {
				suffix := code + "Response"
				if code == "200" || code == "201" {
					suffix = "Response"
				}
				bodies = append(bodies, body{suffix, r})
			}
		})
		for _, b := range bodies {
			type extracted struct {
				schema *Map
				name   string
			}
			var done []extracted
			AsMap(b.holder.Get("content")).Each(func(media string, raw any) {
				holder := AsMap(raw)
				schema := AsMap(holder.Get("schema"))
				if schema == nil || schema.Has("$ref") ||
					(schema.Get("type") != "object" && !schema.Has("properties")) {
					return
				}
				name := ""
				for _, e := range done {
					if reflect.DeepEqual(ToPlain(e.schema), ToPlain(schema)) {
						name = e.name
					}
				}
				if name == "" {
					schemas := Child(Child(f.doc, "components"), "schemas")
					name = Unique(stem+b.suffix, InMap(schemas))
					schemas.Set(name, schema)
					done = append(done, extracted{schema, name})
					f.log("no-inline-object-schemas", "Extracted "+o.Label()+" "+media+
						" schema to '"+name+"'", "components", "schemas", name)
				}
				holder.Set("schema", M("$ref", "#/components/schemas/"+name))
			})
		}
	}
}

func (f *fixer) ensureProblem() {
	comps := Child(f.doc, "components")
	Child(comps, "schemas").SetDefault("Problem", problemSchema())
	Child(comps, "responses").SetDefault("Problem", M(
		"description", "Error (RFC 9457 Problem Details)",
		"content", M(ProblemMedia, M("schema", M("$ref", "#/components/schemas/Problem")))))
}

func (f *fixer) errorResponses() {
	if f.cfg.Enabled("error-response-schema") {
		for _, o := range lint.Operations(f.doc) {
			AsMap(o.Op.Get("responses")).Each(func(code string, raw any) {
				resp := AsMap(raw)
				if !lint.IsErrorCode(code) || resp == nil || resp.Has("$ref") ||
					lint.HasSchema(resp.Get("content")) {
					return
				}
				content := AsMap(resp.Get("content"))
				if content == nil {
					content = NewMap()
				}
				f.ensureProblem()
				content.Set(ProblemMedia, M("schema", M("$ref", "#/components/schemas/Problem")))
				resp.Set("content", content)
				f.log("error-response-schema", "Added Problem body to "+o.Label()+" response "+code,
					"paths", o.Path, o.Method, "responses", code)
			})
		}
	}
	if f.cfg.Enabled("operation-error-response") {
		for _, o := range lint.Operations(f.doc) {
			responses := Child(o.Op, "responses")
			hasError := false
			for _, c := range responses.Keys() {
				hasError = hasError || lint.IsErrorCode(c)
			}
			if !hasError {
				f.ensureProblem()
				responses.Set("default", M("$ref", "#/components/responses/Problem"))
				f.log("operation-error-response", "Added default Problem response to "+o.Label(),
					"paths", o.Path, o.Method, "responses", "default")
			}
		}
	}
}

func (f *fixer) orderKeys() {
	ordered := NewMap()
	for _, k := range topLevelOrder {
		if f.doc.Has(k) {
			ordered.Set(k, f.doc.Get(k))
		}
	}
	f.doc.Each(func(k string, v any) { ordered.SetDefault(k, v) })
	f.doc = ordered
}
