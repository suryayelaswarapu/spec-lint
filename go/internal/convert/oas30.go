package convert

import (
	. "github.com/suryayelaswarapu/spec-lint/go/internal/doc" //nolint:revive
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

var oasMethods = []string{"get", "put", "post", "delete", "options", "head", "patch", "trace"}

func upgradeContent(content any) {
	AsMap(content).Each(func(_ string, media any) {
		if m := AsMap(media); m.Has("schema") {
			m.Set("schema", UpgradeSchema(m.Get("schema")))
		}
	})
}

func upgradeParams(params []any) {
	for _, p := range params {
		if m := AsMap(p); m.Has("schema") {
			m.Set("schema", UpgradeSchema(m.Get("schema")))
		}
	}
}

func upgradeResponse(resp any) {
	r := AsMap(resp)
	if r == nil {
		return
	}
	upgradeContent(r.Get("content"))
	AsMap(r.Get("headers")).Each(func(_ string, h any) {
		if m := AsMap(h); m.Has("schema") {
			m.Set("schema", UpgradeSchema(m.Get("schema")))
		}
	})
}

// UpgradeOpenAPI30 converts an OpenAPI 3.0 document to 3.1 (schema keyword changes only).
func UpgradeOpenAPI30(d *Map) Result {
	out := CopyMap(d)
	out.Set("openapi", "3.1.0")
	AsMap(out.Get("paths")).Each(func(_ string, item any) {
		it := AsMap(item)
		if it == nil {
			return
		}
		upgradeParams(AsList(it.Get("parameters")))
		for _, method := range oasMethods {
			op := AsMap(it.Get(method))
			if op == nil {
				continue
			}
			upgradeParams(AsList(op.Get("parameters")))
			if body := AsMap(op.Get("requestBody")); body != nil {
				upgradeContent(body.Get("content"))
			}
			AsMap(op.Get("responses")).Each(func(_ string, r any) { upgradeResponse(r) })
		}
	})
	comp := AsMap(out.Get("components"))
	if schemas := AsMap(comp.Get("schemas")); schemas != nil {
		upgraded := NewMap()
		schemas.Each(func(k string, v any) { upgraded.Set(k, UpgradeSchema(v)) })
		comp.Set("schemas", upgraded)
	}
	var params []any
	AsMap(comp.Get("parameters")).Each(func(_ string, p any) { params = append(params, p) })
	upgradeParams(params)
	AsMap(comp.Get("requestBodies")).Each(func(_ string, b any) {
		if m := AsMap(b); m != nil {
			upgradeContent(m.Get("content"))
		}
	})
	AsMap(comp.Get("responses")).Each(func(_ string, r any) { upgradeResponse(r) })
	return Result{Document: out, SourceKind: specio.OpenAPI30, Warnings: []string{}}
}
