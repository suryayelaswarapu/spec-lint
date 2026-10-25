# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Web service in all three implementations: Spring Boot (`SpecLintApplication`), FastAPI and Go `net/http`, sharing one OpenAPI 3.1 contract (`docs/api/openapi.yaml`) and one browser UI (`/web`).
- `speclint serve`, `./gradlew :java:bootRun`, `:python:serve` and `:go:serve`.
- Per-language Dockerfiles and `docker-compose.yml` to run the three servers side by side.
- Server hardening: body limit (including chunked uploads), optional bearer token, RFC 9457 errors, CSP and other security headers, no file-system access.
- Swagger UI on `/docs.html` is pinned with Subresource Integrity, and the CSP allows only that versioned path.
- `./gradlew :java:bootRun -Pport=…`, matching `:python:serve` and `:go:serve`.
- Tests that keep the release version identical across all implementations.
- Branch protection as code: `.github/rulesets/main.json` and `scripts/protect-main.sh`.

### Changed
- Python validates against the vendored OAS schemas with `jsonschema` + `referencing` (pre-crawled registry) instead of `openapi-spec-validator`.
- The FastAPI handlers run in a worker thread, so a large request never blocks the event loop.
- The Java WSDL import limits are constructor parameters rather than mutable static fields.

### Fixed
- `no-unused-components` was quadratic. On a 1.3 MB contract, Python `modernize` went from 149 s to 10 s, Java from 8 s to 5 s, and Go from 4.6 s to 2.2 s.
- A malformed `SPECLINT_MAX_BYTES` (for example `2MB`) crashed the Python server with a traceback and was silently ignored by Go. All three now refuse to start with a clear message.
- The Python server buffered the request body before checking the bearer token. Unauthenticated requests are now rejected before the body is read.
- `speclint <command> --help` in the Go CLI printed `Error: flag: help requested` and exited 2. It now prints the command's options.
- `:python:install` did not rerun after `python/.venv` was deleted.

## [0.1.0] - 2026-10-22

### Added
- `modernize`, `convert`, `lint`, `fix` and `rules` commands, implemented identically in Python, Java and Go.
- WSDL 1.1 (document/literal, rpc, rpc/encoded; SOAP 1.1/1.2) → OpenAPI 3.1 JSON facade with full XSD → JSON Schema 2020-12 mapping.
- Swagger 2.0 → OpenAPI 3.1 and OpenAPI 3.0 → 3.1 conversion.
- 29 lint rules with `recommended` and `strict` profiles, `.speclint.yaml` overrides and `fail-on` thresholds.
- Wire-safe auto-fixes, including RFC 9457 Problem Details error responses.
- Text, JSON, SARIF 2.1.0 and Markdown reports.
- Cross-language conformance suite and weather-service example fixtures.
- Security hardening: no DTDs, no network fetches, path-traversal guard, size, alias and depth limits.
- Single Gradle build with coverage gates (≥ 80%), GitHub Actions CI, CodeQL, gitleaks and Dependabot.
