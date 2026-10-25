package convert

import (
	"fmt"
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

var (
	swaggerMethods = []string{"get", "put", "post", "delete", "options", "head", "patch"}
	swaggerRefs    = []refRule{
		{"#/definitions/", "#/components/schemas/"},
		{"#/responses/", "#/components/responses/"},
		{"#/securityDefinitions/", "#/components/securitySchemes/"},
	}
	queryStyles = map[string][2]any{
		"csv": {"form", false}, "ssv": {"spaceDelimited", false},
		"pipes": {"pipeDelimited", false}, "multi": {"form", true},
	}
	paramSchemaKeys = []string{"type", "format", "items", "enum", "default", "maximum",
		"exclusiveMaximum", "minimum", "exclusiveMinimum", "maxLength", "minLength", "pattern",
		"maxItems", "minItems", "uniqueItems", "multipleOf"}
	opKeys = []string{"tags", "summary", "description", "externalDocs", "operationId", "deprecated",
		"security"}
)

// Swagger2Converter converts Swagger 2.0 to OpenAPI 3.1 following the official 2.0 -> 3.0
// migration notes plus the 3.0 -> 3.1 schema changes. Lossy spots become warnings.
type Swagger2Converter struct {
	src            *Map
	warnings       []string
	globalConsumes []any
	globalProduces []any
	bodyParams     map[string]bool
}

// NewSwagger2Converter prepares a converter for a parsed Swagger 2.0 document.
func NewSwagger2Converter(src *Map) *Swagger2Converter {
	c := &Swagger2Converter{src: CopyMap(src), warnings: []string{}, bodyParams: map[string]bool{}}
	c.globalConsumes = orDefault(c.src.Get("consumes"))
	c.globalProduces = orDefault(c.src.Get("produces"))
	AsMap(c.src.Get("parameters")).Each(func(name string, p any) {
		if in := AsMap(p).Get("in"); in == "body" || in == "formData" {
			c.bodyParams[name] = true
		}
	})
	return c
}

func orDefault(v any) []any {
	if Truthy(v) {
		return AsList(v)
	}
	return []any{"application/json"}
}

func (c *Swagger2Converter) warn(format string, args ...any) {
	c.warnings = append(c.warnings, fmt.Sprintf(format, args...))
}

func (c *Swagger2Converter) schema(s any) any { return UpgradeSchema(RewriteRefs(s, swaggerRefs)) }

// Convert runs the conversion.
func (c *Swagger2Converter) Convert() Result {
	out := M("openapi", "3.1.0")
	info := CopyMap(AsMap(c.src.Get("info")))
	info.SetDefault("title", "Untitled API")
	info.SetDefault("version", "0.0.0")
	out.Set("info", info)
	out.Set("servers", c.servers())
	for _, key := range []string{"security", "tags", "externalDocs"} {
		if c.src.Has(key) {
			out.Set(key, DeepCopy(c.src.Get(key)))
		}
	}
	out.Set("paths", c.paths())
	if comp := c.components(); comp.Len() > 0 {
		out.Set("components", comp)
	}
	c.src.Each(func(k string, v any) {
		if strings.HasPrefix(k, "x-") {
			out.Set(k, DeepCopy(v))
		}
	})
	return Result{Document: out, SourceKind: specio.Swagger2, Warnings: c.warnings}
}

func (c *Swagger2Converter) servers() []any {
	host := c.src.Get("host")
	base := "/"
	if Truthy(c.src.Get("basePath")) {
		base = Str(c.src.Get("basePath"))
	}
	if !Truthy(host) {
		c.warn("No 'host' in Swagger 2.0 spec; server URL is relative ('%s').", base)
		return []any{M("url", base)}
	}
	schemes := []any{"https"}
	if Truthy(c.src.Get("schemes")) {
		schemes = AsList(c.src.Get("schemes"))
	}
	if !c.src.Has("schemes") {
		c.warn("No 'schemes' declared; assumed https.")
	}
	var out []any
	for _, scheme := range schemes {
		url := strings.TrimRight(Str(scheme)+"://"+Str(host)+base, "/")
		if url == "" {
			url = "/"
		}
		out = append(out, M("url", url))
	}
	return out
}

func (c *Swagger2Converter) components() *Map {
	comp := NewMap()
	if Truthy(c.src.Get("definitions")) {
		schemas := NewMap()
		AsMap(c.src.Get("definitions")).Each(func(k string, v any) { schemas.Set(k, c.schema(v)) })
		comp.Set("schemas", schemas)
	}
	params, bodies := NewMap(), NewMap()
	AsMap(c.src.Get("parameters")).Each(func(name string, p any) {
		if c.bodyParams[name] {
			bodies.Set(name, c.requestBody([]*Map{AsMap(p)}, c.globalConsumes))
		} else {
			params.Set(name, c.parameter(AsMap(p)))
		}
	})
	if params.Len() > 0 {
		comp.Set("parameters", params)
	}
	if bodies.Len() > 0 {
		comp.Set("requestBodies", bodies)
	}
	if Truthy(c.src.Get("responses")) {
		responses := NewMap()
		AsMap(c.src.Get("responses")).Each(func(k string, v any) {
			responses.Set(k, c.response(AsMap(v), c.globalProduces))
		})
		comp.Set("responses", responses)
	}
	if Truthy(c.src.Get("securityDefinitions")) {
		schemes := NewMap()
		AsMap(c.src.Get("securityDefinitions")).Each(func(k string, v any) {
			schemes.Set(k, c.securityScheme(k, AsMap(v)))
		})
		comp.Set("securitySchemes", schemes)
	}
	return comp
}

func (c *Swagger2Converter) securityScheme(name string, d *Map) *Map {
	var out *Map
	switch d.Get("type") {
	case "basic":
		out = M("type", "http", "scheme", "basic")
	case "apiKey":
		out = M("type", "apiKey", "name", d.Get("name"), "in", d.Get("in"))
	case "oauth2":
		flows := map[string]string{"implicit": "implicit", "password": "password",
			"application": "clientCredentials", "accessCode": "authorizationCode"}
		flowName, ok := flows[Str(d.Get("flow"))]
		if !ok {
			c.warn("securityDefinitions.%s: unknown oauth2 flow '%s'", name, Str(d.Get("flow")))
			flowName = "implicit"
		}
		scopes := d.Get("scopes")
		if !Truthy(scopes) {
			scopes = NewMap()
		}
		flow := M("scopes", scopes)
		for _, k := range []string{"authorizationUrl", "tokenUrl"} {
			if d.Has(k) {
				flow.Set(k, d.Get(k))
			}
		}
		out = M("type", "oauth2", "flows", M(flowName, flow))
	default:
		c.warn("securityDefinitions.%s: unknown type '%s'; copied as-is", name, Str(d.Get("type")))
		out = CopyMap(d)
	}
	if d.Has("description") {
		out.Set("description", d.Get("description"))
	}
	return out
}

func (c *Swagger2Converter) paths() *Map {
	paths := NewMap()
	AsMap(c.src.Get("paths")).Each(func(path string, raw any) {
		item := AsMap(raw)
		if item == nil {
			return
		}
		newItem := NewMap()
		if item.Has("$ref") {
			c.warn("paths.%s: external path item $ref kept as-is", path)
			newItem.Set("$ref", item.Get("$ref"))
		}
		pathParams := AsList(item.Get("parameters"))
		if plain, _ := c.splitParams(pathParams); len(plain) > 0 {
			newItem.Set("parameters", plain)
		}
		for _, method := range swaggerMethods {
			if item.Has(method) {
				newItem.Set(method, c.operation(AsMap(item.Get(method)), pathParams, path, method))
			}
		}
		item.Each(func(k string, v any) {
			if strings.HasPrefix(k, "x-") {
				newItem.Set(k, DeepCopy(v))
			}
		})
		paths.Set(path, newItem)
	})
	return paths
}

func (c *Swagger2Converter) splitParams(params []any) ([]any, []*Map) {
	var plain []any
	var body []*Map
	for _, raw := range params {
		p := AsMap(raw)
		if p == nil {
			continue
		}
		switch {
		case p.Has("$ref"):
			ref := Str(p.Get("$ref"))
			name := ref[strings.LastIndex(ref, "/")+1:]
			if c.bodyParams[name] {
				body = append(body, M("$ref", "#/components/requestBodies/"+name))
			} else {
				plain = append(plain, M("$ref", "#/components/parameters/"+name))
			}
		case p.Get("in") == "body" || p.Get("in") == "formData":
			body = append(body, p)
		default:
			plain = append(plain, c.parameter(p))
		}
	}
	return plain, body
}

func (c *Swagger2Converter) operation(op *Map, pathParams []any, path, method string) *Map {
	where := strings.ToUpper(method) + " " + path
	out := NewMap()
	for _, key := range opKeys {
		if op.Has(key) {
			out.Set(key, DeepCopy(op.Get(key)))
		}
	}
	consumes, produces := c.globalConsumes, c.globalProduces
	if Truthy(op.Get("consumes")) {
		consumes = AsList(op.Get("consumes"))
	}
	if Truthy(op.Get("produces")) {
		produces = AsList(op.Get("produces"))
	}
	plain, body := c.splitParams(AsList(op.Get("parameters")))
	if len(plain) > 0 {
		out.Set("parameters", plain)
	}
	if len(body) == 0 {
		_, body = c.splitParams(pathParams)
	}
	if len(body) > 0 {
		var refs []*Map
		for _, b := range body {
			if b.Has("$ref") {
				refs = append(refs, b)
			}
		}
		if len(refs) > 0 {
			out.Set("requestBody", refs[0])
			if len(body) > 1 {
				c.warn("%s: multiple body parameters; kept %s", where, Str(refs[0].Get("$ref")))
			}
		} else {
			out.Set("requestBody", c.requestBody(body, consumes))
		}
	}
	responses := NewMap()
	AsMap(op.Get("responses")).Each(func(code string, resp any) {
		if r := AsMap(resp); r != nil {
			responses.Set(code, c.response(r, produces))
		}
	})
	out.Set("responses", responses)
	if op.Has("schemes") {
		c.warn("%s: per-operation 'schemes' dropped (use servers instead)", where)
	}
	op.Each(func(k string, v any) {
		if strings.HasPrefix(k, "x-") {
			out.Set(k, DeepCopy(v))
		}
	})
	return out
}

func (c *Swagger2Converter) paramSchema(p *Map) *Map {
	schema := NewMap()
	for _, key := range paramSchemaKeys {
		if p.Has(key) {
			schema.Set(key, DeepCopy(p.Get(key)))
		}
	}
	if schema.Has("items") {
		schema.Set("items", c.paramSchema(AsMap(schema.Get("items"))))
	}
	if Truthy(p.Get("x-nullable")) {
		schema.Set("x-nullable", true)
	}
	return UpgradeSchema(schema).(*Map)
}

func (c *Swagger2Converter) parameter(p *Map) *Map {
	out := M("name", p.Get("name"), "in", p.Get("in"))
	for _, key := range []string{"description", "required", "deprecated", "allowEmptyValue"} {
		if p.Has(key) {
			out.Set(key, p.Get(key))
		}
	}
	if out.Get("in") == "path" {
		out.Set("required", true)
	}
	out.Set("schema", c.paramSchema(p))
	format := Str(p.Get("collectionFormat"))
	if p.Get("type") == "array" && format != "" {
		style, known := queryStyles[format]
		switch {
		case out.Get("in") == "query" && known:
			out.Set("style", style[0])
			out.Set("explode", style[1])
		case format == "csv":
			out.Set("style", "simple")
			out.Set("explode", false)
		default:
			c.warn("parameter '%s': collectionFormat '%s' has no OpenAPI 3 equivalent for in=%s; defaulted",
				Str(p.Get("name")), format, Str(out.Get("in")))
		}
	}
	p.Each(func(k string, v any) {
		if strings.HasPrefix(k, "x-") && k != "x-nullable" {
			out.Set(k, DeepCopy(v))
		}
	})
	return out
}

func (c *Swagger2Converter) requestBody(params []*Map, consumes []any) *Map {
	for _, p := range params {
		if p.Get("in") != "body" {
			continue
		}
		content := NewMap()
		for _, ct := range consumes {
			var s any = NewMap()
			if p.Has("schema") {
				s = p.Get("schema")
			}
			content.Set(Str(ct), M("schema", c.schema(s)))
		}
		out := M("content", content)
		if Truthy(p.Get("description")) {
			out.Set("description", p.Get("description"))
		}
		if Truthy(p.Get("required")) {
			out.Set("required", true)
		}
		return out
	}
	props := NewMap()
	var required []any
	hasFile := false
	for _, p := range params {
		hasFile = hasFile || p.Get("type") == "file"
		prop := c.paramSchema(p)
		if Truthy(p.Get("description")) {
			prop.Set("description", p.Get("description"))
		}
		props.Set(Str(p.Get("name")), prop)
		if Truthy(p.Get("required")) {
			required = append(required, Str(p.Get("name")))
		}
	}
	schema := M("type", "object", "properties", props)
	if len(required) > 0 {
		schema.Set("required", required)
	}
	var formTypes []string
	for _, ct := range consumes {
		if strings.Contains(Str(ct), "form") {
			formTypes = append(formTypes, Str(ct))
		}
	}
	if hasFile {
		formTypes = []string{"multipart/form-data"}
	} else if len(formTypes) == 0 {
		formTypes = []string{"application/x-www-form-urlencoded"}
	}
	content := NewMap()
	for _, ct := range formTypes {
		content.Set(ct, M("schema", schema))
	}
	result := M("content", content)
	if len(required) > 0 {
		result.Set("required", true)
	}
	return result
}

func (c *Swagger2Converter) response(resp *Map, produces []any) *Map {
	if resp.Has("$ref") {
		return RewriteRefs(resp, swaggerRefs).(*Map)
	}
	var desc any = ""
	if resp.Has("description") {
		desc = resp.Get("description")
	}
	out := M("description", desc)
	if resp.Has("schema") {
		schema := c.schema(resp.Get("schema"))
		examples := AsMap(resp.Get("examples"))
		content := NewMap()
		for _, ct := range produces {
			media := M("schema", schema)
			if examples.Has(Str(ct)) {
				media.Set("example", examples.Get(Str(ct)))
			}
			content.Set(Str(ct), media)
		}
		out.Set("content", content)
	}
	if Truthy(resp.Get("headers")) {
		headers := NewMap()
		AsMap(resp.Get("headers")).Each(func(name string, raw any) {
			h := AsMap(raw)
			header := NewMap()
			if h.Has("description") {
				header.Set("description", h.Get("description"))
			}
			header.Set("schema", c.paramSchema(h))
			headers.Set(name, header)
		})
		out.Set("headers", headers)
	}
	return out
}
