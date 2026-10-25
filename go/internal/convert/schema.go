// Package convert turns Swagger 2.0, OpenAPI 3.0 and WSDL 1.1 into OpenAPI 3.1.
package convert

import (
	"strings"

	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive // data-model DSL
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// Result is the output of a conversion: the new document plus notes for the user.
type Result struct {
	Document   *Map
	SourceKind specio.SpecKind
	Warnings   []string
}

const maxDepth = 64

type refRule struct{ old, new string }

// RewriteRefs deep-copies node, rewriting $ref prefixes using the first matching rule.
func RewriteRefs(node any, rules []refRule) any {
	switch x := node.(type) {
	case *Map:
		out := NewMap()
		x.Each(func(k string, v any) {
			if s, ok := v.(string); ok && k == "$ref" {
				for _, r := range rules {
					if strings.HasPrefix(s, r.old) {
						s = r.new + s[len(r.old):]
						break
					}
				}
				out.Set(k, s)
			} else {
				out.Set(k, RewriteRefs(v, rules))
			}
		})
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = RewriteRefs(e, rules)
		}
		return out
	}
	return node
}

func addNullUpgrade(s *Map) *Map {
	if s.Has("$ref") || !s.Has("type") {
		return M("anyOf", []any{s, M("type", "null")})
	}
	var types []any
	if l, ok := s.Get("type").([]any); ok {
		types = append(types, l...)
	} else {
		types = []any{s.Get("type")}
	}
	if !Contains(types, "null") {
		types = append(types, "null")
	}
	s.Set("type", types)
	if e, ok := s.Get("enum").([]any); ok && !Contains(e, nil) {
		s.Set("enum", append(append([]any(nil), e...), nil))
	}
	return s
}

// UpgradeSchema converts one schema (recursively) to OpenAPI 3.1 semantics: nullable and
// x-nullable become type arrays, boolean exclusive bounds become numbers, type: file becomes a
// binary string, example becomes examples, and string discriminators become objects.
func UpgradeSchema(schema any) any { return upgradeSchema(schema, 0) }

func upgradeSchema(schema any, depth int) any {
	src, ok := schema.(*Map)
	if depth > maxDepth || !ok || src == nil {
		return schema
	}
	s := NewMap()
	src.Each(s.Set)
	for _, key := range []string{"allOf", "anyOf", "oneOf", "prefixItems"} {
		if l, ok := s.Get(key).([]any); ok {
			out := make([]any, len(l))
			for i, e := range l {
				out[i] = upgradeSchema(e, depth+1)
			}
			s.Set(key, out)
		}
	}
	for _, key := range []string{"items", "not", "additionalProperties", "if", "then", "else", "contains"} {
		if IsMap(s.Get(key)) {
			s.Set(key, upgradeSchema(s.Get(key), depth+1))
		}
	}
	for _, key := range []string{"properties", "patternProperties", "$defs"} {
		if m := AsMap(s.Get(key)); m != nil {
			out := NewMap()
			m.Each(func(k string, v any) { out.Set(k, upgradeSchema(v, depth+1)) })
			s.Set(key, out)
		}
	}
	if s.Get("type") == "file" {
		s.Set("type", "string")
		s.Set("format", "binary")
	}
	for _, pair := range [][2]string{{"minimum", "exclusiveMinimum"}, {"maximum", "exclusiveMaximum"}} {
		if b, ok := s.Get(pair[1]).(bool); ok {
			if b && s.Has(pair[0]) {
				s.Set(pair[1], s.Delete(pair[0]))
			} else {
				s.Delete(pair[1])
			}
		}
	}
	if name, ok := s.Get("discriminator").(string); ok {
		s.Set("discriminator", M("propertyName", name))
	}
	if s.Has("example") {
		s.Set("examples", []any{s.Delete("example")})
	}
	nullable := Truthy(s.Delete("nullable"))
	nullable = Truthy(s.Delete("x-nullable")) || nullable
	if nullable {
		return addNullUpgrade(s)
	}
	return s
}
