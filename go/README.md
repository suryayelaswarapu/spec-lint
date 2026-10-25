# spec-lint (Go)

The Go implementation of [spec-lint](../README.md). It requires Go 1.24+ and has two dependencies (`gopkg.in/yaml.v3` and `santhosh-tekuri/jsonschema/v6`).

```bash
go build -o speclint ./cmd/speclint
./speclint serve                    # web service on http://localhost:8080
go test -race -cover ./...          # coverage gate 80% enforced by Gradle (currently ~98%)
```

| Package | Responsibility |
|---|---|
| `internal/doc` | Insertion-ordered JSON model (`*doc.Map`), naming helpers, error kinds |
| `internal/specio` | Safe reading/parsing, YAML/JSON output, line index, detection |
| `internal/convert` | Swagger 2.0, OpenAPI 3.0, WSDL/XSD converters, safe XML tree |
| `internal/lint` | Rules, config, embedded official OAS schemas |
| `internal/fix` | Wire-safe auto-fixes |
| `internal/report` | Text / JSON / SARIF / Markdown |
| `internal/pipeline` | The modernize pipeline shared by the CLI and the server |
| `internal/server` | `net/http` REST API + embedded web UI |
| `internal/app` | CLI and the conformance builder |

Go maps are unordered, so the data model uses an ordered map. That keeps output key order identical to the Python and Java implementations.
