package server

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
)

func root(rel string) string { return filepath.Join("..", "..", "..", rel) }

func read(t *testing.T, rel string) string {
	t.Helper()
	b, err := os.ReadFile(root(filepath.Join("testdata", rel)))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

type resp struct {
	code int
	hdr  http.Header
	body map[string]any
	raw  string
}

func do(h http.Handler, method, path string, body io.Reader, headers ...string) resp {
	req := httptest.NewRequest(method, path, body)
	req.Header.Set("Content-Type", "application/json")
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return resp{rec.Code, rec.Header(), m, rec.Body.String()}
}

func post(h http.Handler, path string, payload any, headers ...string) resp {
	b, _ := json.Marshal(payload)
	return do(h, http.MethodPost, path, bytes.NewReader(b), headers...)
}

func TestHealthRulesHeadersAndStatic(t *testing.T) {
	h := Handler(Settings{})
	r := do(h, http.MethodGet, "/health", nil)
	if r.body["implementation"] != "go" || r.body["status"] != "UP" || r.hdr.Get("X-Frame-Options") != "DENY" ||
		!strings.Contains(r.hdr.Get("Content-Security-Policy"), "default-src 'self'") {
		t.Error(r)
	}
	if rules := do(h, http.MethodGet, "/api/v1/rules", nil).body["rules"].([]any); len(rules) != 29 {
		t.Error(len(rules))
	}
	for route := range staticFiles {
		if r := do(h, http.MethodGet, route, nil); r.code != 200 || r.raw == "" {
			t.Error(route, r.code)
		}
	}
	if r := do(h, http.MethodGet, "/nope", nil); r.code != 404 || r.body["title"] != "Not found" {
		t.Error(r)
	}
	if r := do(h, http.MethodGet, "/api/v1/convert", nil); r.code != 405 {
		t.Error(r.code)
	}
}

func TestStaticMatchesSharedSources(t *testing.T) {
	for _, f := range []string{"index.html", "app.js", "styles.css", "docs.html", "docs.js"} {
		a, _ := os.ReadFile(filepath.Join("static", f))
		b, _ := os.ReadFile(root(filepath.Join("web", f)))
		if len(a) == 0 || !bytes.Equal(a, b) {
			t.Errorf("%s differs from /web", f)
		}
	}
	a, _ := os.ReadFile(filepath.Join("static", "openapi.yaml"))
	b, _ := os.ReadFile(root("docs/api/openapi.yaml"))
	if !bytes.Equal(a, b) {
		t.Error("openapi.yaml differs from docs/api")
	}
}

func TestModernizeMatchesConformance(t *testing.T) {
	h := Handler(Settings{})
	for _, name := range []string{"weather-api-v2", "weather-service", "legacy-forecast-rpc", "weather-api-v3.0"} {
		raw, _ := os.ReadFile(root("testdata/conformance/" + name + ".json"))
		var golden map[string]any
		_ = json.Unmarshal(raw, &golden)
		r := post(h, "/api/v1/modernize", map[string]any{"spec": read(t, golden["input"].(string)), "filename": name})
		if r.code != 200 || !reflect.DeepEqual(r.body["openapi"], golden["modernized"]) ||
			r.body["sourceKind"] != golden["sourceKind"] || float64(len(r.body["warnings"].([]any))) != golden["warnings"] ||
			!strings.Contains(r.body["report"].(string), "# spec-lint migration report") ||
			r.body["summary"].(map[string]any)["error"] != 0.0 {
			t.Errorf("%s: %d %v", name, r.code, r.body["detail"])
		}
	}
}

func TestConvertLintFix(t *testing.T) {
	h := Handler(Settings{})
	if r := post(h, "/api/v1/convert", map[string]any{"spec": read(t, "weather/weather-api-v2.yaml")}); r.body["sourceKind"] != "swagger-2.0" {
		t.Error(r)
	}
	spec := read(t, "openapi/weather-api-v3.0.yaml")
	lint := post(h, "/api/v1/lint", map[string]any{"spec": spec})
	rules := map[string]bool{}
	for _, f := range lint.body["findings"].([]any) {
		rules[f.(map[string]any)["rule"].(string)] = true
		if _, ok := f.(map[string]any)["line"]; !ok {
			t.Error("missing line")
		}
	}
	if !reflect.DeepEqual(rules, map[string]bool{"openapi-version-3-1": true, "no-legacy-nullable": true}) {
		t.Error(rules)
	}
	strict := post(h, "/api/v1/lint", map[string]any{"spec": spec, "config": map[string]any{"extends": "strict"}})
	if strict.body["summary"].(map[string]any)["error"] != 2.0 {
		t.Error(strict.body)
	}
	fixed := post(h, "/api/v1/fix", map[string]any{"spec": spec})
	if len(fixed.body["fixes"].([]any)) != 1 || strings.Contains(fixed.body["yaml"].(string), "nullable") {
		t.Error(fixed.body)
	}
	noFix := post(h, "/api/v1/modernize", map[string]any{"spec": spec, "fix": false})
	if len(noFix.body["fixes"].([]any)) != 0 {
		t.Error(noFix.body)
	}
}

func TestBadInput(t *testing.T) {
	h := Handler(Settings{})
	cases := []struct {
		route    string
		payload  any
		fragment string
	}{
		{"/api/v1/lint", map[string]any{"spec": "<definitions/>"}, "WSDL"},
		{"/api/v1/fix", map[string]any{"spec": "swagger: '2.0'\npaths: {}\n"}, "Swagger 2.0"},
		{"/api/v1/convert", map[string]any{"spec": ""}, "spec"},
		{"/api/v1/convert", map[string]any{"spec": 5}, "spec"},
		{"/api/v1/convert", map[string]any{"spec": "title: x"}, "Not an OpenAPI"},
		{"/api/v1/lint", map[string]any{"spec": "openapi: 3.1.0", "config": "x"}, "config"},
		{"/api/v1/lint", map[string]any{"spec": "openapi: 3.1.0", "config": map[string]any{"extends": "loose"}}, "extends"},
		{"/api/v1/modernize", map[string]any{"spec": "openapi: 3.1.0", "fix": "yes"}, "fix"},
		{"/api/v1/modernize", map[string]any{"spec": "openapi: 3.1.0", "config": 1}, "config"},
		{"/api/v1/fix", map[string]any{"spec": "openapi: 3.1.0", "config": 1}, "config"},
		{"/api/v1/fix", map[string]any{"spec": "<definitions/>"}, "WSDL"},
		{"/api/v1/lint", map[string]any{"spec": "{bad"}, "JSON/YAML"},
		{"/api/v1/lint", map[string]any{"spec": "title: x"}, "Not an OpenAPI"},
		{"/api/v1/modernize", map[string]any{"spec": "title: x"}, "Not an OpenAPI"},
		{"/api/v1/lint", map[string]any{}, "spec"},
		{"/api/v1/fix", map[string]any{}, "spec"},
		{"/api/v1/modernize", map[string]any{}, "spec"},
	}
	for _, c := range cases {
		r := post(h, c.route, c.payload)
		if r.code != 400 || !strings.HasPrefix(r.hdr.Get("Content-Type"), "application/problem+json") ||
			!strings.Contains(Str(r.body["detail"]), c.fragment) {
			t.Errorf("%s %v -> %d %v", c.route, c.payload, r.code, r.body)
		}
	}
	for _, raw := range []string{"{not json", `["a"]`, ""} {
		if r := do(h, http.MethodPost, "/api/v1/convert", strings.NewReader(raw)); r.code != 400 {
			t.Error(raw, r.code)
		}
	}
}

func Str(v any) string { return doc.Str(v) }

func TestUnsafeInput(t *testing.T) {
	h := Handler(Settings{})
	for _, name := range []string{"xxe.wsdl", "billion-laughs.wsdl", "yaml-aliases.yaml"} {
		if r := post(h, "/api/v1/convert", map[string]any{"spec": read(t, "security/"+name)}); r.code != 422 ||
			r.body["title"] != "Unsafe input rejected" {
			t.Error(name, r.code)
		}
	}
	r := post(h, "/api/v1/convert", map[string]any{"spec": read(t, "security/path-traversal.wsdl")})
	if r.code != 400 || !strings.Contains(Str(r.body["detail"]), "portType") {
		t.Error(r.code, r.body)
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestBodyLimitAndReadErrors(t *testing.T) {
	h := Handler(Settings{MaxBytes: 100})
	if r := post(h, "/api/v1/convert", map[string]any{"spec": strings.Repeat("x", 200)}); r.code != 413 ||
		r.hdr.Get("X-Content-Type-Options") != "nosniff" {
		t.Error(r.code)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/convert", strings.NewReader(`{"spec":"`+strings.Repeat("x", 200)+`"}`))
	req.ContentLength = -1 // chunked: exercises the streaming limit
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 413 {
		t.Error("chunked", rec.Code)
	}
	req = httptest.NewRequest(http.MethodPost, "/api/v1/convert", errReader{})
	req.ContentLength = -1
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 400 {
		t.Error("read error", rec.Code)
	}
}

func TestBearerToken(t *testing.T) {
	h := Handler(Settings{APIToken: "s3cret"})
	body := map[string]any{"spec": read(t, "openapi/weather-api-v3.0.yaml")}
	if r := post(h, "/api/v1/lint", body); r.code != 401 || r.hdr.Get("WWW-Authenticate") != "Bearer" {
		t.Error(r.code)
	}
	if r := post(h, "/api/v1/lint", body, "Authorization", "Bearer nope"); r.code != 401 {
		t.Error(r.code)
	}
	if r := post(h, "/api/v1/lint", body, "Authorization", "Bearer s3cret"); r.code != 200 {
		t.Error(r.code)
	}
	if do(h, http.MethodGet, "/api/v1/rules", nil).code != 200 || do(h, http.MethodGet, "/health", nil).code != 200 {
		t.Error("open endpoints")
	}
}

func TestSettingsServerAndErrors(t *testing.T) {
	t.Setenv("SPECLINT_MAX_BYTES", "123")
	t.Setenv("SPECLINT_API_TOKEN", "t")
	if s, err := SettingsFromEnv(); err != nil || s.MaxBytes != 123 || s.APIToken != "t" {
		t.Error(s, err)
	}
	for _, bad := range []string{"nope", "2MB", "0", "-5"} {
		t.Setenv("SPECLINT_MAX_BYTES", bad)
		if _, err := SettingsFromEnv(); err == nil || !strings.Contains(err.Error(), "SPECLINT_MAX_BYTES") {
			t.Errorf("%q: %v", bad, err)
		}
	}
	t.Setenv("SPECLINT_MAX_BYTES", "")
	if s, err := SettingsFromEnv(); err != nil || s.MaxBytes != DefaultMaxBytes {
		t.Error("default")
	}
	srv := NewServer("127.0.0.1:0", Settings{})
	if srv.ReadHeaderTimeout == 0 || srv.Handler == nil {
		t.Error("timeouts")
	}
	rec := httptest.NewRecorder()
	errorResponse(rec, io.EOF)
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "EOF") {
		t.Error(rec.Code, rec.Body.String())
	}
}

func TestSafeFilename(t *testing.T) {
	cases := map[any]string{nil: "spec", "../../etc/passwd": "passwd", `C:\x\api.wsdl`: "api.wsdl",
		"a<b>.yaml": "a_b_.yaml", strings.Repeat("x", 300): strings.Repeat("x", 200), "   ": "spec", 7: "spec"}
	for in, want := range cases {
		if got := SafeFilename(in); got != want {
			t.Errorf("%v -> %q", in, got)
		}
	}
}
