# Contributing

Thanks for helping! spec-lint has **three implementations of one specification**, so most changes touch all of them.

## Setup

Prerequisites: JDK 21+, Python 3.10+, Go 1.22+ and git.

```bash
./gradlew check                    # everything: build, lint, type-check, tests, coverage >= 80%
pip install pre-commit && pre-commit install   # ruff, gitleaks and hygiene hooks
```

Per language:

| | Run tests | Lint |
|---|---|---|
| Python | `cd python && pip install -e '.[dev]' && pytest` | `ruff check . && ruff format --check . && mypy` |
| Java | `./gradlew :java:test` | compiles with `-Xlint:all -Werror` |
| Go | `cd go && go test -race ./...` | `go vet ./... && gofmt -l .` |

## Changing behavior (rules, conversions, fixes)

1. Implement the change in **Python** first, the reference implementation, with tests.
2. Regenerate the golden files: `python python/scripts/update_golden.py`.
3. Port the change to **Java** and **Go** until `./gradlew conformance` passes.
4. Update `docs/rules.md` and/or `docs/wsdl-mapping.md`, and `CHANGELOG.md`.

The conformance suite compares converted and fixed documents, the applied fixes and the lint findings across languages. Messages may differ in wording; rule IDs, paths and document content may not.

## Changing the web UI or the API contract

The UI lives in `/web` and the REST contract in `docs/api/openapi.yaml`. Java packages them at build time; Python and Go keep committed copies. After editing either, run:

```bash
./gradlew syncWebAssets
```

Tests in each language fail if a copy drifts. The contract must stay lint-clean: `speclint lint docs/api/openapi.yaml --fail-on info`.

## Adding a fixture

- Use fictional services on reserved domains (`example.com`, `example.org`).
- Never commit real credentials, internal hostnames or customer data.
- Third-party specs must be under a license that allows redistribution.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat(lint): add path-version-prefix rule`, `fix(wsdl): map nillable attributes`.
- Keep pull requests focused, and fill in the PR checklist.
- Every pull request must pass CI: `gradlew check`, the Python version matrix, Go on macOS and Windows, CodeQL, and gitleaks.

## Code of conduct

Be respectful and constructive. Harassment of any kind isn't tolerated.
