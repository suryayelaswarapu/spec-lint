package app

import (
	"bytes"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type run struct {
	code     int
	out, err string
}

func exec(args ...string) run {
	var out, errb bytes.Buffer
	code := Run(args, &out, &errb)
	return run{code, out.String(), errb.String()}
}

func td(rel string) string { return filepath.Join("..", "..", "..", "testdata", rel) }

func TestVersionHelpRules(t *testing.T) {
	if r := exec("--version"); r.code != 0 || !strings.Contains(r.out, "speclint 0.1.0") {
		t.Error(r)
	}
	if r := exec(); r.code != 2 {
		t.Error(r)
	}
	if r := exec("--help"); r.code != 0 || !strings.Contains(r.out, "Commands") {
		t.Error(r)
	}
	if r := exec("rules"); !strings.Contains(r.out, "operation-id-required") || !strings.Contains(r.out, "fix") {
		t.Error(r)
	}
	if r := exec("rules", "--format", "json"); !strings.HasPrefix(r.out, "[") || !strings.Contains(r.out, "servers-defined") {
		t.Error(r)
	}
	if r := exec("bogus"); r.code != 2 {
		t.Error(r)
	}
	if r := exec("--nope"); r.code != 2 {
		t.Error(r)
	}
	if r := exec("--max-size", "0", "rules"); r.code != 2 {
		t.Error(r)
	}
}

func TestConvert(t *testing.T) {
	r := exec("convert", td("weather/legacy-forecast-rpc.wsdl"))
	if r.code != 0 || !strings.HasPrefix(r.out, "openapi: 3.1.0") || !strings.Contains(r.err, "note:") {
		t.Error(r)
	}
	target := filepath.Join(t.TempDir(), "out.json")
	if r := exec("convert", td("weather/weather-api-v2.yaml"), "-o", target); r.code != 0 {
		t.Error(r)
	}
	if b, _ := os.ReadFile(target); !strings.Contains(string(b), `"openapi": "3.1.0"`) {
		t.Error(string(b))
	}
	if r := exec("convert", td("weather/weather-service.wsdl"), "-f", "json", "--no-local-imports"); !strings.HasPrefix(r.out, "{") {
		t.Error(r)
	}
	if r := exec("convert", td("weather/weather-service.wsdl"), "-f", "xml"); r.code != 2 {
		t.Error(r)
	}
	if r := exec("convert", td("security/xxe.wsdl")); r.code != 2 || !strings.Contains(r.err, "Unsafe XML") {
		t.Error(r)
	}
}

func TestLint(t *testing.T) {
	spec := td("openapi/weather-api-v3.0.yaml")
	if r := exec("lint", spec); r.code != 0 || !strings.Contains(r.out, "openapi-version-3-1") {
		t.Error(r)
	}
	if r := exec("lint", spec, "--fail-on", "warning"); r.code != 1 {
		t.Error(r)
	}
	if r := exec("lint", "--format", "json", spec); !strings.Contains(r.out, `"summary"`) {
		t.Error(r)
	}
	sarif := filepath.Join(t.TempDir(), "r.sarif")
	exec("lint", spec, "--format", "sarif", "-o", sarif)
	if b, _ := os.ReadFile(sarif); !strings.Contains(string(b), `"2.1.0"`) {
		t.Error(string(b))
	}
	if r := exec("lint", spec, "--format", "html"); r.code != 2 {
		t.Error(r)
	}
	if r := exec("lint", spec, "--fail-on", "sometimes"); r.code != 2 {
		t.Error(r)
	}
	cfg := filepath.Join(t.TempDir(), "c.yaml")
	_ = os.WriteFile(cfg, []byte("extends: loose\n"), 0o600)
	if r := exec("lint", spec, "-c", cfg); r.code != 2 {
		t.Error(r)
	}
	if r := exec("lint", td("weather/weather-api-v2.yaml")); r.code != 2 || !strings.Contains(r.err, "modernize") {
		t.Error(r)
	}
	if r := exec("lint", td("weather/weather-service.wsdl")); r.code != 2 || !strings.Contains(r.err, "WSDL") {
		t.Error(r)
	}
	if r := exec("lint", td("security/yaml-aliases.yaml")); r.code != 2 {
		t.Error(r)
	}
	if r := exec("lint"); r.code != 2 {
		t.Error(r)
	}
}

func TestFix(t *testing.T) {
	out := filepath.Join(t.TempDir(), "fixed.yaml")
	r := exec("fix", td("openapi/weather-api-v3.0.yaml"), "-o", out)
	b, _ := os.ReadFile(out)
	if r.code != 0 || !strings.Contains(r.err, "fixed [no-legacy-nullable]") || strings.Contains(string(b), "nullable") {
		t.Error(r)
	}
	if r := exec("fix", td("openapi/weather-api-v3.0.yaml")); !strings.Contains(r.out, "openapi:") {
		t.Error(r)
	}
	if r := exec("fix", td("weather/weather-api-v2.yaml")); r.code != 2 {
		t.Error(r)
	}
	if r := exec("fix", "nope.yaml"); r.code != 2 {
		t.Error(r)
	}
}

func TestModernize(t *testing.T) {
	dir := t.TempDir()
	out, rep, sarif := filepath.Join(dir, "w.yaml"), filepath.Join(dir, "r.md"), filepath.Join(dir, "r.sarif")
	r := exec("modernize", td("weather/weather-api-v2.yaml"), "-o", out, "--report", rep, "--sarif", sarif)
	md, _ := os.ReadFile(rep)
	sr, _ := os.ReadFile(sarif)
	if r.code != 0 || !strings.Contains(string(md), "migration report") || strings.Contains(string(md), dir) ||
		!strings.Contains(string(sr), `"results"`) || !strings.Contains(r.err, "problems") {
		t.Error(r)
	}
	strict := exec("modernize", td("weather/weather-service.wsdl"), "--no-fix", "--fail-on", "info", "--no-local-imports")
	if strict.code != 1 || !strings.HasPrefix(strict.out, "openapi: 3.1.0") {
		t.Error(strict)
	}
	if r := exec("modernize", td("weather/weather-service.wsdl"), "--sarif", sarif); r.code != 0 {
		t.Error(r)
	}
	if r := exec("modernize", td("security/xxe.wsdl")); r.code != 2 {
		t.Error(r)
	}
	bad := filepath.Join(dir, "no", "such", "x.yaml")
	if r := exec("modernize", td("weather/weather-api-v2.yaml"), "-o", bad); r.code != 2 || !strings.Contains(r.err, "error:") {
		t.Error(r)
	}
	if r := exec("modernize", td("weather/weather-api-v2.yaml"), "-o", out, "--report", bad); r.code != 2 {
		t.Error(r)
	}
	if r := exec("modernize", td("weather/weather-api-v2.yaml"), "-o", out, "--sarif", bad); r.code != 2 {
		t.Error(r)
	}
	cfg := filepath.Join(dir, "c.yaml")
	_ = os.WriteFile(cfg, []byte("fail-on: nope\n"), 0o600)
	if r := exec("modernize", td("weather/weather-api-v2.yaml"), "-c", cfg); r.code != 2 {
		t.Error(r)
	}
	if r := exec("modernize", "missing.yaml"); r.code != 2 {
		t.Error(r)
	}
	if _, err := BuildCase(td(""), "missing.yaml"); err == nil {
		t.Error("BuildCase missing")
	}
	if _, err := BuildCase(td(""), "security/xxe.wsdl"); err == nil {
		t.Error("BuildCase xxe")
	}
}

func TestServeCommand(t *testing.T) {
	var addr string
	listen = func(srv *http.Server) error { addr = srv.Addr; return http.ErrServerClosed }
	defer func() { listen = func(srv *http.Server) error { return srv.ListenAndServe() } }()
	if r := exec("serve", "--port", "9999", "--host", "0.0.0.0"); r.code != 0 || addr != "0.0.0.0:9999" ||
		!strings.Contains(r.err, "listening on") {
		t.Error(r, addr)
	}
	listen = func(*http.Server) error { return errors.New("port in use") }
	if r := exec("serve"); r.code != 2 || !strings.Contains(r.err, "port in use") {
		t.Error(r)
	}
	if r := exec("serve", "--port", "0"); r.code != 2 {
		t.Error(r)
	}
	t.Setenv("SPECLINT_MAX_BYTES", "2MB")
	if r := exec("serve"); r.code != 2 || !strings.Contains(r.err, "SPECLINT_MAX_BYTES") {
		t.Error(r)
	}
	t.Setenv("SPECLINT_MAX_BYTES", "")
	for _, cmd := range []string{"convert", "lint", "fix", "modernize", "rules", "serve"} {
		if r := exec(cmd, "--help"); r.code != 0 || !strings.Contains(r.out, "speclint "+cmd) || r.err != "" {
			t.Errorf("%s --help: %+v", cmd, r)
		}
	}
	if r := exec("serve", "--bogus"); r.code != 2 {
		t.Error(r)
	}
}
