package io.github.suryayelaswarapu.speclint.lint;

import static io.github.suryayelaswarapu.speclint.Json.list;
import static io.github.suryayelaswarapu.speclint.Json.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Helpers for walking an OpenAPI 3.x document. */
public final class Walk {
    public static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    public static final List<String> COMPONENT_SECTIONS = List.of(
            "schemas", "parameters", "responses", "requestBodies", "headers", "examples");
    static final int MAX_DEPTH = 64;

    private Walk() {}

    public record Op(String path, String method, Map<String, Object> op,
            Map<String, Object> item) {}

    public record Ref(String ref, List<String> path) {}

    public record Media(String kind, Map<String, Object> schema, List<String> path,
            String label) {}

    public record Prop(String name, List<String> path) {}

    public static List<String> path(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    public static List<Op> operations(Map<String, Object> doc) {
        List<Op> out = new ArrayList<>();
        map(doc.get("paths")).forEach((path, item) -> {
            if (!(item instanceof Map<?, ?>)) {
                return;
            }
            for (String method : HTTP_METHODS) {
                if (map(item).get(method) instanceof Map<?, ?>) {
                    out.add(new Op(path, method, map(map(item).get(method)), map(item)));
                }
            }
        });
        return out;
    }

    /** Every {@code $ref} string with its location. */
    public static List<Ref> refs(Object node) {
        List<Ref> out = new ArrayList<>();
        record Item(Object node, List<String> path) {}
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(node, List.of()));
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            if (it.node() instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    String key = String.valueOf(k);
                    if (key.equals("$ref") && v instanceof String s) {
                        out.add(new Ref(s, path(it.path(), "$ref")));
                    } else {
                        stack.push(new Item(v, path(it.path(), key)));
                    }
                });
            } else if (it.node() instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    stack.push(new Item(l.get(i), path(it.path(), String.valueOf(i))));
                }
            }
        }
        return out;
    }

    /** Resolve a local {@code #/a/b} reference; null if it does not exist. */
    public static Object resolvePointer(Object doc, String ref) {
        if (!ref.startsWith("#")) {
            return null;
        }
        Object node = doc;
        String[] parts = ref.substring(1).split("/", -1);
        for (int i = 1; i < parts.length; i++) {
            String key = parts[i].replace("~1", "/").replace("~0", "~");
            if (node instanceof Map<?, ?> m && m.containsKey(key)) {
                node = m.get(key);
            } else if (node instanceof List<?> l && key.matches("\\d+")
                    && Integer.parseInt(key) < l.size()) {
                node = l.get(Integer.parseInt(key));
            } else {
                return null;
            }
        }
        return node;
    }

    /** Follow {@code $ref} chains (max 10 hops). */
    public static Object deref(Map<String, Object> doc, Object node) {
        for (int i = 0; i < 10; i++) {
            if (node instanceof Map<?, ?> m && m.get("$ref") instanceof String ref) {
                node = resolvePointer(doc, ref);
            } else {
                break;
            }
        }
        return node;
    }

    /** Request/response body schemas of every operation. */
    public static List<Media> mediaSchemas(Map<String, Object> doc) {
        List<Media> out = new ArrayList<>();
        for (Op o : operations(doc)) {
            List<String> base = List.of("paths", o.path(), o.method());
            String label = o.method().toUpperCase(java.util.Locale.ROOT) + " " + o.path();
            Object body = o.op().get("requestBody");
            if (body instanceof Map<?, ?> && map(body).get("content") instanceof Map<?, ?>) {
                map(map(body).get("content")).forEach((media, obj) -> {
                    if (map(obj).get("schema") instanceof Map<?, ?>) {
                        out.add(new Media("request", map(map(obj).get("schema")),
                                path(base, "requestBody", "content", media, "schema"), label));
                    }
                });
            }
            map(o.op().get("responses")).forEach((code, resp) -> {
                if (!(map(resp).get("content") instanceof Map<?, ?>)) {
                    return;
                }
                map(map(resp).get("content")).forEach((media, obj) -> {
                    if (map(obj).get("schema") instanceof Map<?, ?>) {
                        out.add(new Media("response:" + code, map(map(obj).get("schema")),
                                path(base, "responses", code, "content", media, "schema"),
                                label));
                    }
                });
            });
        }
        return out;
    }

    /** Property names of a schema and its inline sub-schemas. */
    public static List<Prop> schemaProperties(Object schema, List<String> path) {
        List<Prop> out = new ArrayList<>();
        collectProps(schema, path, 0, out);
        return out;
    }

    private static void collectProps(Object schema, List<String> path, int depth,
            List<Prop> out) {
        if (depth > MAX_DEPTH || !(schema instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> s = map(schema);
        if (s.get("properties") instanceof Map<?, ?>) {
            map(s.get("properties")).forEach((name, sub) -> {
                List<String> p = path(path, "properties", name);
                out.add(new Prop(name, p));
                collectProps(sub, p, depth + 1, out);
            });
        }
        for (String key : List.of("items", "additionalProperties", "not")) {
            if (s.get(key) instanceof Map<?, ?>) {
                collectProps(s.get(key), path(path, key), depth + 1, out);
            }
        }
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            List<Object> subs = list(s.get(key));
            for (int i = 0; i < subs.size(); i++) {
                collectProps(subs.get(i), path(path, key, String.valueOf(i)), depth + 1, out);
            }
        }
    }
}
