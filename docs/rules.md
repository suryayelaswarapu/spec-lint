# Rule catalog

This is the shared rulebook for every spec-lint implementation (Python, Java, Go). Rule IDs, default severities and auto-fix behavior are identical in all three, and the conformance suite in [`testdata/conformance`](../testdata/conformance) enforces that.

**Severity** is the default under `extends: recommended`. `extends: strict` raises every `warning` to `error` and every `info` to `warning`. Any rule can be overridden or turned `off` in `.speclint.yaml`.

**Fix** means `speclint fix` / `speclint modernize` can repair it automatically. Auto-fixes never change the wire contract: paths, parameter names and property names are never renamed.

| Rule | Severity | Fix | What it enforces |
|---|---|---|---|
| `oas3-valid-schema` | error | | The document is structurally valid OpenAPI 3.x. |
| `no-remote-refs` | error | | `$ref` stays inside the document. Remote URLs and external files are never fetched. |
| `ref-resolves` | error | | Every local `$ref` points to something that exists. |
| `openapi-version-3-1` | warning | | Targets OpenAPI 3.1 (JSON Schema 2020-12). |
| `info-description` | warning | | `info.description` says what the API is for. |
| `info-contact` | warning | | `info.contact` names the owning team. |
| `info-license` | info | | `info.license` states the terms of use. |
| `info-version-semver` | warning | | `info.version` is `MAJOR.MINOR.PATCH`. |
| `servers-defined` | error | | At least one server is declared. |
| `servers-https` | warning | | Absolute server URLs use `https` (localhost excepted). |
| `security-defined` | warning | | Security schemes exist and every operation is covered. |
| `operation-id-required` | error | ✔ | Every operation has an `operationId`. The fix derives one from the method and path, for example `getStationsById`. |
| `operation-id-unique` | error | | `operationId`s are unique. |
| `operation-id-camel-case` | warning | ✔ | `operationId`s are camelCase. Renaming one changes generated SDK method names, not the wire. |
| `operation-summary` | warning | | Every operation has a summary. |
| `operation-tags` | warning | ✔ | Every operation has a tag. The fix uses the first static path segment, skipping version segments like `v1`. |
| `operation-tag-defined` | warning | ✔ | Tags used by operations are declared at the top level. |
| `operation-success-response` | error | | Every operation has a 2xx/3xx response. |
| `operation-error-response` | warning | ✔ | Every operation documents errors. The fix adds `default` → `#/components/responses/Problem`. |
| `error-response-schema` | warning | ✔ | 4xx/5xx/default responses have a body schema. The fix uses RFC 9457 Problem Details. |
| `parameter-description` | info | | Parameters are described. |
| `path-kebab-case` | warning | | Static path segments are `lower-kebab-case`. |
| `path-no-trailing-slash` | warning | | Paths do not end in `/`. |
| `path-params-defined` | error | | `{templates}` and `in: path` parameters match one-to-one. |
| `schema-name-pascal-case` | warning | ✔ | `components.schemas` keys are PascalCase. The fix renames them and rewrites every `$ref`. |
| `property-camel-case` | warning | | Schema property names are camelCase. |
| `no-inline-object-schemas` | warning | ✔ | Request and response bodies `$ref` named schemas. The fix extracts them to `<OperationId>Request` / `<OperationId>Response`. |
| `no-legacy-nullable` | warning | ✔ | Uses `type: [T, "null"]` instead of `nullable` / `x-nullable`. |
| `no-unused-components` | warning | | Every component is referenced. |

## Why these rules

The ruleset is opinionated toward what API governance teams usually standardize first:

- **Consistency for consumers:** casing rules, kebab-case paths, a shared error format (RFC 9457).
- **Good tooling:** `operationId`s and named schemas give clean SDK method and class names.
- **Safety:** no remote `$ref`s, https servers, declared security.

Contract-breaking changes (renaming paths or properties) are reported but never auto-fixed. Those need a versioning decision from a person.

## Configuration

```yaml
# .speclint.yaml
extends: recommended   # or: strict
fail-on: error         # error | warning | info | never  (exit code 1 threshold)
rules:
  property-camel-case: off   # e.g. a legacy snake_case API you cannot change
  info-contact: error
```
