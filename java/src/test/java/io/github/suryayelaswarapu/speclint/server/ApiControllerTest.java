package io.github.suryayelaswarapu.speclint.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suryayelaswarapu.speclint.TestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP API tests; the same scenarios run against the Python and Go servers. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    static String read(String rel) throws Exception {
        return Files.readString(TestSupport.testdata(rel));
    }

    MockHttpServletResponse call(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn().getResponse();
    }

    static JsonNode json(MockHttpServletResponse r) throws Exception {
        return MAPPER.readTree(r.getContentAsString());
    }

    @Test
    void healthRulesHeadersAndStatic() throws Exception {
        MockHttpServletResponse health = mvc.perform(get("/health")).andReturn().getResponse();
        assertThat(json(health).get("implementation").asText()).isEqualTo("java");
        assertThat(health.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(health.getHeader("Content-Security-Policy")).contains("default-src 'self'");
        JsonNode rules = json(mvc.perform(get("/api/v1/rules")).andReturn().getResponse());
        assertThat(rules.get("rules")).hasSize(29);
        for (String path : List.of("/", "/index.html", "/app.js", "/styles.css", "/docs.html",
                "/docs.js", "/api/v1/openapi.yaml")) {
            MockHttpServletResponse r = mvc.perform(get(path)).andReturn().getResponse();
            if (r.getForwardedUrl() != null) { // "/" forwards to the welcome page
                r = mvc.perform(get("/" + r.getForwardedUrl().replaceFirst("^/", ""))).andReturn().getResponse();
            }
            assertThat(r.getStatus()).as(path).isEqualTo(200);
            assertThat(r.getContentAsByteArray()).as(path).isNotEmpty();
        }
        MockHttpServletResponse missing = mvc.perform(get("/nope")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(json(missing).get("title").asText()).isEqualTo("Not found");
        assertThat(mvc.perform(get("/api/v1/convert")).andReturn().getResponse().getStatus())
                .isEqualTo(405);
    }

    @ParameterizedTest
    @ValueSource(strings = {"weather-api-v2", "weather-service", "legacy-forecast-rpc",
        "weather-api-v3.0"})
    void modernizeMatchesConformance(String name) throws Exception {
        JsonNode golden = MAPPER.readTree(
                Files.readString(TestSupport.testdata("conformance/" + name + ".json")));
        MockHttpServletResponse r = call("/api/v1/modernize",
                Map.of("spec", read(golden.get("input").asText()), "filename", name));
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(TestSupport.normalize(MAPPER.convertValue(body.get("openapi"), Map.class)))
                .isEqualTo(TestSupport.normalize(
                        MAPPER.convertValue(golden.get("modernized"), Map.class)));
        assertThat(body.get("sourceKind").asText()).isEqualTo(golden.get("sourceKind").asText());
        assertThat(body.get("warnings")).hasSize(golden.get("warnings").asInt());
        assertThat(body.get("report").asText()).contains("# spec-lint migration report");
        assertThat(body.at("/summary/error").asInt()).isZero();
    }

    @Test
    void convertLintFix() throws Exception {
        assertThat(json(call("/api/v1/convert", Map.of("spec", read("weather/weather-api-v2.yaml"))))
                .get("sourceKind").asText()).isEqualTo("swagger-2.0");
        String spec = read("openapi/weather-api-v3.0.yaml");
        JsonNode lint = json(call("/api/v1/lint", Map.of("spec", spec)));
        Set<String> rules = new HashSet<>();
        lint.get("findings").forEach(f -> {
            rules.add(f.get("rule").asText());
            assertThat(f.has("line")).isTrue();
        });
        assertThat(rules).containsExactlyInAnyOrder("openapi-version-3-1", "no-legacy-nullable");
        JsonNode strict = json(call("/api/v1/lint",
                Map.of("spec", spec, "config", Map.of("extends", "strict"))));
        assertThat(strict.at("/summary/error").asInt()).isEqualTo(2);
        JsonNode fixed = json(call("/api/v1/fix", Map.of("spec", spec)));
        assertThat(fixed.get("fixes")).hasSize(1);
        assertThat(fixed.get("yaml").asText()).doesNotContain("nullable");
        assertThat(json(call("/api/v1/modernize", Map.of("spec", spec, "fix", false)))
                .get("fixes")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            /api/v1/lint      | {"spec": "<definitions/>"}                                    | WSDL
            /api/v1/fix       | {"spec": "swagger: '2.0'\\npaths: {}\\n"}                    | Swagger 2.0
            /api/v1/convert   | {"spec": ""}                                                  | spec
            /api/v1/convert   | {"spec": 5}                                                   | spec
            /api/v1/convert   | {"spec": "title: x"}                                          | Not an OpenAPI
            /api/v1/lint      | {"spec": "openapi: 3.1.0", "config": "x"}                     | config
            /api/v1/lint      | {"spec": "openapi: 3.1.0", "config": {"extends": "loose"}}    | extends
            /api/v1/modernize | {"spec": "openapi: 3.1.0", "fix": "yes"}                      | fix
            """)
    void badInput(String path, String body, String fragment) throws Exception {
        MockHttpServletResponse r = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(json(r).get("detail").asText()).contains(fragment);
    }

    @Test
    void malformedRequests() throws Exception {
        for (String raw : List.of("{not json", "[\"a\"]", "")) {
            assertThat(mvc.perform(post("/api/v1/convert").contentType(MediaType.APPLICATION_JSON)
                    .content(raw)).andReturn().getResponse().getStatus()).as(raw).isEqualTo(400);
        }
        assertThat(mvc.perform(post("/api/v1/convert").contentType(MediaType.TEXT_PLAIN)
                .content("x")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xxe.wsdl", "billion-laughs.wsdl", "yaml-aliases.yaml"})
    void unsafeInputRejected(String name) throws Exception {
        MockHttpServletResponse r = call("/api/v1/convert", Map.of("spec", read("security/" + name)));
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(json(r).get("title").asText()).isEqualTo("Unsafe input rejected");
    }

    @Test
    void neverReadsLocalImports() throws Exception {
        MockHttpServletResponse r = call("/api/v1/convert",
                Map.of("spec", read("security/path-traversal.wsdl")));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("detail").asText()).contains("portType");
    }

    @Test
    void safeFilename() {
        Map<Object, String> cases = new LinkedHashMap<>();
        cases.put("../../etc/passwd", "passwd");
        cases.put("C:\\x\\api.wsdl", "api.wsdl");
        cases.put("a<b>.yaml", "a_b_.yaml");
        cases.put("x".repeat(300), "x".repeat(200));
        cases.put("   ", "spec");
        cases.forEach((in, want) -> assertThat(SpecService.safeFilename(in)).isEqualTo(want));
        assertThat(SpecService.safeFilename(null)).isEqualTo("spec");
    }

    @Test
    void staticAssetsComeFromSharedSources() throws Exception {
        Path root = TestSupport.testdata().resolve("..").normalize();
        byte[] served = mvc.perform(get("/app.js")).andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(Files.readAllBytes(root.resolve("web/app.js")));
        byte[] contract = mvc.perform(get("/api/v1/openapi.yaml")).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(contract).isEqualTo(Files.readAllBytes(root.resolve("docs/api/openapi.yaml")));
    }
}
