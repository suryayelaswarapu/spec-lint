package io.github.suryayelaswarapu.speclint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import io.github.suryayelaswarapu.speclint.SpecLintException.UnsupportedSpecException;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IoAndDetectTest {
    @TempDir
    Path tmp;

    @Test
    void yamlKeepsDatesAsStringsAndKeysAsStrings() {
        Map<String, Object> doc = SpecIO.parseStructured(
                "openapi: 3.1.0\ninfo: {version: 2024-01-01}\nresponses: {200: ok}\n");
        assertThat(Json.map(doc.get("info")).get("version")).isEqualTo("2024-01-01");
        assertThat(Json.map(doc.get("responses"))).containsKey("200");
    }

    @Test
    void parsesJson() {
        assertThat(SpecIO.parseStructured("{\"swagger\": \"2.0\"}")).containsEntry("swagger", "2.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"- a\n- b\n", "just a string", "{bad json", "a: [unclosed\n"})
    void rejectsGarbage(String text) {
        assertThatThrownBy(() -> SpecIO.parseStructured(text)).isInstanceOf(InputException.class);
    }

    @Test
    void rejectsAliasBomb() {
        SourceFile src = SpecIO.readSource(TestSupport.testdata("security/yaml-aliases.yaml"));
        assertThatThrownBy(() -> SpecIO.parseStructured(src.text()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("aliases");
    }

    @Test
    void rejectsJavaTypeTags() {
        assertThatThrownBy(() -> SpecIO.parseStructured("a: !!java.io.File [x]\n"))
                .isInstanceOf(InputException.class);
    }

    @Test
    void readSourceLimitsAndErrors() throws IOException {
        Path big = tmp.resolve("big.yaml");
        Files.writeString(big, "a: " + "x".repeat(100));
        assertThatThrownBy(() -> SpecIO.readSource(big, 10))
                .isInstanceOf(SecurityException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> SpecIO.readSource(tmp.resolve("nope.yaml")))
                .isInstanceOf(InputException.class).hasMessageContaining("not found");
        Path bin = tmp.resolve("b.yaml");
        Files.write(bin, new byte[] {(byte) 0xff, (byte) 0xfe, 0, 'x'});
        assertThatThrownBy(() -> SpecIO.readSource(bin))
                .isInstanceOf(InputException.class).hasMessageContaining("UTF-8");
        Path bom = tmp.resolve("bom.yaml");
        Files.writeString(bom, "\uFEFFa: 1\n");
        assertThat(SpecIO.readSource(bom).text()).isEqualTo("a: 1\n");
        assertThat(SpecIO.readSource(bom).name()).isEqualTo("bom.yaml");
    }

    @Test
    void dumpRoundTrips() {
        Map<String, Object> doc = Json.obj("openapi", "3.1.0", "info",
                Json.obj("description", "line1\nline2"), "list", List.of(1L, 2L));
        String yaml = SpecIO.dump(doc, "yaml");
        assertThat(yaml).contains("|");
        assertThat(TestSupport.normalize(SpecIO.parseStructured(yaml)))
                .isEqualTo(TestSupport.normalize(doc));
        String json = SpecIO.dump(doc, "json");
        assertThat(json).contains("\"openapi\": \"3.1.0\"");
        assertThat(TestSupport.normalize(SpecIO.parseStructured(json)))
                .isEqualTo(TestSupport.normalize(doc));
        assertThatThrownBy(() -> SpecIO.dump(doc, "xml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dumpNeverEmitsAnchors() {
        Map<String, Object> shared = Json.obj("type", "string");
        String yaml = SpecIO.dump(Json.obj("a", shared, "b", shared), "yaml");
        assertThat(yaml).doesNotContain("&").doesNotContain("*");
    }

    @Test
    void formatForPath() {
        assertThat(SpecIO.formatForPath("a.JSON")).isEqualTo("json");
        assertThat(SpecIO.formatForPath("a.yml")).isEqualTo("yaml");
        assertThat(SpecIO.formatForPath(null)).isEqualTo("yaml");
    }

    @Test
    void lineIndex() {
        Map<List<String>, Integer> index = SpecIO.lineIndex(
                "openapi: 3.1.0\npaths:\n  /a:\n    get: {}\nlist:\n  - x\n");
        assertThat(index.get(List.of("paths", "/a", "get"))).isEqualTo(4);
        assertThat(index.get(List.of("list", "0"))).isEqualTo(6);
        assertThat(SpecIO.lineIndex("")).isEmpty();
        assertThat(SpecIO.lineIndex("a: [")).isEmpty();
    }

    @Test
    void detectDocument() {
        assertThat(SpecKind.detectDocument(Json.obj("swagger", "2.0"))).isEqualTo(SpecKind.SWAGGER2);
        assertThat(SpecKind.detectDocument(Json.obj("openapi", "3.0.3")))
                .isEqualTo(SpecKind.OPENAPI30);
        assertThat(SpecKind.detectDocument(Json.obj("openapi", "3.1.0")))
                .isEqualTo(SpecKind.OPENAPI31);
        for (Map<String, Object> bad : List.of(Json.obj("swagger", "1.2"),
                Json.obj("openapi", "4.0"), Json.obj("title", "x"))) {
            assertThatThrownBy(() -> SpecKind.detectDocument(bad))
                    .isInstanceOf(UnsupportedSpecException.class);
        }
        for (SpecKind k : SpecKind.values()) {
            assertThat(k.label()).isNotBlank();
            assertThat(k.value()).isNotBlank();
        }
    }

    @Test
    void detectXml() {
        assertThat(SpecKind.detectXml("<wsdl:definitions xmlns:wsdl=\"x\"/>"))
                .isEqualTo(SpecKind.WSDL11);
        assertThatThrownBy(() -> SpecKind.detectXml(
                "<description xmlns=\"http://www.w3.org/ns/wsdl\"/>"))
                .hasMessageContaining("2.0");
        assertThatThrownBy(() -> SpecKind.detectXml("<root/>"))
                .isInstanceOf(UnsupportedSpecException.class);
    }

    @Test
    void namingConversions() {
        String[][] cases = {
            {"GetForecast", "getForecast", "GetForecast", "get-forecast"},
            {"create_alert_subscription", "createAlertSubscription", "CreateAlertSubscription",
                "create-alert-subscription"},
            {"NDFDgenByDay", "ndfDgenByDay", "NDFDgenByDay", "ndf-dgen-by-day"},
            {"forecast-v2", "forecastV2", "ForecastV2", "forecast-v2"},
        };
        for (String[] c : cases) {
            assertThat(Naming.toCamel(c[0])).isEqualTo(c[1]);
            assertThat(Naming.toPascal(c[0])).isEqualTo(c[2]);
            assertThat(Naming.toKebab(c[0])).isEqualTo(c[3]);
        }
        assertThat(Naming.words("--")).isEmpty();
        assertThat(Naming.toCamel("--")).isEqualTo("--");
        assertThat(Naming.toKebab("--")).isEqualTo("--");
        assertThat(Naming.unique("a", List.of("a", "a2"))).isEqualTo("a3");
    }

    @Test
    void jsonHelpers() {
        assertThat(Json.truthy(null)).isFalse();
        assertThat(Json.truthy(0)).isFalse();
        assertThat(Json.truthy(1.5)).isTrue();
        assertThat(Json.truthy("")).isFalse();
        assertThat(Json.truthy(List.of())).isFalse();
        assertThat(Json.truthy(Map.of())).isFalse();
        assertThat(Json.truthy(new Object())).isTrue();
        assertThat(Json.str(null)).isEmpty();
        assertThat(Json.list("x")).isEmpty();
        Map<String, Object> parent = Json.obj("k", "not a map");
        Json.child(parent, "k").put("x", 1);
        assertThat(Json.map(parent.get("k"))).containsKey("x");
    }
}
