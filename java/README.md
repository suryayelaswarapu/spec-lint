# spec-lint (Java)

The Java 21 implementation of [spec-lint](../README.md), built with Gradle, picocli, SnakeYAML, Jackson and networknt json-schema-validator.

```bash
./gradlew :java:bootRun        # web service on http://localhost:8080 (SpecLintApplication)
./gradlew :java:check          # tests + JaCoCo gate (80% lines, 70% branches)
./gradlew :java:installDist    # CLI in java/build/install/speclint/bin/speclint
open java/build/reports/jacoco/test/html/index.html
```

| Package | Responsibility |
|---|---|
| `io` | `SpecIO`: safe reading/parsing, YAML/JSON output, line index |
| `convert` | `Swagger2Converter`, `OpenApi30Upgrader`, `WsdlConverter`, `XsdConverter`, `SafeXml` (OWASP XXE settings) |
| `lint` | `Rules`, `Linter`, `LintConfig`, `OasValidator` |
| `fix` | `Fixer` |
| `report` | `Reports` |
| `server` | Spring Boot web layer: `ApiController`, `SpecService`, `ProblemHandler` (RFC 9457), `BodyLimitFilter` (limits, token, security headers) |
| root | `SpecLintApplication` (web entry point), `Main` (CLI, picocli), `Modernizer`, `Conformance` |
