# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private vulnerability reporting instead: **Security → Report a vulnerability** on this repository. You can expect an acknowledgement within 7 days.

Please include the implementation (Python, Java or Go), the version (`speclint --version`), and a minimal input that reproduces the problem. Strip anything confidential from the input first.

## Supported versions

Only the latest release on `main` receives security fixes.

## Threat model

spec-lint processes **untrusted API descriptions**. Its guarantees:

- **No network access.** Remote `$ref`s and `xsd:import`s are never fetched.
- **No reads outside the input's folder.** Local XSD imports that escape it are rejected.
- **No XML DTDs or entities**, which blocks XXE and entity-expansion attacks.
- **Bounded work.** There are limits on input size, YAML aliases, XSD nesting depth and the number of imported documents.
- **No code execution.** YAML is parsed with safe loaders only.
- **Web service:** request bodies are limited (2 MiB by default, including chunked uploads), and an optional bearer token protects the API. Errors never include stack traces or the submitted content. Responses carry a strict Content Security Policy. Servers bind to `127.0.0.1` unless configured otherwise.

For an internet-facing deployment, put the service behind a reverse proxy that adds TLS and rate limiting, and set `SPECLINT_API_TOKEN`.

The README's [Security design](README.md#security-design) section maps each threat to its mitigation and test fixture. A bypass of any of these guarantees counts as a vulnerability.

## Keeping secrets out of the repository

- `gitleaks` runs in the pre-commit hook and in CI.
- `.gitignore` excludes `.env*`, private keys and keystores.
- Test fixtures use reserved documentation domains (`example.com`, RFC 2606) only.
