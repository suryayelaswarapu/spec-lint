package io.github.suryayelaswarapu.speclint;

import static io.github.suryayelaswarapu.speclint.ConvertTest.at;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.suryayelaswarapu.speclint.fix.Fixer;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import io.github.suryayelaswarapu.speclint.lint.Severity;
import io.github.suryayelaswarapu.speclint.report.Reports;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FixReportTest {
    static Map<String, Object> messy() {
        return SpecIO.parseStructured("""
                openapi: 3.1.0
                components:
                  schemas:
                    old_name: {type: object, nullable: true}
                    Keep: {$ref: '#/components/schemas/old_name/properties'}
                info: {title: x, version: 1.0.0}
                paths:
                  /v1/Stations/{id}:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json: {schema: {type: object}}
                            application/xml: {schema: {type: object}}
                        '400': {description: bad}
                        '401': {$ref: '#/components/responses/X'}
                    post:
                      operationId: Create_Station
                      requestBody:
                        content:
                          application/json: {schema: {properties: {a: {}}}}
                          text/plain: {schema: {type: string}}
                          application/x: {schema: {$ref: '#/components/schemas/old_name'}}
                          bad: x
                      responses: {'201': {description: c}}
                    put: {operationId: create_station, responses: {}}
                  /{only}:
                    delete:
                      responses: {'404': {description: nf, content: {a/b: {}}}}
                """);
    }

    @Test
    void appliesAllFixes() {
        Fixer.Result result = Fixer.apply(messy());
        Map<String, Object> fixed = result.document();
        Set<String> rules = result.applied().stream().map(Fixer.AppliedFix::rule)
                .collect(Collectors.toSet());
        assertThat(rules).containsExactlyInAnyOrder("no-legacy-nullable",
                "operation-id-required", "operation-id-camel-case", "operation-tags",
                "operation-tag-defined", "schema-name-pascal-case", "no-inline-object-schemas",
                "error-response-schema", "operation-error-response");
        assertThat(List.copyOf(fixed.keySet()).subList(0, 4))
                .containsExactly("openapi", "info", "tags", "paths");
        Map<String, Object> item = map(at(fixed, "paths", "/v1/Stations/{id}"));
        assertThat(at(item, "get", "operationId")).isEqualTo("getV1StationsById");
        assertThat(at(item, "get", "tags")).isEqualTo(List.of("stations"));
        assertThat(at(item, "put", "operationId")).isEqualTo("createStation");
        assertThat(at(item, "post", "operationId")).isEqualTo("createStation2");
        assertThat(at(fixed, "paths", "/{only}", "delete", "tags")).isEqualTo(List.of("default"));
        Map<String, Object> schemas = map(at(fixed, "components", "schemas"));
        assertThat(schemas).containsKey("OldName").doesNotContainKey("old_name");
        assertThat(schemas.get("Keep"))
                .isEqualTo(Json.obj("$ref", "#/components/schemas/OldName/properties"));
        assertThat(at(item, "get", "responses", "200", "content", "application/json", "schema"))
                .isEqualTo(at(item, "get", "responses", "200", "content", "application/xml",
                        "schema"));
        assertThat(map(at(fixed, "paths", "/{only}", "delete", "responses", "404", "content")))
                .containsKeys("a/b", "application/problem+json");
        List<Finding> leftovers = Linter.lint(fixed).stream()
                .filter(f -> rules.contains(f.rule())).toList();
        assertThat(leftovers).extracting(f -> f.rule() + "@" + f.path().get(f.path().size() - 1))
                .containsExactly("error-response-schema@401");
    }

    @Test
    void respectsDisabledRulesAndCleanDocs() {
        Map<String, Severity> off = new HashMap<>();
        Rules.ALL.keySet().forEach(id -> off.put(id, Severity.OFF));
        Fixer.Result r = Fixer.apply(messy(), new LintConfig("recommended", "error", off));
        assertThat(r.applied()).isEmpty();
        assertThat(map(at(r.document(), "components", "schemas"))).containsKey("old_name");
        Fixer.Result clean = Fixer.apply(TestSupport.minimalOas());
        assertThat(clean.applied()).isEmpty();
        assertThat(clean.document()).isEqualTo(TestSupport.minimalOas());
        assertThat(Fixer.apply(Json.obj("openapi", "3.1.0", "paths", Json.obj())).document())
                .doesNotContainKey("tags");
    }

    static final List<Finding> FINDINGS = List.of(
            new Finding("info-contact", Severity.WARNING, "info.contact is missing",
                    List.of("info"), 2),
            new Finding("ref-resolves", Severity.ERROR, "bad | ref", List.of("paths", "/a"), null),
            new Finding("info-license", Severity.INFO, "no license", List.of(), null));

    @Test
    void textReport() {
        String text = Reports.text(FINDINGS, "api.yaml");
        assertThat(text).contains("api.yaml:2  warning")
                .contains("3 problems (1 errors, 1 warnings, 1 info)");
        assertThat(Reports.text(List.of(), "api.yaml")).contains("no problems found");
        assertThat(Reports.text(FINDINGS.subList(0, 1), "a")).contains("1 problem (");
    }

    @Test
    void jsonAndSarifReports() throws Exception {
        JsonNode json = TestSupport.MAPPER.readTree(Reports.json(FINDINGS, "api.yaml"));
        assertThat(json.at("/summary/error").asInt()).isEqualTo(1);
        assertThat(json.at("/findings/1/path").asText()).isEqualTo("/paths/~1a");
        JsonNode sarif = TestSupport.MAPPER.readTree(Reports.sarif(FINDINGS, "api.yaml"));
        assertThat(sarif.at("/version").asText()).isEqualTo("2.1.0");
        assertThat(sarif.at("/runs/0/tool/driver/rules/0/id").asText()).isEqualTo("info-contact");
        assertThat(sarif.at("/runs/0/results/0/locations/0/physicalLocation/region/startLine")
                .asInt()).isEqualTo(2);
        assertThat(sarif.at("/runs/0/results/2/level").asText()).isEqualTo("note");
    }

    @Test
    void markdownReport() {
        String md = Reports.markdown("in.wsdl", "WSDL 1.1", "out.yaml", List.of("w|1"),
                List.of(new Fixer.AppliedFix("operation-tags", "Tagged x", "/paths")), FINDINGS);
        assertThat(md).contains("bad \\| ref").contains("w\\|1").contains("operation-tags");
        String clean = Reports.markdown("a", "b", "c", List.of(), List.of(), List.of());
        assertThat(clean).contains("meets the standard").contains("_None._");
    }
}
