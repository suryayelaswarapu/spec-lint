package io.github.suryayelaswarapu.speclint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for tests. */
public final class TestSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() {}

    public static Path testdata() {
        String dir = System.getProperty("speclint.testdata");
        return dir != null ? Path.of(dir) : Path.of("..", "testdata");
    }

    public static Path testdata(String rel) {
        return testdata().resolve(rel);
    }

    public static SourceFile source(String text) {
        return new SourceFile(Path.of("spec.yaml"), text);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJson(Path path) {
        try {
            return MAPPER.readValue(Files.readString(path), LinkedHashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Normalise numbers so 60, 60L and 60.0 compare equal (JSON has one number type). */
    public static Object normalize(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(normalize(v)));
            return out;
        }
        if (value instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
            return d.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : d;
        }
        return value;
    }

    /** A small OpenAPI 3.1 document that passes every rule. */
    public static Map<String, Object> minimalOas() {
        return Json.copyMap(io.github.suryayelaswarapu.speclint.io.SpecIO.parseStructured("""
                openapi: 3.1.0
                info:
                  title: Demo
                  version: 1.0.0
                  description: Demo API
                  contact: {name: Team}
                  license: {name: MIT, identifier: MIT}
                servers: [{url: 'https://api.example.com'}]
                security: [{key: []}]
                tags: [{name: items}]
                paths:
                  /items/{itemId}:
                    get:
                      tags: [items]
                      summary: Get item
                      operationId: getItem
                      parameters:
                        - name: itemId
                          in: path
                          required: true
                          description: Item id
                          schema: {type: string}
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Item'}
                        '404': {$ref: '#/components/responses/NotFound'}
                components:
                  schemas:
                    Item: {type: object, properties: {itemId: {type: string}}}
                    Problem: {type: object, properties: {title: {type: string}}}
                  responses:
                    NotFound:
                      description: Not found
                      content:
                        application/problem+json:
                          schema: {$ref: '#/components/schemas/Problem'}
                  securitySchemes:
                    key: {type: apiKey, in: header, name: X-Key}
                """));
    }
}
