package server

import (
	"bytes"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// Copies of /web and /docs/api/openapi.yaml (kept identical by a test).
//
//go:embed static
var staticFS embed.FS

// DefaultMaxBytes is the default request body limit (2 MiB).
const DefaultMaxBytes int64 = 2 * 1024 * 1024

const csp = "default-src 'self'; script-src 'self' https://unpkg.com/swagger-ui-dist@5.17.14/; " +
	"style-src 'self' 'unsafe-inline' https://unpkg.com/swagger-ui-dist@5.17.14/; img-src 'self' data:; " +
	"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

var staticFiles = map[string][2]string{
	"/":                    {"index.html", "text/html; charset=utf-8"},
	"/index.html":          {"index.html", "text/html; charset=utf-8"},
	"/app.js":              {"app.js", "text/javascript; charset=utf-8"},
	"/styles.css":          {"styles.css", "text/css; charset=utf-8"},
	"/docs.html":           {"docs.html", "text/html; charset=utf-8"},
	"/docs.js":             {"docs.js", "text/javascript; charset=utf-8"},
	"/api/v1/openapi.yaml": {"openapi.yaml", "application/yaml; charset=utf-8"},
}

// Settings configure the server.
type Settings struct {
	MaxBytes int64
	APIToken string
}

// SettingsFromEnv reads SPECLINT_MAX_BYTES and SPECLINT_API_TOKEN. A malformed
// SPECLINT_MAX_BYTES is an error rather than being silently replaced by the default.
func SettingsFromEnv() (Settings, error) {
	s := Settings{MaxBytes: DefaultMaxBytes, APIToken: os.Getenv("SPECLINT_API_TOKEN")}
	if raw := strings.TrimSpace(os.Getenv("SPECLINT_MAX_BYTES")); raw != "" {
		v, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || v <= 0 {
			return s, fmt.Errorf("SPECLINT_MAX_BYTES must be a positive whole number of bytes, got %q", raw)
		}
		s.MaxBytes = v
	}
	return s, nil
}

func writeJSON(w http.ResponseWriter, status int, contentType string, body any) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(body)
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_, _ = w.Write(buf.Bytes())
}

func problem(w http.ResponseWriter, status int, title, detail string) {
	body := doc.M("type", "about:blank", "title", title, "status", int64(status))
	if detail != "" {
		body.Set("detail", detail)
	}
	writeJSON(w, status, "application/problem+json", body)
}

func errorResponse(w http.ResponseWriter, err error) {
	var e *doc.Error
	switch {
	case errors.As(err, &e) && e.Kind == doc.ErrSecurity:
		problem(w, http.StatusUnprocessableEntity, "Unsafe input rejected", e.Msg)
	case errors.As(err, &e):
		problem(w, http.StatusBadRequest, "Invalid input", e.Msg)
	default:
		log.Printf("unhandled error: %v", err) // the submitted contract is never logged
		problem(w, http.StatusInternalServerError, "Internal error", "Unexpected error")
	}
}

type handlerFunc func(*doc.Map) (*doc.Map, error)

func (s Settings) api(fn handlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.APIToken != "" {
			expected := []byte("Bearer " + s.APIToken)
			if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), expected) != 1 {
				w.Header().Set("WWW-Authenticate", "Bearer")
				problem(w, http.StatusUnauthorized, "Unauthorized", "A valid bearer token is required")
				return
			}
		}
		if r.ContentLength > s.MaxBytes {
			problem(w, http.StatusRequestEntityTooLarge, "Payload too large",
				"Request body exceeds "+strconv.FormatInt(s.MaxBytes, 10)+" bytes")
			return
		}
		raw, err := readAll(http.MaxBytesReader(w, r.Body, s.MaxBytes))
		if err != nil {
			var tooBig *http.MaxBytesError
			if errors.As(err, &tooBig) {
				problem(w, http.StatusRequestEntityTooLarge, "Payload too large",
					"Request body exceeds "+strconv.FormatInt(s.MaxBytes, 10)+" bytes")
				return
			}
			problem(w, http.StatusBadRequest, "Invalid request", "Could not read the request body")
			return
		}
		body, err := parseBody(raw)
		if err != nil {
			problem(w, http.StatusBadRequest, "Invalid request", "Request body must be a JSON object with 'spec'")
			return
		}
		out, err := fn(body)
		if err != nil {
			errorResponse(w, err)
			return
		}
		writeJSON(w, http.StatusOK, "application/json", out)
	}
}

func readAll(r interface{ Read([]byte) (int, error) }) ([]byte, error) {
	var buf bytes.Buffer
	_, err := buf.ReadFrom(r)
	return buf.Bytes(), err
}

// parseBody decodes a JSON object into the ordered data model (JSON is valid YAML).
func parseBody(raw []byte) (*doc.Map, error) {
	if !json.Valid(raw) {
		return nil, errors.New("invalid JSON")
	}
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return nil, errors.New("not an object")
	}
	return specio.ParseStructured(string(raw), "request")
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

// Handler builds the HTTP handler (routes + middleware).
func Handler(s Settings) http.Handler {
	if s.MaxBytes <= 0 {
		s.MaxBytes = DefaultMaxBytes
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, "application/json", Health())
	})
	mux.HandleFunc("GET /api/v1/rules", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, "application/json", Rules())
	})
	mux.HandleFunc("POST /api/v1/convert", s.api(Convert))
	mux.HandleFunc("POST /api/v1/lint", s.api(Lint))
	mux.HandleFunc("POST /api/v1/fix", s.api(Fix))
	mux.HandleFunc("POST /api/v1/modernize", s.api(Modernize))
	for route, file := range staticFiles {
		content, err := staticFS.ReadFile("static/" + file[0])
		if err != nil {
			panic(err) // embedded at build time; cannot happen
		}
		contentType := file[1]
		pattern := "GET " + route
		if route == "/" {
			pattern = "GET /{$}"
		}
		mux.HandleFunc(pattern, func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", contentType)
			_, _ = w.Write(content)
		})
	}
	// Anything else: Problem Details instead of the default text responses.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		problem(w, http.StatusNotFound, "Not found", "")
	})
	for _, p := range []string{"/api/v1/convert", "/api/v1/lint", "/api/v1/fix", "/api/v1/modernize"} {
		mux.HandleFunc(p, func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Allow", "POST")
			problem(w, http.StatusMethodNotAllowed, "Method not allowed", "")
		})
	}
	return securityHeaders(mux)
}

// NewServer returns an *http.Server with conservative timeouts.
func NewServer(addr string, s Settings) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           Handler(s),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
	}
}
