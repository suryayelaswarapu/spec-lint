package io.github.suryayelaswarapu.speclint.fix;

import static io.github.suryayelaswarapu.speclint.Json.child;
import static io.github.suryayelaswarapu.speclint.Json.list;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static io.github.suryayelaswarapu.speclint.Json.obj;
import static io.github.suryayelaswarapu.speclint.Json.str;
import static io.github.suryayelaswarapu.speclint.Json.truthy;

import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.Naming;
import io.github.suryayelaswarapu.speclint.convert.OpenApi30Upgrader;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import io.github.suryayelaswarapu.speclint.lint.Walk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Safe auto-fixes. Only changes that do not alter the wire contract are applied: operationIds,
 * tags, component names, schema extraction and documented error bodies. Paths, parameter names
 * and property names are never renamed.
 */
public final class Fixer {
    public static final String PROBLEM_MEDIA = "application/problem+json";
    static final List<String> TOP_LEVEL_ORDER = List.of("openapi", "info", "jsonSchemaDialect",
            "servers", "security", "tags", "externalDocs", "paths", "webhooks", "components");

    static Map<String, Object> problemSchema() {
        return obj(
                "type", "object",
                "description", "Error body following RFC 9457 (Problem Details for HTTP APIs).",
                "properties", obj(
                        "type", obj("type", "string", "format", "uri-reference"),
                        "title", obj("type", "string"),
                        "status", obj("type", "integer", "minimum", 100L, "maximum", 599L),
                        "detail", obj("type", "string"),
                        "instance", obj("type", "string", "format", "uri-reference")));
    }

    /** A fix that was applied, for reports. */
    public record AppliedFix(String rule, String message, String path) {}

    /** Result of {@link #apply}. */
    public record Result(Map<String, Object> document, List<AppliedFix> applied) {}

    private Map<String, Object> doc;
    private final LintConfig config;
    private final List<AppliedFix> applied = new ArrayList<>();

    private Fixer(Map<String, Object> doc, LintConfig config) {
        this.doc = Json.copyMap(doc);
        this.config = config;
    }

    public static Result apply(Map<String, Object> doc, LintConfig config) {
        Fixer f = new Fixer(doc, config != null ? config : new LintConfig());
        f.legacyNullable();
        f.operationIds();
        f.tags();
        f.schemaNames();
        f.inlineSchemas();
        f.errorResponses();
        f.orderKeys();
        return new Result(f.doc, f.applied);
    }

    public static Result apply(Map<String, Object> doc) {
        return apply(doc, null);
    }

    private boolean on(String rule) {
        return config.enabled(rule);
    }

    private void log(String rule, String message, List<String> path) {
        applied.add(new AppliedFix(rule, message, Finding.pointer(path)));
    }

    private static String label(Walk.Op o) {
        return o.method().toUpperCase(Locale.ROOT) + " " + o.path();
    }

    private void legacyNullable() {
        if (on("no-legacy-nullable") && !Rules.ALL.get("no-legacy-nullable").check()
                .apply(doc).isEmpty()) {
            Object version = doc.get("openapi");
            doc = OpenApi30Upgrader.upgrade(doc).document();
            doc.put("openapi", version);
            log("no-legacy-nullable", "Rewrote nullable/x-nullable as type: [T, 'null']",
                    List.of());
        }
    }

    private void operationIds() {
        List<Walk.Op> ops = Walk.operations(doc);
        Set<String> taken = new HashSet<>();
        for (Walk.Op o : ops) {
            if (truthy(o.op().get("operationId"))) {
                taken.add(str(o.op().get("operationId")));
            }
        }
        if (on("operation-id-required")) {
            for (Walk.Op o : ops) {
                if (truthy(o.op().get("operationId"))) {
                    continue;
                }
                List<String> parts = new ArrayList<>(List.of(o.method()));
                for (String seg : o.path().split("/")) {
                    if (seg.startsWith("{")) {
                        parts.add("by");
                        parts.add(seg.replaceAll("^\\{+|\\}+$", ""));
                    } else if (!seg.isEmpty()) {
                        parts.add(seg);
                    }
                }
                String id = Naming.unique(Naming.toCamel(String.join("-", parts)), taken);
                taken.add(id);
                o.op().put("operationId", id);
                log("operation-id-required", "Added operationId '" + id + "' to " + label(o),
                        List.of("paths", o.path(), o.method()));
            }
        }
        if (on("operation-id-camel-case")) {
            for (Walk.Op o : ops) {
                String old = str(o.op().get("operationId"));
                if (!old.isEmpty() && !Naming.CAMEL_CASE.matcher(old).matches()) {
                    taken.remove(old);
                    String id = Naming.unique(Naming.toCamel(old), taken);
                    taken.add(id);
                    o.op().put("operationId", id);
                    log("operation-id-camel-case",
                            "Renamed operationId '" + old + "' -> '" + id + "'",
                            List.of("paths", o.path(), o.method(), "operationId"));
                }
            }
        }
    }

    private void tags() {
        if (on("operation-tags")) {
            for (Walk.Op o : Walk.operations(doc)) {
                if (truthy(o.op().get("tags"))) {
                    continue;
                }
                List<String> segs = new ArrayList<>();
                for (String s : o.path().split("/")) {
                    if (!s.isEmpty() && !s.startsWith("{") && !s.matches("v\\d+")) {
                        segs.add(s);
                    }
                }
                String tag = segs.isEmpty() ? "default" : Naming.toKebab(segs.get(0));
                o.op().put("tags", new ArrayList<>(List.of(tag)));
                log("operation-tags", "Tagged " + label(o) + " as '" + tag + "'",
                        List.of("paths", o.path(), o.method()));
            }
        }
        if (on("operation-tag-defined")) {
            List<Object> declared = doc.get("tags") instanceof List<?> ? list(doc.get("tags"))
                    : new ArrayList<>();
            Set<Object> names = new HashSet<>();
            for (Object t : declared) {
                if (t instanceof Map<?, ?>) {
                    names.add(map(t).get("name"));
                }
            }
            for (Walk.Op o : Walk.operations(doc)) {
                for (Object tag : list(o.op().get("tags"))) {
                    if (names.add(tag)) {
                        declared.add(obj("name", tag));
                        log("operation-tag-defined", "Declared tag '" + tag + "'",
                                List.of("tags"));
                    }
                }
            }
            if (declared.isEmpty()) {
                doc.remove("tags");
            } else {
                doc.put("tags", declared);
            }
        }
    }

    private void schemaNames() {
        if (!on("schema-name-pascal-case")) {
            return;
        }
        Map<String, Object> schemas = map(map(doc.get("components")).get("schemas"));
        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(schemas.keySet());
        for (String name : schemas.keySet()) {
            if (!Naming.PASCAL_CASE.matcher(name).matches()) {
                taken.remove(name);
                String id = Naming.unique(Naming.toPascal(name), taken);
                taken.add(id);
                renames.put(name, id);
            }
        }
        if (renames.isEmpty()) {
            return;
        }
        Map<String, Object> renamed = new LinkedHashMap<>();
        schemas.forEach((k, v) -> renamed.put(renames.getOrDefault(k, k), v));
        map(doc.get("components")).put("schemas", renamed);
        rewrite(doc, renames);
        renames.forEach((old, id) -> log("schema-name-pascal-case",
                "Renamed schema '" + old + "' -> '" + id + "'",
                List.of("components", "schemas", id)));
    }

    private static void rewrite(Object node, Map<String, String> renames) {
        String prefix = "#/components/schemas/";
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> mm = map(m);
            if (mm.get("$ref") instanceof String ref && ref.startsWith(prefix)) {
                String rest = ref.substring(prefix.length());
                int slash = rest.indexOf('/');
                String head = slash < 0 ? rest : rest.substring(0, slash);
                String tail = slash < 0 ? "" : rest.substring(slash);
                if (renames.containsKey(head)) {
                    mm.put("$ref", prefix + renames.get(head) + tail);
                }
            }
            mm.values().forEach(v -> rewrite(v, renames));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> rewrite(v, renames));
        }
    }

    private void inlineSchemas() {
        if (!on("no-inline-object-schemas")) {
            return;
        }
        for (Walk.Op o : Walk.operations(doc)) {
            String stem = Naming.toPascal(truthy(o.op().get("operationId"))
                    ? str(o.op().get("operationId")) : o.method() + "-" + o.path());
            List<Map.Entry<String, Map<String, Object>>> bodies = new ArrayList<>();
            if (o.op().get("requestBody") instanceof Map<?, ?>) {
                bodies.add(Map.entry("Request", map(o.op().get("requestBody"))));
            }
            map(o.op().get("responses")).forEach((code, resp) -> {
                if (resp instanceof Map<?, ?>) {
                    String suffix = code.equals("200") || code.equals("201") ? "Response"
                            : code + "Response";
                    bodies.add(Map.entry(suffix, map(resp)));
                }
            });
            for (Map.Entry<String, Map<String, Object>> body : bodies) {
                Map<Object, String> extracted = new LinkedHashMap<>();
                for (Map.Entry<String, Object> media : map(body.getValue().get("content"))
                        .entrySet()) {
                    if (!(media.getValue() instanceof Map<?, ?>)) {
                        continue;
                    }
                    Map<String, Object> holder = map(media.getValue());
                    if (!(holder.get("schema") instanceof Map<?, ?>)) {
                        continue;
                    }
                    Map<String, Object> schema = map(holder.get("schema"));
                    if (schema.containsKey("$ref") || (!"object".equals(schema.get("type"))
                            && !schema.containsKey("properties"))) {
                        continue;
                    }
                    if (!extracted.containsKey(schema)) {
                        Map<String, Object> schemas = child(child(doc, "components"), "schemas");
                        String name = Naming.unique(stem + body.getKey(), schemas.keySet());
                        schemas.put(name, schema);
                        extracted.put(schema, name);
                        log("no-inline-object-schemas", "Extracted " + label(o) + " "
                                + media.getKey() + " schema to '" + name + "'",
                                List.of("components", "schemas", name));
                    }
                    holder.put("schema", obj("$ref", "#/components/schemas/"
                            + extracted.get(schema)));
                }
            }
        }
    }

    private void ensureProblem() {
        Map<String, Object> components = child(doc, "components");
        child(components, "schemas").putIfAbsent("Problem", problemSchema());
        child(components, "responses").putIfAbsent("Problem", obj(
                "description", "Error (RFC 9457 Problem Details)",
                "content", obj(PROBLEM_MEDIA,
                        obj("schema", obj("$ref", "#/components/schemas/Problem")))));
    }

    private void errorResponses() {
        if (on("error-response-schema")) {
            for (Walk.Op o : Walk.operations(doc)) {
                for (Map.Entry<String, Object> e : map(o.op().get("responses")).entrySet()) {
                    if (!isErrorCode(e.getKey()) || !(e.getValue() instanceof Map<?, ?>)) {
                        continue;
                    }
                    Map<String, Object> resp = map(e.getValue());
                    Map<String, Object> content = resp.get("content") instanceof Map<?, ?>
                            ? map(resp.get("content")) : new LinkedHashMap<>();
                    boolean hasSchema = content.values().stream()
                            .anyMatch(m -> m instanceof Map<?, ?> mm && mm.containsKey("schema"));
                    if (resp.containsKey("$ref") || hasSchema) {
                        continue;
                    }
                    ensureProblem();
                    content.put(PROBLEM_MEDIA,
                            obj("schema", obj("$ref", "#/components/schemas/Problem")));
                    resp.put("content", content);
                    log("error-response-schema", "Added Problem body to " + label(o)
                            + " response " + e.getKey(),
                            List.of("paths", o.path(), o.method(), "responses", e.getKey()));
                }
            }
        }
        if (on("operation-error-response")) {
            for (Walk.Op o : Walk.operations(doc)) {
                Map<String, Object> responses = child(o.op(), "responses");
                if (responses.keySet().stream().noneMatch(Fixer::isErrorCode)) {
                    ensureProblem();
                    responses.put("default", obj("$ref", "#/components/responses/Problem"));
                    log("operation-error-response",
                            "Added default Problem response to " + label(o),
                            List.of("paths", o.path(), o.method(), "responses", "default"));
                }
            }
        }
    }

    private static boolean isErrorCode(String code) {
        return code.equals("default") || code.startsWith("4") || code.startsWith("5");
    }

    private void orderKeys() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : TOP_LEVEL_ORDER) {
            if (doc.containsKey(k)) {
                ordered.put(k, doc.get(k));
            }
        }
        doc.forEach(ordered::putIfAbsent);
        doc = ordered;
    }
}
