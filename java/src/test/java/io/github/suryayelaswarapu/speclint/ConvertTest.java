package io.github.suryayelaswarapu.speclint;

import static io.github.suryayelaswarapu.speclint.Json.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.Converters;
import io.github.suryayelaswarapu.speclint.convert.OpenApi30Upgrader;
import io.github.suryayelaswarapu.speclint.convert.SchemaUpgrader;
import io.github.suryayelaswarapu.speclint.convert.Swagger2Converter;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConvertTest {
    static Map<String, Object> yaml(String text) {
        return SpecIO.parseStructured(text);
    }

    static Object at(Object node, String... keys) {
        Object cur = node;
        for (String k : keys) {
            cur = cur instanceof List<?> l ? l.get(Integer.parseInt(k)) : map(cur).get(k);
        }
        return cur;
    }

    @Test
    void swaggerServersSecurityAndWarnings() {
        ConversionResult r = new Swagger2Converter(yaml("""
                swagger: '2.0'
                info: {title: T, version: '1'}
                paths: {}
                securityDefinitions:
                  basic: {type: basic, description: d}
                  key: {type: apiKey, in: query, name: k}
                  pw: {type: oauth2, flow: password, tokenUrl: 'https://t', scopes: {}}
                  app: {type: oauth2, flow: application, tokenUrl: 'https://t'}
                  impl: {type: oauth2, flow: implicit, authorizationUrl: 'https://a'}
                  code: {type: oauth2, flow: accessCode, authorizationUrl: 'https://a', tokenUrl: 'https://t'}
                  bad: {type: oauth2, flow: weird}
                  odd: {type: mystery}
                """)).convert();
        assertThat(at(r.document(), "servers", "0", "url")).isEqualTo("/");
        Map<String, Object> schemes = map(at(r.document(), "components", "securitySchemes"));
        assertThat(map(schemes.get("basic"))).containsEntry("scheme", "basic")
                .containsEntry("description", "d");
        assertThat(map(at(schemes, "pw", "flows"))).containsKey("password");
        assertThat(map(at(schemes, "app", "flows"))).containsKey("clientCredentials");
        assertThat(map(at(schemes, "code", "flows"))).containsKey("authorizationCode");
        assertThat(map(at(schemes, "bad", "flows"))).containsKey("implicit");
        assertThat(r.warnings()).hasSize(3);

        ConversionResult hosted = new Swagger2Converter(yaml(
                "swagger: '2.0'\nhost: h.example.com\npaths: {}\nx-top: 1\n")).convert();
        assertThat(at(hosted.document(), "servers", "0", "url")).isEqualTo("https://h.example.com");
        assertThat(at(hosted.document(), "info", "title")).isEqualTo("Untitled API");
        assertThat(hosted.document()).containsKey("x-top");
        assertThat(hosted.warnings()).anyMatch(w -> w.contains("schemes"));
    }

    @Test
    void swaggerOperations() {
        Map<String, Object> out = new Swagger2Converter(yaml("""
                swagger: '2.0'
                host: h
                schemes: [https]
                consumes: [application/json, application/xml]
                parameters:
                  Limit: {name: limit, in: query, type: integer}
                  Body: {name: body, in: body, schema: {$ref: '#/definitions/Item'}}
                responses:
                  NotFound: {description: nf}
                definitions:
                  Item: {type: object, x-nullable: true}
                paths:
                  /items:
                    parameters: [{$ref: '#/parameters/Limit'}]
                    x-path-ext: 1
                    post:
                      operationId: createItem
                      parameters: [{$ref: '#/parameters/Body'}, {$ref: '#/parameters/Body'}, notamap]
                      responses:
                        '201': {description: ok, schema: {$ref: '#/definitions/Item'}}
                        '404': {$ref: '#/responses/NotFound'}
                        '500': junk
                      schemes: [http]
                      x-internal: true
                  /a:
                    $ref: other.yaml
                    post:
                      parameters:
                        - {name: b, in: body, required: true, description: d, schema: {type: object}}
                      responses: {}
                  /f:
                    parameters: [{name: n, in: formData, type: string}]
                    post:
                      consumes: [application/x-www-form-urlencoded]
                      responses: {}
                  /u:
                    post:
                      parameters:
                        - {name: file, in: formData, type: file, required: true, description: up}
                      responses:
                        '200':
                          description: ok
                          schema: {type: string}
                          examples: {application/json: hi}
                          headers: {X-Rate: {type: integer, description: r}, X-Other: {type: string}}
                  /q/{id}:
                    get:
                      parameters:
                        - {name: q, in: query, type: array, items: {type: string}, collectionFormat: multi, x-ext: 1}
                        - {name: h, in: header, type: array, items: {type: string}, collectionFormat: csv}
                        - {name: t, in: header, type: array, items: {type: string}, collectionFormat: tsv}
                        - {name: id, in: path, type: string, x-nullable: true}
                      responses: {}
                  /bad: nope
                """)).convert().document();
        assertThat(at(out, "paths", "/items", "post", "requestBody", "$ref"))
                .isEqualTo("#/components/requestBodies/Body");
        assertThat(at(out, "paths", "/items", "post", "responses", "404", "$ref"))
                .isEqualTo("#/components/responses/NotFound");
        assertThat(map(at(out, "paths", "/items", "post", "responses"))).doesNotContainKey("500");
        assertThat(at(out, "components", "schemas", "Item", "type"))
                .isEqualTo(List.of("object", "null"));
        assertThat(at(out, "paths", "/a", "post", "requestBody", "required")).isEqualTo(true);
        assertThat(map(at(out, "paths", "/f", "post", "requestBody", "content")))
                .containsKey("application/x-www-form-urlencoded");
        assertThat(at(out, "paths", "/u", "post", "requestBody", "content",
                "multipart/form-data", "schema", "properties", "file", "format"))
                .isEqualTo("binary");
        assertThat(at(out, "paths", "/u", "post", "responses", "200", "content",
                "application/json", "example")).isEqualTo("hi");
        List<Object> params = Json.list(at(out, "paths", "/q/{id}", "get", "parameters"));
        assertThat(map(params.get(0))).containsEntry("style", "form").containsEntry("explode", true)
                .containsEntry("x-ext", 1);
        assertThat(map(params.get(1))).containsEntry("style", "simple");
        assertThat(map(params.get(2))).doesNotContainKey("style");
        assertThat(map(params.get(3))).containsEntry("required", true);
        assertThat(map(at(out, "paths"))).doesNotContainKey("/bad");
    }

    @Test
    void schemaUpgradeRules() {
        Map<String, Object> out = map(SchemaUpgrader.upgrade(yaml("""
                type: object
                discriminator: kind
                properties:
                  a: {type: integer, minimum: 1, exclusiveMinimum: true}
                  b: {type: integer, maximum: 5, exclusiveMaximum: false}
                  c: {type: file}
                  d: {$ref: '#/x', nullable: true}
                  e: {type: string, enum: [x], nullable: true}
                  f: {type: [string, 'null'], x-nullable: true}
                allOf: [{type: object}]
                patternProperties: {'^x': {type: string}}
                items: {type: string, example: z}
                """)));
        assertThat(TestSupport.normalize(at(out, "properties", "a")))
                .isEqualTo(TestSupport.normalize(Json.obj("type", "integer", "exclusiveMinimum", 1)));
        assertThat(map(at(out, "properties", "b"))).doesNotContainKey("exclusiveMaximum");
        assertThat(at(out, "properties", "c", "format")).isEqualTo("binary");
        assertThat(map(at(out, "properties", "d"))).containsKey("anyOf");
        assertThat(Json.list(at(out, "properties", "e", "enum"))).containsExactly("x", null);
        assertThat(at(out, "properties", "f", "type")).isEqualTo(List.of("string", "null"));
        assertThat(at(out, "discriminator", "propertyName")).isEqualTo("kind");
        assertThat(at(out, "items", "examples")).isEqualTo(List.of("z"));
        assertThat(SchemaUpgrader.upgrade("x")).isEqualTo("x");
        assertThat(SchemaUpgrader.rewriteRefs(List.of(Json.obj("$ref", 3)), Map.of()))
                .isEqualTo(List.of(Json.obj("$ref", 3)));
    }

    @Test
    void upgradeOpenApi30() {
        ConversionResult r = OpenApi30Upgrader.upgrade(yaml("""
                openapi: 3.0.3
                paths:
                  /a:
                    parameters: [{name: p, in: query, schema: {type: string, nullable: true}}]
                    get:
                      parameters: [{name: q, in: query, schema: {type: string, nullable: true}}]
                      requestBody: {content: {a/b: {schema: {type: string, nullable: true}}}}
                      responses:
                        '200':
                          content: {a/b: {schema: {type: string, nullable: true}}}
                          headers: {X: {schema: {type: string, nullable: true}}}
                        '204': junk
                  /skip: null
                components:
                  schemas: {S: {type: string, nullable: true}}
                  parameters: {P: {schema: {type: string, nullable: true}}}
                  requestBodies: {B: {content: {a/b: {schema: {type: string, nullable: true}}}}}
                  responses: {R: {content: {a/b: {schema: {type: string, nullable: true}}}}}
                """));
        assertThat(r.document().get("openapi")).isEqualTo("3.1.0");
        assertThat(SpecIO.dump(r.document(), "json")).doesNotContain("nullable");
    }

    @Test
    void convertSourceDispatch() {
        ConversionResult r = Converters.convertSource(
                TestSupport.source("openapi: 3.1.0\ninfo: {title: x, version: 1.0.0}\n"));
        assertThat(r.sourceKind()).isEqualTo(SpecKind.OPENAPI31);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void securityInputs() {
        for (String name : List.of("xxe.wsdl", "billion-laughs.wsdl")) {
            assertThatThrownBy(() -> Converters.convertSource(
                    SpecIO.readSource(TestSupport.testdata("security/" + name))))
                    .isInstanceOf(SecurityException.class).hasMessageContaining("Unsafe XML");
        }
        assertThatThrownBy(() -> Converters.convertSource(
                SpecIO.readSource(TestSupport.testdata("security/path-traversal.wsdl"))))
                .isInstanceOf(SecurityException.class).hasMessageContaining("escapes");
        ConversionResult remote = Converters.convertSource(
                SpecIO.readSource(TestSupport.testdata("security/remote-import.wsdl")));
        assertThat(remote.warnings()).anyMatch(w -> w.contains("not fetched"));
        assertThatThrownBy(() -> Converters.convertSource(TestSupport.source("<a><b></a>")))
                .isInstanceOf(SpecLintException.class);
        assertThatThrownBy(() -> new io.github.suryayelaswarapu.speclint.convert.WsdlConverter(
                null, true).convert("<a><b></a>")).isInstanceOf(InputException.class);
    }
}
