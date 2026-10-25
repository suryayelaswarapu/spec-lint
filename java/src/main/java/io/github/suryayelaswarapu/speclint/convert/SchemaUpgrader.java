package io.github.suryayelaswarapu.speclint.convert;

import io.github.suryayelaswarapu.speclint.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Upgrades Swagger 2.0 / OpenAPI 3.0 schema objects to OpenAPI 3.1 (JSON Schema 2020-12). */
public final class SchemaUpgrader {
    static final int MAX_DEPTH = 64;
    private static final List<String> LISTS = List.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final List<String> SINGLES =
            List.of("items", "not", "additionalProperties", "if", "then", "else", "contains");
    private static final List<String> MAPS = List.of("properties", "patternProperties", "$defs");

    private SchemaUpgrader() {}

    /** Deep copy of {@code node} with {@code $ref} prefixes rewritten via {@code mapping}. */
    public static Object rewriteRefs(Object node, Map<String, String> mapping) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (key.equals("$ref") && v instanceof String ref) {
                    for (Map.Entry<String, String> e : mapping.entrySet()) {
                        if (ref.startsWith(e.getKey())) {
                            ref = e.getValue() + ref.substring(e.getKey().length());
                            break;
                        }
                    }
                    out.put(key, ref);
                } else {
                    out.put(key, rewriteRefs(v, mapping));
                }
            });
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(rewriteRefs(v, mapping)));
            return out;
        }
        return node;
    }

    static Map<String, Object> addNull(Map<String, Object> schema) {
        if (schema.containsKey("$ref") || !schema.containsKey("type")) {
            return Json.obj("anyOf", new ArrayList<>(List.of(schema, Json.obj("type", "null"))));
        }
        Object current = schema.get("type");
        List<Object> types = current instanceof List<?> l ? new ArrayList<>(l)
                : new ArrayList<>(List.of(current));
        if (!types.contains("null")) {
            types.add("null");
        }
        schema.put("type", types);
        if (schema.get("enum") instanceof List<?> e && !e.contains(null)) {
            List<Object> values = new ArrayList<>(e);
            values.add(null);
            schema.put("enum", values);
        }
        return schema;
    }

    public static Object upgrade(Object schema) {
        return upgrade(schema, 0);
    }

    /**
     * Convert one schema (recursively) to 3.1 semantics: {@code nullable}/{@code x-nullable} to
     * type arrays, boolean exclusive bounds to numbers, {@code type: file} to binary strings,
     * {@code example} to {@code examples}, and string discriminators to objects.
     */
    public static Object upgrade(Object schema, int depth) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?>)) {
            return schema;
        }
        Map<String, Object> s = new LinkedHashMap<>(Json.map(schema));
        for (String key : LISTS) {
            if (s.get(key) instanceof List<?> l) {
                List<Object> out = new ArrayList<>();
                l.forEach(x -> out.add(upgrade(x, depth + 1)));
                s.put(key, out);
            }
        }
        for (String key : SINGLES) {
            if (s.get(key) instanceof Map<?, ?>) {
                s.put(key, upgrade(s.get(key), depth + 1));
            }
        }
        for (String key : MAPS) {
            if (s.get(key) instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), upgrade(v, depth + 1)));
                s.put(key, out);
            }
        }
        if ("file".equals(s.get("type"))) {
            s.put("type", "string");
            s.put("format", "binary");
        }
        String[][] bounds = {{"minimum", "exclusiveMinimum"}, {"maximum", "exclusiveMaximum"}};
        for (String[] pair : bounds) {
            if (s.get(pair[1]) instanceof Boolean exclusive) {
                if (exclusive && s.containsKey(pair[0])) {
                    s.put(pair[1], s.remove(pair[0]));
                } else {
                    s.remove(pair[1]);
                }
            }
        }
        if (s.get("discriminator") instanceof String name) {
            s.put("discriminator", Json.obj("propertyName", name));
        }
        if (s.containsKey("example")) {
            List<Object> examples = new ArrayList<>();
            examples.add(s.remove("example"));
            s.put("examples", examples);
        }
        boolean nullable = Json.truthy(s.remove("nullable")) | Json.truthy(s.remove("x-nullable"));
        return nullable ? addNull(s) : s;
    }
}
