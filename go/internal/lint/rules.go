package lint

import (
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
)

var (
	semverRe       = regexp.MustCompile(`^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$`)
	pathSegmentRe  = regexp.MustCompile(`^[a-z0-9]+(?:-[a-z0-9]+)*(?:\.[a-z0-9]+)?$`)
	pathTemplateRe = regexp.MustCompile(`\{([^}/]+)\}`)
	localHosts     = []string{"localhost", "127.0.0.1", "[::1]"}
)

// IsErrorCode reports whether a response code documents an error.
func IsErrorCode(code string) bool {
	return code == "default" || strings.HasPrefix(code, "4") || strings.HasPrefix(code, "5")
}

var registry = []Rule{
	{"no-remote-refs", SevError, "$ref must point inside the document; remote refs are never fetched.", false, noRemoteRefs},
	{"ref-resolves", SevError, "Every local $ref must resolve to an existing node.", false, refResolves},
	{"oas3-valid-schema", SevError, "Document must be valid against the OpenAPI 3.x specification.", false, oasValid},
	{"openapi-version-3-1", SevWarning, "Target OpenAPI 3.1 (full JSON Schema 2020-12 support).", false, openapiVersion},
	{"info-description", SevWarning, "info.description explains what the API is for.", false, infoField("description")},
	{"info-contact", SevWarning, "info.contact tells consumers who owns the API.", false, infoField("contact")},
	{"info-license", SevInfo, "info.license states the terms of use.", false, infoField("license")},
	{"info-version-semver", SevWarning, "info.version should be semantic (MAJOR.MINOR.PATCH).", false, infoVersion},
	{"servers-defined", SevError, "At least one server URL must be declared.", false, serversDefined},
	{"servers-https", SevWarning, "Absolute server URLs must use https (localhost excepted).", false, serversHTTPS},
	{"security-defined", SevWarning, "Operations should be protected by a declared security scheme.", false, securityDefined},
	{"operation-id-required", SevError, "Every operation needs an operationId.", true, opCheck(func(o Op) string {
		if !Truthy(o.Op.Get("operationId")) {
			return o.Label() + " has no operationId"
		}
		return ""
	})},
	{"operation-id-unique", SevError, "operationId values must be unique.", false, operationIDUnique},
	{"operation-id-camel-case", SevWarning, "operationId should be camelCase.", true, operationIDCamel},
	{"operation-summary", SevWarning, "Every operation needs a short summary.", false, opCheck(func(o Op) string {
		if !Truthy(o.Op.Get("summary")) {
			return o.Label() + " has no summary"
		}
		return ""
	})},
	{"operation-tags", SevWarning, "Every operation needs at least one tag.", true, opCheck(func(o Op) string {
		if !Truthy(o.Op.Get("tags")) {
			return o.Label() + " has no tags"
		}
		return ""
	})},
	{"operation-tag-defined", SevWarning, "Tags used by operations are declared at the top level.", true, operationTagDefined},
	{"operation-success-response", SevError, "Every operation defines a 2xx or 3xx response.", false, operationSuccess},
	{"operation-error-response", SevWarning, "Every operation documents its errors (4xx/5xx/default).", true, operationError},
	{"error-response-schema", SevWarning, "Error responses must describe their body with a schema.", true, errorResponseSchema},
	{"parameter-description", SevInfo, "Parameters should be described.", false, parameterDescription},
	{"path-kebab-case", SevWarning, "Static path segments are lower kebab-case.", false, pathKebabCase},
	{"path-no-trailing-slash", SevWarning, "Paths must not end with a slash.", false, pathTrailingSlash},
	{"path-params-defined", SevError, "Path templates and path parameters must match.", false, pathParamsDefined},
	{"schema-name-pascal-case", SevWarning, "components.schemas names are PascalCase.", true, schemaNamePascal},
	{"property-camel-case", SevWarning, "Schema property names are camelCase.", false, propertyCamelCase},
	{"no-inline-object-schemas", SevWarning, "Request/response bodies reference named schemas instead of inline objects.", true, noInlineObjects},
	{"no-legacy-nullable", SevWarning, "Use type: [T, 'null'] instead of nullable / x-nullable.", true, NoLegacyNullable},
	{"no-unused-components", SevWarning, "Every component is referenced somewhere.", false, noUnusedComponents},
}

var byID = func() map[string]Rule {
	m := map[string]Rule{}
	for _, r := range registry {
		m[r.ID] = r
	}
	return m
}()

// Rules returns all rules in registration order.
func Rules() []Rule { return registry }

// RuleByID looks up a rule.
func RuleByID(id string) (Rule, bool) {
	r, ok := byID[id]
	return r, ok
}

func opCheck(fn func(Op) string) func(*Map) []Hit {
	return func(d *Map) []Hit {
		var out []Hit
		for _, o := range Operations(d) {
			if msg := fn(o); msg != "" {
				out = append(out, Hit{msg, []string{"paths", o.Path, o.Method}})
			}
		}
		return out
	}
}

func noRemoteRefs(d *Map) []Hit {
	var out []Hit
	for _, r := range Refs(d) {
		if !strings.HasPrefix(r.Ref, "#") {
			kind := "external file"
			if strings.Contains(r.Ref, "://") {
				kind = "remote URL"
			}
			out = append(out, Hit{fmt.Sprintf("$ref to %s '%s' is not allowed; bundle it into this document",
				kind, r.Ref), r.Path})
		}
	}
	return out
}

func refResolves(d *Map) []Hit {
	var out []Hit
	for _, r := range Refs(d) {
		if strings.HasPrefix(r.Ref, "#") {
			if _, ok := ResolvePointer(d, r.Ref); !ok {
				out = append(out, Hit{"$ref '" + r.Ref + "' does not resolve", r.Path})
			}
		}
	}
	return out
}

func openapiVersion(d *Map) []Hit {
	v := Str(d.Get("openapi"))
	if !strings.HasPrefix(v, "3.1") {
		return []Hit{{"openapi is '" + v + "'; upgrade to 3.1.x", []string{"openapi"}}}
	}
	return nil
}

func infoField(field string) func(*Map) []Hit {
	return func(d *Map) []Hit {
		if !Truthy(AsMap(d.Get("info")).Get(field)) {
			return []Hit{{"info." + field + " is missing", []string{"info"}}}
		}
		return nil
	}
}

func infoVersion(d *Map) []Hit {
	v := Str(AsMap(d.Get("info")).Get("version"))
	if !semverRe.MatchString(v) {
		return []Hit{{"info.version '" + v + "' is not semantic versioning", []string{"info", "version"}}}
	}
	return nil
}

func serversDefined(d *Map) []Hit {
	if len(AsList(d.Get("servers"))) == 0 {
		return []Hit{{"No servers defined", []string{}}}
	}
	return nil
}

func serversHTTPS(d *Map) []Hit {
	var out []Hit
	for i, s := range AsList(d.Get("servers")) {
		url := Str(AsMap(s).Get("url"))
		if !strings.HasPrefix(url, "http://") {
			continue
		}
		local := false
		for _, h := range localHosts {
			local = local || strings.HasPrefix(url[7:], h)
		}
		if !local {
			out = append(out, Hit{"Server URL '" + url + "' is not https",
				[]string{"servers", strconv.Itoa(i), "url"}})
		}
	}
	return out
}

func securityDefined(d *Map) []Hit {
	if !Truthy(AsMap(d.Get("components")).Get("securitySchemes")) {
		return []Hit{{"No components.securitySchemes defined", []string{}}}
	}
	if Truthy(d.Get("security")) {
		return nil
	}
	var out []Hit
	for _, o := range Operations(d) {
		if !o.Op.Has("security") {
			out = append(out, Hit{o.Label() + " has no security requirement",
				[]string{"paths", o.Path, o.Method}})
		}
	}
	return out
}

func operationIDUnique(d *Map) []Hit {
	var out []Hit
	seen := map[string]bool{}
	for _, o := range Operations(d) {
		id := o.Op.Get("operationId")
		if !Truthy(id) {
			continue
		}
		if seen[Str(id)] {
			out = append(out, Hit{"Duplicate operationId '" + Str(id) + "'",
				[]string{"paths", o.Path, o.Method, "operationId"}})
		}
		seen[Str(id)] = true
	}
	return out
}

func operationIDCamel(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		id := o.Op.Get("operationId")
		if Truthy(id) && !CamelCase.MatchString(Str(id)) {
			out = append(out, Hit{"operationId '" + Str(id) + "' is not camelCase",
				[]string{"paths", o.Path, o.Method, "operationId"}})
		}
	}
	return out
}

func operationTagDefined(d *Map) []Hit {
	declared := map[any]bool{}
	for _, t := range AsList(d.Get("tags")) {
		if m := AsMap(t); m != nil {
			declared[m.Get("name")] = true
		}
	}
	var out []Hit
	for _, o := range Operations(d) {
		for i, tag := range AsList(o.Op.Get("tags")) {
			if !declared[tag] {
				out = append(out, Hit{"Tag '" + Str(tag) + "' is not declared in top-level tags",
					[]string{"paths", o.Path, o.Method, "tags", strconv.Itoa(i)}})
			}
		}
	}
	return out
}

func anyCode(op *Map, pred func(string) bool) bool {
	for _, c := range AsMap(op.Get("responses")).Keys() {
		if pred(c) {
			return true
		}
	}
	return false
}

func operationSuccess(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		if !anyCode(o.Op, func(c string) bool { return strings.HasPrefix(c, "2") || strings.HasPrefix(c, "3") }) {
			out = append(out, Hit{o.Label() + " has no success response",
				[]string{"paths", o.Path, o.Method, "responses"}})
		}
	}
	return out
}

func operationError(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		if !anyCode(o.Op, IsErrorCode) {
			out = append(out, Hit{o.Label() + " has no error response",
				[]string{"paths", o.Path, o.Method, "responses"}})
		}
	}
	return out
}

// HasSchema reports whether a content map has at least one media type with a schema.
func HasSchema(content any) bool {
	found := false
	AsMap(content).Each(func(_ string, m any) { found = found || AsMap(m).Has("schema") })
	return found
}

func errorResponseSchema(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		AsMap(o.Op.Get("responses")).Each(func(code string, resp any) {
			if !IsErrorCode(code) {
				return
			}
			if !HasSchema(AsMap(Deref(d, resp)).Get("content")) {
				out = append(out, Hit{o.Label() + " response " + code + " has no schema",
					[]string{"paths", o.Path, o.Method, "responses", code}})
			}
		})
	}
	return out
}

func parameterDescription(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		for i, p := range AsList(o.Op.Get("parameters")) {
			m := AsMap(p)
			if m != nil && !m.Has("$ref") && !Truthy(m.Get("description")) {
				out = append(out, Hit{"Parameter '" + Str(m.Get("name")) + "' has no description",
					[]string{"paths", o.Path, o.Method, "parameters", strconv.Itoa(i)}})
			}
		}
	}
	AsMap(AsMap(d.Get("components")).Get("parameters")).Each(func(name string, p any) {
		if m := AsMap(p); m != nil && !Truthy(m.Get("description")) {
			out = append(out, Hit{"Parameter '" + Str(m.Get("name")) + "' has no description",
				[]string{"components", "parameters", name}})
		}
	})
	return out
}

func pathKebabCase(d *Map) []Hit {
	var out []Hit
	for _, path := range AsMap(d.Get("paths")).Keys() {
		for _, seg := range strings.Split(path, "/") {
			if seg != "" && !strings.HasPrefix(seg, "{") && !pathSegmentRe.MatchString(seg) {
				out = append(out, Hit{"Path segment '" + seg + "' in '" + path + "' is not kebab-case",
					[]string{"paths", path}})
				break
			}
		}
	}
	return out
}

func pathTrailingSlash(d *Map) []Hit {
	var out []Hit
	for _, path := range AsMap(d.Get("paths")).Keys() {
		if len(path) > 1 && strings.HasSuffix(path, "/") {
			out = append(out, Hit{"Path '" + path + "' ends with a slash", []string{"paths", path}})
		}
	}
	return out
}

func pathParamsDefined(d *Map) []Hit {
	var out []Hit
	for _, o := range Operations(d) {
		template := map[string]bool{}
		for _, m := range pathTemplateRe.FindAllStringSubmatch(o.Path, -1) {
			template[m[1]] = true
		}
		declared := map[string]bool{}
		params := append(append([]any(nil), AsList(o.Item.Get("parameters"))...), AsList(o.Op.Get("parameters"))...)
		for _, raw := range params {
			if p := AsMap(Deref(d, raw)); p != nil && p.Get("in") == "path" {
				declared[Str(p.Get("name"))] = true
			}
		}
		where := []string{"paths", o.Path, o.Method}
		for _, name := range sortedKeys(template) {
			if !declared[name] {
				out = append(out, Hit{o.Label() + ": path parameter '" + name + "' is not declared", where})
			}
		}
		for _, name := range sortedKeys(declared) {
			if !template[name] {
				out = append(out, Hit{o.Label() + ": parameter '" + name + "' is not in the path", where})
			}
		}
	}
	return out
}

func sortedKeys(m map[string]bool) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func schemaNamePascal(d *Map) []Hit {
	var out []Hit
	for _, name := range AsMap(AsMap(d.Get("components")).Get("schemas")).Keys() {
		if !PascalCase.MatchString(name) {
			out = append(out, Hit{"Schema name '" + name + "' is not PascalCase",
				[]string{"components", "schemas", name}})
		}
	}
	return out
}

func propertyCamelCase(d *Map) []Hit {
	type root struct {
		schema any
		path   []string
	}
	var roots []root
	AsMap(AsMap(d.Get("components")).Get("schemas")).Each(func(name string, s any) {
		roots = append(roots, root{s, []string{"components", "schemas", name}})
	})
	for _, m := range MediaSchemas(d) {
		roots = append(roots, root{m.Schema, m.Path})
	}
	seen := map[string]bool{}
	var out []Hit
	for _, r := range roots {
		for _, p := range SchemaProperties(r.schema, r.path) {
			key := strings.Join(p.Path, "\x00")
			if !seen[key] && !CamelCase.MatchString(p.Name) {
				seen[key] = true
				out = append(out, Hit{"Property '" + p.Name + "' is not camelCase", p.Path})
			}
		}
	}
	return out
}

func noInlineObjects(d *Map) []Hit {
	var out []Hit
	for _, m := range MediaSchemas(d) {
		s := m.Schema
		if !s.Has("$ref") && (s.Get("type") == "object" || s.Has("properties")) {
			where := "request body"
			if m.Kind != "request" {
				where = "response " + strings.SplitN(m.Kind, ":", 2)[1]
			}
			out = append(out, Hit{m.Label + " " + where + " uses an inline object schema", m.Path})
		}
	}
	return out
}

// NoLegacyNullable finds nullable / x-nullable keywords (also used by the fixer).
func NoLegacyNullable(d *Map) []Hit {
	var out []Hit
	var walk func(any, []string)
	walk = func(n any, path []string) {
		switch x := n.(type) {
		case *Map:
			for _, k := range []string{"nullable", "x-nullable"} {
				if _, ok := x.Get(k).(bool); ok {
					out = append(out, Hit{"'" + k + "' is legacy syntax; use type: [T, 'null']", join(path, k)})
				}
			}
			x.Each(func(k string, v any) {
				if k != "example" {
					walk(v, join(path, k))
				}
			})
		case []any:
			for i, e := range x {
				walk(e, join(path, strconv.Itoa(i)))
			}
		}
	}
	walk(d, nil)
	return out
}

func noUnusedComponents(d *Map) []Hit {
	// Collect "section/escapedName" once, so the check is linear in refs + components.
	const prefix = "#/components/"
	used := map[string]bool{}
	for _, r := range Refs(d) {
		if rest, ok := strings.CutPrefix(r.Ref, prefix); ok {
			if parts := strings.SplitN(rest, "/", 3); len(parts) >= 2 {
				used[parts[0]+"/"+parts[1]] = true
			}
		}
	}
	comps := AsMap(d.Get("components"))
	var out []Hit
	for _, section := range componentSections {
		for _, name := range AsMap(comps.Get(section)).Keys() {
			key := section + "/" + strings.ReplaceAll(strings.ReplaceAll(name, "~", "~0"), "/", "~1")
			if !used[key] {
				out = append(out, Hit{"components." + section + "." + name + " is never referenced",
					[]string{"components", section, name}})
			}
		}
	}
	return out
}
