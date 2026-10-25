package io.github.suryayelaswarapu.speclint.convert;

import static io.github.suryayelaswarapu.speclint.Json.list;
import static io.github.suryayelaswarapu.speclint.Json.map;

import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.SpecKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAPI 3.0 to 3.1 (schema keyword changes only; the structure is identical). */
public final class OpenApi30Upgrader {
    static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private OpenApi30Upgrader() {}

    private static void content(Object content) {
        for (Object media : map(content).values()) {
            if (media instanceof Map<?, ?> && map(media).containsKey("schema")) {
                map(media).put("schema", SchemaUpgrader.upgrade(map(media).get("schema")));
            }
        }
    }

    private static void parameters(Object params) {
        for (Object p : list(params)) {
            if (p instanceof Map<?, ?> && map(p).containsKey("schema")) {
                map(p).put("schema", SchemaUpgrader.upgrade(map(p).get("schema")));
            }
        }
    }

    private static void response(Object resp) {
        if (!(resp instanceof Map<?, ?>)) {
            return;
        }
        content(map(resp).get("content"));
        for (Object header : map(map(resp).get("headers")).values()) {
            if (header instanceof Map<?, ?> && map(header).containsKey("schema")) {
                map(header).put("schema", SchemaUpgrader.upgrade(map(header).get("schema")));
            }
        }
    }

    public static ConversionResult upgrade(Map<String, Object> doc) {
        Map<String, Object> out = Json.copyMap(doc);
        out.put("openapi", "3.1.0");
        for (Object item : map(out.get("paths")).values()) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            parameters(map(item).get("parameters"));
            for (String method : HTTP_METHODS) {
                Object op = map(item).get(method);
                if (!(op instanceof Map<?, ?>)) {
                    continue;
                }
                parameters(map(op).get("parameters"));
                if (map(op).get("requestBody") instanceof Map<?, ?>) {
                    content(map(map(op).get("requestBody")).get("content"));
                }
                map(map(op).get("responses")).values().forEach(OpenApi30Upgrader::response);
            }
        }
        Map<String, Object> comp = map(out.get("components"));
        if (comp.get("schemas") instanceof Map<?, ?>) {
            Map<String, Object> schemas = new LinkedHashMap<>();
            map(comp.get("schemas")).forEach((k, v) -> schemas.put(k, SchemaUpgrader.upgrade(v)));
            comp.put("schemas", schemas);
        }
        parameters(new ArrayList<>(map(comp.get("parameters")).values()));
        for (Object body : map(comp.get("requestBodies")).values()) {
            if (body instanceof Map<?, ?>) {
                content(map(body).get("content"));
            }
        }
        map(comp.get("responses")).values().forEach(OpenApi30Upgrader::response);
        return new ConversionResult(out, SpecKind.OPENAPI30, List.of());
    }
}
