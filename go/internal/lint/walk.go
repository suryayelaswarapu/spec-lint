package lint

import (
	"strconv"
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
)

var (
	httpMethods       = []string{"get", "put", "post", "delete", "options", "head", "patch", "trace"}
	componentSections = []string{"schemas", "parameters", "responses", "requestBodies", "headers", "examples"}
)

const maxDepth = 64

// Op is an operation with its location.
type Op struct {
	Path, Method string
	Op, Item     *Map
}

// Label is "GET /path".
func (o Op) Label() string { return strings.ToUpper(o.Method) + " " + o.Path }

// Operations lists every operation in path then method order.
func Operations(d *Map) []Op {
	var out []Op
	AsMap(d.Get("paths")).Each(func(path string, item any) {
		it := AsMap(item)
		for _, m := range httpMethods {
			if op := AsMap(it.Get(m)); op != nil {
				out = append(out, Op{path, m, op, it})
			}
		}
	})
	return out
}

func join(base []string, more ...string) []string {
	return append(append([]string(nil), base...), more...)
}

// RefHit is a $ref string and where it was found.
type RefHit struct {
	Ref  string
	Path []string
}

// Refs collects every $ref string.
func Refs(node any) []RefHit {
	var out []RefHit
	var walk func(any, []string)
	walk = func(n any, path []string) {
		switch x := n.(type) {
		case *Map:
			x.Each(func(k string, v any) {
				if s, ok := v.(string); ok && k == "$ref" {
					out = append(out, RefHit{s, join(path, "$ref")})
				} else {
					walk(v, join(path, k))
				}
			})
		case []any:
			for i, e := range x {
				walk(e, join(path, strconv.Itoa(i)))
			}
		}
	}
	walk(node, nil)
	return out
}

// ResolvePointer resolves a local "#/a/b" reference; ok=false when it does not exist.
func ResolvePointer(d any, ref string) (any, bool) {
	if !strings.HasPrefix(ref, "#") {
		return nil, false
	}
	node := d
	parts := strings.Split(ref[1:], "/")
	for _, raw := range parts[1:] {
		key := strings.ReplaceAll(strings.ReplaceAll(raw, "~1", "/"), "~0", "~")
		switch x := node.(type) {
		case *Map:
			if !x.Has(key) {
				return nil, false
			}
			node = x.Get(key)
		case []any:
			i, err := strconv.Atoi(key)
			if err != nil || i < 0 || i >= len(x) {
				return nil, false
			}
			node = x[i]
		default:
			return nil, false
		}
	}
	return node, true
}

// Deref follows $ref chains (max 10 hops); unresolvable refs yield nil.
func Deref(d *Map, node any) any {
	for i := 0; i < 10; i++ {
		m := AsMap(node)
		ref, ok := m.Get("$ref").(string)
		if !ok {
			break
		}
		node, _ = ResolvePointer(d, ref)
	}
	return node
}

// Media is a request/response body schema.
type Media struct {
	Kind   string
	Schema *Map
	Path   []string
	Label  string
}

// MediaSchemas lists request/response body schemas of every operation.
func MediaSchemas(d *Map) []Media {
	var out []Media
	for _, o := range Operations(d) {
		base := []string{"paths", o.Path, o.Method}
		if body := AsMap(o.Op.Get("requestBody")); body != nil {
			AsMap(body.Get("content")).Each(func(media string, obj any) {
				if s := AsMap(AsMap(obj).Get("schema")); s != nil {
					out = append(out, Media{"request", s,
						join(base, "requestBody", "content", media, "schema"), o.Label()})
				}
			})
		}
		AsMap(o.Op.Get("responses")).Each(func(code string, resp any) {
			AsMap(AsMap(resp).Get("content")).Each(func(media string, obj any) {
				if s := AsMap(AsMap(obj).Get("schema")); s != nil {
					out = append(out, Media{"response:" + code, s,
						join(base, "responses", code, "content", media, "schema"), o.Label()})
				}
			})
		})
	}
	return out
}

// Prop is a schema property name with its location.
type Prop struct {
	Name string
	Path []string
}

// SchemaProperties lists property names of a schema and its inline sub-schemas.
func SchemaProperties(schema any, path []string) []Prop {
	var out []Prop
	var walk func(any, []string, int)
	walk = func(s any, p []string, depth int) {
		m := AsMap(s)
		if depth > maxDepth || m == nil {
			return
		}
		AsMap(m.Get("properties")).Each(func(name string, sub any) {
			pp := join(p, "properties", name)
			out = append(out, Prop{name, pp})
			walk(sub, pp, depth+1)
		})
		for _, k := range []string{"items", "additionalProperties", "not"} {
			if IsMap(m.Get(k)) {
				walk(m.Get(k), join(p, k), depth+1)
			}
		}
		for _, k := range []string{"allOf", "anyOf", "oneOf"} {
			for i, sub := range AsList(m.Get(k)) {
				walk(sub, join(p, k, strconv.Itoa(i)), depth+1)
			}
		}
	}
	walk(schema, path, 0)
	return out
}
