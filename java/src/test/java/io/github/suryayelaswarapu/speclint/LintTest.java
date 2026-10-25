package io.github.suryayelaswarapu.speclint;

import static io.github.suryayelaswarapu.speclint.Json.child;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.suryayelaswarapu.speclint.SpecLintException.ConfigException;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import io.github.suryayelaswarapu.speclint.lint.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LintTest {
    @TempDir
    Path tmp;

    static Set<String> rules(Map<String, Object> doc) {
        return Linter.lint(doc).stream().map(Finding::rule).collect(Collectors.toSet());
    }

    static Map<String, Object> op(Map<String, Object> d) {
        return map(map(map(d.get("paths")).get("/items/{itemId}")).get("get"));
    }

    static Map<String, Object> mutate(Consumer<Map<String, Object>> change) {
        Map<String, Object> doc = TestSupport.minimalOas();
        change.accept(doc);
        return doc;
    }

    @Test
    void minimalDocumentIsClean() {
        assertThat(Linter.lint(TestSupport.minimalOas())).isEmpty();
    }

    @Test
    void everyRuleIsDocumented() throws IOException {
        String catalog = Files.readString(TestSupport.testdata().resolve("../docs/rules.md"));
        for (String id : Rules.ALL.keySet()) {
            assertThat(catalog).contains("`" + id + "`");
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        out.add(Arguments.of("openapi-version-3-1",
                (Consumer<Map<String, Object>>) d -> d.put("openapi", "3.0.3")));
        out.add(Arguments.of("info-description",
                (Consumer<Map<String, Object>>) d -> map(d.get("info")).remove("description")));
        out.add(Arguments.of("info-contact",
                (Consumer<Map<String, Object>>) d -> map(d.get("info")).remove("contact")));
        out.add(Arguments.of("info-license",
                (Consumer<Map<String, Object>>) d -> map(d.get("info")).remove("license")));
        out.add(Arguments.of("info-version-semver",
                (Consumer<Map<String, Object>>) d -> map(d.get("info")).put("version", "v1")));
        out.add(Arguments.of("servers-defined",
                (Consumer<Map<String, Object>>) d -> d.remove("servers")));
        out.add(Arguments.of("servers-https", (Consumer<Map<String, Object>>) d ->
                d.put("servers", List.of(Json.obj("url", "http://api.example.com")))));
        out.add(Arguments.of("security-defined", (Consumer<Map<String, Object>>) d ->
                map(d.get("components")).remove("securitySchemes")));
        out.add(Arguments.of("security-defined",
                (Consumer<Map<String, Object>>) d -> d.remove("security")));
        out.add(Arguments.of("operation-id-required",
                (Consumer<Map<String, Object>>) d -> op(d).remove("operationId")));
        out.add(Arguments.of("operation-id-camel-case",
                (Consumer<Map<String, Object>>) d -> op(d).put("operationId", "Get_Item")));
        out.add(Arguments.of("operation-summary",
                (Consumer<Map<String, Object>>) d -> op(d).remove("summary")));
        out.add(Arguments.of("operation-tags",
                (Consumer<Map<String, Object>>) d -> op(d).remove("tags")));
        out.add(Arguments.of("operation-tag-defined",
                (Consumer<Map<String, Object>>) d -> d.remove("tags")));
        out.add(Arguments.of("operation-success-response",
                (Consumer<Map<String, Object>>) d -> map(op(d).get("responses")).remove("200")));
        out.add(Arguments.of("operation-error-response",
                (Consumer<Map<String, Object>>) d -> map(op(d).get("responses")).remove("404")));
        out.add(Arguments.of("error-response-schema", (Consumer<Map<String, Object>>) d ->
                map(op(d).get("responses")).put("500", Json.obj("description", "x"))));
        out.add(Arguments.of("parameter-description", (Consumer<Map<String, Object>>) d ->
                map(Json.list(op(d).get("parameters")).get(0)).remove("description")));
        out.add(Arguments.of("parameter-description", (Consumer<Map<String, Object>>) d ->
                map(d.get("components")).put("parameters",
                        Json.obj("P", Json.obj("name", "p", "in", "query")))));
        out.add(Arguments.of("path-kebab-case", (Consumer<Map<String, Object>>) d ->
                map(d.get("paths")).put("/Bad_Path", Json.obj())));
        out.add(Arguments.of("path-no-trailing-slash", (Consumer<Map<String, Object>>) d ->
                map(d.get("paths")).put("/items/", Json.obj())));
        out.add(Arguments.of("path-params-defined", (Consumer<Map<String, Object>>) d ->
                op(d).put("parameters", new ArrayList<>())));
        out.add(Arguments.of("path-params-defined", (Consumer<Map<String, Object>>) d ->
                Json.list(op(d).get("parameters")).add(Json.obj("name", "extra", "in", "path",
                        "required", true, "schema", Json.obj(), "description", "x"))));
        out.add(Arguments.of("schema-name-pascal-case", (Consumer<Map<String, Object>>) d ->
                child(map(d.get("components")), "schemas").put("lower", Json.obj())));
        out.add(Arguments.of("property-camel-case", (Consumer<Map<String, Object>>) d ->
                map(map(map(map(d.get("components")).get("schemas")).get("Item"))
                        .get("properties")).put("snake_case", Json.obj())));
        out.add(Arguments.of("no-inline-object-schemas", (Consumer<Map<String, Object>>) d ->
                map(map(map(map(op(d).get("responses")).get("200")).get("content"))
                        .get("application/json")).put("schema", Json.obj("type", "object"))));
        out.add(Arguments.of("no-legacy-nullable", (Consumer<Map<String, Object>>) d ->
                map(map(map(d.get("components")).get("schemas")).get("Item"))
                        .put("nullable", true)));
        out.add(Arguments.of("no-unused-components", (Consumer<Map<String, Object>>) d ->
                map(map(d.get("components")).get("schemas")).put("Unused", Json.obj())));
        out.add(Arguments.of("no-remote-refs", (Consumer<Map<String, Object>>) d ->
                map(op(d).get("responses")).put("201", Json.obj("$ref", "other.yaml#/x"))));
        out.add(Arguments.of("no-remote-refs", (Consumer<Map<String, Object>>) d ->
                map(op(d).get("responses")).put("201", Json.obj("$ref", "https://x/y#/z"))));
        out.add(Arguments.of("ref-resolves", (Consumer<Map<String, Object>>) d ->
                map(op(d).get("responses")).put("201", Json.obj("$ref", "#/nope"))));
        out.add(Arguments.of("oas3-valid-schema",
                (Consumer<Map<String, Object>>) d -> map(d.get("info")).remove("title")));
        out.add(Arguments.of("oas3-valid-schema", (Consumer<Map<String, Object>>) d -> {
            d.put("openapi", "3.0.3");
            map(d.get("info")).remove("title");
        }));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void ruleFires(String ruleId, Consumer<Map<String, Object>> change) {
        assertThat(rules(mutate(change))).contains(ruleId);
    }

    @Test
    void unusedComponentsHandlesEscapedAndNestedRefs() {
        Map<String, Object> doc = TestSupport.minimalOas();
        Map<String, Object> schemas = map(map(doc.get("components")).get("schemas"));
        schemas.put("a/b", new java.util.LinkedHashMap<String, Object>());
        map(map(schemas.get("Item")).get("properties")).put("x",
                Json.obj("$ref", "#/components/schemas/a~1b/properties"));
        assertThat(rules(doc)).doesNotContain("no-unused-components");
        schemas.put("Orphan", Json.obj("$ref", "#/components"));
        assertThat(Linter.lint(doc).stream().filter(f -> f.rule().equals("no-unused-components"))
                .map(Finding::path).toList()).containsExactly(
                        List.of("components", "schemas", "Orphan"));
    }

    @Test
    void operationIdUnique() {
        Map<String, Object> doc = mutate(d -> {
            Map<String, Object> copy = Json.copyMap(op(d));
            copy.put("parameters", new ArrayList<>());
            map(d.get("paths")).put("/other", Json.obj("get", copy));
        });
        assertThat(rules(doc)).contains("operation-id-unique");
    }

    @Test
    void toleratesJunk() {
        Map<String, Object> doc = SpecIO.parseStructured(
                "openapi: 3.1.0\npaths: {/a: junk, /b: {get: junk}}\ninfo: {}\n");
        assertThat(rules(doc)).contains("servers-defined");
        assertThat(rules(Json.obj("openapi", "3.1.0", "paths", List.of())))
                .contains("info-version-semver");
    }

    @Test
    void remoteRefSkipsValidator() {
        Map<String, Object> doc = SpecIO.parseStructured(SpecIO.readSource(
                TestSupport.testdata("security/remote-ref.yaml")).text());
        assertThat(rules(doc)).contains("no-remote-refs").doesNotContain("oas3-valid-schema");
    }

    @Test
    void validatorCapsErrors() {
        Map<String, Object> doc = TestSupport.minimalOas();
        Map<String, Object> paths = Json.obj();
        for (int i = 0; i < 30; i++) {
            paths.put("/p" + i, Json.obj("get", Json.obj("responses", "bad")));
        }
        doc.put("paths", paths);
        long count = Linter.lint(doc).stream()
                .filter(f -> f.rule().equals("oas3-valid-schema")).count();
        assertThat(count).isBetween(1L, 20L);
    }

    @Test
    void linesAndSummary() {
        Map<String, Object> doc = mutate(d -> map(d.get("info")).remove("contact"));
        List<Finding> findings = Linter.lint(doc, null, Map.of(List.of("info"), 3));
        assertThat(findings.get(0).line()).isEqualTo(3);
        assertThat(findings.get(0).toMap()).containsEntry("line", 3);
        assertThat(Linter.summarize(findings)).containsEntry("warning", 1);
    }

    @Test
    void configOverridesAndStrict() throws IOException {
        Files.writeString(tmp.resolve(".speclint.yaml"), "extends: strict\nfail-on: warning\n"
                + "rules:\n  info-contact: off\n  info-license: error\n");
        LintConfig config = LintConfig.load(null, tmp);
        assertThat(config.severity("info-contact")).isEqualTo(Severity.OFF);
        assertThat(config.severity("info-license")).isEqualTo(Severity.ERROR);
        assertThat(config.severity("operation-summary")).isEqualTo(Severity.ERROR);
        assertThat(config.severity("parameter-description")).isEqualTo(Severity.WARNING);
        assertThat(config.severity("servers-defined")).isEqualTo(Severity.ERROR);
        assertThat(config.fails(Severity.WARNING)).isTrue();
        assertThat(config.fails(Severity.INFO)).isFalse();
        Map<String, Object> doc = mutate(d -> {
            map(d.get("info")).remove("contact");
            map(d.get("info")).remove("license");
        });
        assertThat(Linter.lint(doc, config, null)).extracting(Finding::rule)
                .containsExactly("info-license");
        assertThat(new LintConfig("recommended", "never", Map.of()).fails(Severity.ERROR))
                .isFalse();
        assertThat(LintConfig.load(null, tmp.resolve("nowhere")).failOn()).isEqualTo("error");
        assertThatThrownBy(() -> Severity.parse("loud"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"extends: loose\n", "fail-on: sometimes\n", "rules: [a]\n",
        "rules:\n  not-a-rule: off\n", "rules:\n  info-contact: loud\n", "- not a mapping\n"})
    void configErrors(String content) throws IOException {
        Path cfg = tmp.resolve("c.yaml");
        Files.writeString(cfg, content);
        assertThatThrownBy(() -> LintConfig.load(cfg, null)).isInstanceOf(ConfigException.class);
    }
}
