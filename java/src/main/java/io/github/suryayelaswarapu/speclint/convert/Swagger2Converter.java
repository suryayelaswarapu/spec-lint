package io.github.suryayelaswarapu.speclint.convert;

import static io.github.suryayelaswarapu.speclint.Json.copyMap;
import static io.github.suryayelaswarapu.speclint.Json.deepCopy;
import static io.github.suryayelaswarapu.speclint.Json.list;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static io.github.suryayelaswarapu.speclint.Json.obj;
import static io.github.suryayelaswarapu.speclint.Json.str;
import static io.github.suryayelaswarapu.speclint.Json.truthy;

import io.github.suryayelaswarapu.speclint.SpecKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Swagger 2.0 to OpenAPI 3.1. Follows the official 2.0 to 3.0 migration notes, then applies the
 * 3.0 to 3.1 schema changes. Anything without a lossless equivalent is reported as a warning.
 */
public final class Swagger2Converter {
    static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch");
    static final String DEFAULT_MEDIA = "application/json";
    private static final Map<String, String> REF_MAP = new LinkedHashMap<>();
    private static final Map<String, String[]> QUERY_STYLES = Map.of(
            "csv", new String[] {"form", "false"},
            "ssv", new String[] {"spaceDelimited", "false"},
            "pipes", new String[] {"pipeDelimited", "false"},
            "multi", new String[] {"form", "true"});
    private static final List<String> SCHEMA_KEYS = List.of(
            "type", "format", "items", "enum", "default", "maximum", "exclusiveMaximum", "minimum",
            "exclusiveMinimum", "maxLength", "minLength", "pattern", "maxItems", "minItems",
            "uniqueItems", "multipleOf");
    private static final List<String> OP_KEYS = List.of(
            "tags", "summary", "description", "externalDocs", "operationId", "deprecated",
            "security");

    static {
        REF_MAP.put("#/definitions/", "#/components/schemas/");
        REF_MAP.put("#/responses/", "#/components/responses/");
        REF_MAP.put("#/securityDefinitions/", "#/components/securitySchemes/");
    }

    private final Map<String, Object> src;
    private final List<String> warnings = new ArrayList<>();
    private final List<Object> globalConsumes;
    private final List<Object> globalProduces;
    private final Set<String> bodyParams = new HashSet<>();

    public Swagger2Converter(Map<String, Object> src) {
        this.src = copyMap(src);
        this.globalConsumes = truthy(this.src.get("consumes")) ? list(this.src.get("consumes"))
                : List.of(DEFAULT_MEDIA);
        this.globalProduces = truthy(this.src.get("produces")) ? list(this.src.get("produces"))
                : List.of(DEFAULT_MEDIA);
        map(this.src.get("parameters")).forEach((name, p) -> {
            Object in = map(p).get("in");
            if ("body".equals(in) || "formData".equals(in)) {
                bodyParams.add(name);
            }
        });
    }

    private Object refs(Object node) {
        return SchemaUpgrader.rewriteRefs(node, REF_MAP);
    }

    private Object schema(Object schema) {
        return SchemaUpgrader.upgrade(refs(schema));
    }

    public ConversionResult convert() {
        Map<String, Object> out = obj("openapi", "3.1.0");
        Map<String, Object> info = new LinkedHashMap<>(copyMap(map(src.get("info"))));
        info.putIfAbsent("title", "Untitled API");
        info.putIfAbsent("version", "0.0.0");
        out.put("info", info);
        out.put("servers", servers());
        for (String key : List.of("security", "tags", "externalDocs")) {
            if (src.containsKey(key)) {
                out.put(key, deepCopy(src.get(key)));
            }
        }
        out.put("paths", paths());
        Map<String, Object> components = components();
        if (!components.isEmpty()) {
            out.put("components", components);
        }
        src.forEach((k, v) -> {
            if (k.startsWith("x-")) {
                out.put(k, deepCopy(v));
            }
        });
        return new ConversionResult(out, SpecKind.SWAGGER2, warnings);
    }

    private List<Object> servers() {
        Object host = src.get("host");
        String base = truthy(src.get("basePath")) ? str(src.get("basePath")) : "/";
        List<Object> out = new ArrayList<>();
        if (!truthy(host)) {
            warnings.add("No 'host' in Swagger 2.0 spec; server URL is relative ('" + base + "').");
            out.add(obj("url", base));
            return out;
        }
        List<Object> schemes = truthy(src.get("schemes")) ? list(src.get("schemes"))
                : List.of("https");
        if (!src.containsKey("schemes")) {
            warnings.add("No 'schemes' declared; assumed https.");
        }
        for (Object scheme : schemes) {
            String url = scheme + "://" + host + base;
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            out.add(obj("url", url.isEmpty() ? "/" : url));
        }
        return out;
    }

    private Map<String, Object> components() {
        Map<String, Object> comp = obj();
        if (truthy(src.get("definitions"))) {
            Map<String, Object> schemas = obj();
            map(src.get("definitions")).forEach((k, v) -> schemas.put(k, schema(v)));
            comp.put("schemas", schemas);
        }
        Map<String, Object> params = obj();
        Map<String, Object> bodies = obj();
        map(src.get("parameters")).forEach((name, p) -> {
            if (bodyParams.contains(name)) {
                bodies.put(name, requestBody(List.of(map(p)), globalConsumes));
            } else {
                params.put(name, parameter(map(p)));
            }
        });
        if (!params.isEmpty()) {
            comp.put("parameters", params);
        }
        if (!bodies.isEmpty()) {
            comp.put("requestBodies", bodies);
        }
        if (truthy(src.get("responses"))) {
            Map<String, Object> responses = obj();
            map(src.get("responses")).forEach((k, v) -> responses.put(k,
                    response(map(v), globalProduces)));
            comp.put("responses", responses);
        }
        if (truthy(src.get("securityDefinitions"))) {
            Map<String, Object> schemes = obj();
            map(src.get("securityDefinitions")).forEach((k, v) -> schemes.put(k,
                    securityScheme(k, map(v))));
            comp.put("securitySchemes", schemes);
        }
        return comp;
    }

    private Map<String, Object> securityScheme(String name, Map<String, Object> d) {
        Object kind = d.get("type");
        Map<String, Object> out;
        if ("basic".equals(kind)) {
            out = obj("type", "http", "scheme", "basic");
        } else if ("apiKey".equals(kind)) {
            out = obj("type", "apiKey", "name", d.get("name"), "in", d.get("in"));
        } else if ("oauth2".equals(kind)) {
            String flowName = switch (str(d.get("flow"))) {
                case "implicit" -> "implicit";
                case "password" -> "password";
                case "application" -> "clientCredentials";
                case "accessCode" -> "authorizationCode";
                default -> "";
            };
            if (flowName.isEmpty()) {
                warnings.add("securityDefinitions." + name + ": unknown oauth2 flow '"
                        + str(d.get("flow")) + "'");
                flowName = "implicit";
            }
            Map<String, Object> flow = obj("scopes",
                    truthy(d.get("scopes")) ? d.get("scopes") : obj());
            if (d.containsKey("authorizationUrl")) {
                flow.put("authorizationUrl", d.get("authorizationUrl"));
            }
            if (d.containsKey("tokenUrl")) {
                flow.put("tokenUrl", d.get("tokenUrl"));
            }
            out = obj("type", "oauth2", "flows", obj(flowName, flow));
        } else {
            warnings.add("securityDefinitions." + name + ": unknown type '" + str(kind)
                    + "'; copied as-is");
            out = new LinkedHashMap<>(d);
        }
        if (d.containsKey("description")) {
            out.put("description", d.get("description"));
        }
        return out;
    }

    // ------------------------------------------------------------------ paths

    private Map<String, Object> paths() {
        Map<String, Object> paths = obj();
        map(src.get("paths")).forEach((path, rawItem) -> {
            if (!(rawItem instanceof Map<?, ?>)) {
                return;
            }
            Map<String, Object> item = map(rawItem);
            Map<String, Object> newItem = obj();
            if (item.containsKey("$ref")) {
                warnings.add("paths." + path + ": external path item $ref kept as-is");
                newItem.put("$ref", item.get("$ref"));
            }
            List<Object> pathParams = list(item.get("parameters"));
            List<Object> plain = splitParams(pathParams).plain();
            if (!plain.isEmpty()) {
                newItem.put("parameters", plain);
            }
            for (String method : HTTP_METHODS) {
                if (item.containsKey(method)) {
                    newItem.put(method, operation(map(item.get(method)), pathParams, path, method));
                }
            }
            item.forEach((k, v) -> {
                if (k.startsWith("x-")) {
                    newItem.put(k, deepCopy(v));
                }
            });
            paths.put(path, newItem);
        });
        return paths;
    }

    private record Split(List<Object> plain, List<Map<String, Object>> body) {}

    private Split splitParams(List<Object> params) {
        List<Object> plain = new ArrayList<>();
        List<Map<String, Object>> body = new ArrayList<>();
        for (Object raw : params) {
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> p = map(raw);
            if (p.containsKey("$ref")) {
                String ref = str(p.get("$ref"));
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                if (bodyParams.contains(name)) {
                    body.add(obj("$ref", "#/components/requestBodies/" + name));
                } else {
                    plain.add(obj("$ref", "#/components/parameters/" + name));
                }
            } else if ("body".equals(p.get("in")) || "formData".equals(p.get("in"))) {
                body.add(p);
            } else {
                plain.add(parameter(p));
            }
        }
        return new Split(plain, body);
    }

    private Map<String, Object> operation(
            Map<String, Object> op, List<Object> pathParams, String path, String method) {
        String where = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        Map<String, Object> out = obj();
        for (String key : OP_KEYS) {
            if (op.containsKey(key)) {
                out.put(key, deepCopy(op.get(key)));
            }
        }
        List<Object> consumes = truthy(op.get("consumes")) ? list(op.get("consumes"))
                : globalConsumes;
        List<Object> produces = truthy(op.get("produces")) ? list(op.get("produces"))
                : globalProduces;

        Split split = splitParams(list(op.get("parameters")));
        if (!split.plain().isEmpty()) {
            out.put("parameters", split.plain());
        }
        List<Map<String, Object>> body = split.body();
        if (body.isEmpty()) {
            body = splitParams(pathParams).body();
        }
        if (!body.isEmpty()) {
            List<Map<String, Object>> refs = body.stream().filter(b -> b.containsKey("$ref"))
                    .toList();
            if (!refs.isEmpty()) {
                out.put("requestBody", refs.get(0));
                if (body.size() > 1) {
                    warnings.add(where + ": multiple body parameters; kept "
                            + refs.get(0).get("$ref"));
                }
            } else {
                out.put("requestBody", requestBody(body, consumes));
            }
        }
        Map<String, Object> responses = obj();
        map(op.get("responses")).forEach((code, resp) -> {
            if (resp instanceof Map<?, ?>) {
                responses.put(code, response(map(resp), produces));
            }
        });
        out.put("responses", responses);
        if (op.containsKey("schemes")) {
            warnings.add(where + ": per-operation 'schemes' dropped (use servers instead)");
        }
        op.forEach((k, v) -> {
            if (k.startsWith("x-")) {
                out.put(k, deepCopy(v));
            }
        });
        return out;
    }

    // ------------------------------------------------------- params / bodies

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramSchema(Map<String, Object> p) {
        Map<String, Object> schema = obj();
        for (String key : SCHEMA_KEYS) {
            if (p.containsKey(key)) {
                schema.put(key, deepCopy(p.get(key)));
            }
        }
        if (schema.containsKey("items")) {
            schema.put("items", paramSchema(map(schema.get("items"))));
        }
        if (truthy(p.get("x-nullable"))) {
            schema.put("x-nullable", true);
        }
        return (Map<String, Object>) SchemaUpgrader.upgrade(schema);
    }

    private Map<String, Object> parameter(Map<String, Object> p) {
        Map<String, Object> out = obj("name", p.get("name"), "in", p.get("in"));
        for (String key : List.of("description", "required", "deprecated", "allowEmptyValue")) {
            if (p.containsKey(key)) {
                out.put(key, p.get(key));
            }
        }
        if ("path".equals(out.get("in"))) {
            out.put("required", true);
        }
        out.put("schema", paramSchema(p));
        String fmt = str(p.get("collectionFormat"));
        if ("array".equals(p.get("type")) && !fmt.isEmpty()) {
            if ("query".equals(out.get("in")) && QUERY_STYLES.containsKey(fmt)) {
                String[] style = QUERY_STYLES.get(fmt);
                out.put("style", style[0]);
                out.put("explode", Boolean.parseBoolean(style[1]));
            } else if (fmt.equals("csv")) {
                out.put("style", "simple");
                out.put("explode", false);
            } else {
                warnings.add("parameter '" + str(p.get("name")) + "': collectionFormat '" + fmt
                        + "' has no OpenAPI 3 equivalent for in=" + str(out.get("in"))
                        + "; defaulted");
            }
        }
        p.forEach((k, v) -> {
            if (k.startsWith("x-") && !k.equals("x-nullable")) {
                out.put(k, deepCopy(v));
            }
        });
        return out;
    }

    private Map<String, Object> requestBody(
            List<Map<String, Object>> params, List<Object> consumes) {
        Map<String, Object> body = params.stream().filter(p -> "body".equals(p.get("in")))
                .findFirst().orElse(null);
        if (body != null) {
            Map<String, Object> content = obj();
            for (Object ct : consumes) {
                content.put(str(ct), obj("schema",
                        schema(body.containsKey("schema") ? body.get("schema") : obj())));
            }
            Map<String, Object> out = obj("content", content);
            if (truthy(body.get("description"))) {
                out.put("description", body.get("description"));
            }
            if (truthy(body.get("required"))) {
                out.put("required", true);
            }
            return out;
        }
        Map<String, Object> props = obj();
        List<Object> required = new ArrayList<>();
        boolean hasFile = false;
        for (Map<String, Object> p : params) {
            hasFile |= "file".equals(p.get("type"));
            Map<String, Object> prop = paramSchema(p);
            if (truthy(p.get("description"))) {
                prop.put("description", p.get("description"));
            }
            props.put(str(p.get("name")), prop);
            if (truthy(p.get("required"))) {
                required.add(str(p.get("name")));
            }
        }
        Map<String, Object> schema = obj("type", "object", "properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        List<String> formTypes = new ArrayList<>();
        for (Object ct : consumes) {
            if (str(ct).contains("form")) {
                formTypes.add(str(ct));
            }
        }
        if (hasFile || formTypes.isEmpty()) {
            formTypes = List.of(hasFile ? "multipart/form-data"
                    : "application/x-www-form-urlencoded");
        }
        Map<String, Object> content = obj();
        for (String ct : formTypes) {
            content.put(ct, obj("schema", schema));
        }
        Map<String, Object> result = obj("content", content);
        if (!required.isEmpty()) {
            result.put("required", true);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> resp, List<Object> produces) {
        if (resp.containsKey("$ref")) {
            return (Map<String, Object>) refs(resp);
        }
        Map<String, Object> out = obj("description",
                resp.containsKey("description") ? resp.get("description") : "");
        if (resp.containsKey("schema")) {
            Object schema = schema(resp.get("schema"));
            Map<String, Object> examples = map(resp.get("examples"));
            Map<String, Object> content = obj();
            for (Object ct : produces) {
                Map<String, Object> media = obj("schema", schema);
                if (examples.containsKey(str(ct))) {
                    media.put("example", examples.get(str(ct)));
                }
                content.put(str(ct), media);
            }
            out.put("content", content);
        }
        if (truthy(resp.get("headers"))) {
            Map<String, Object> headers = obj();
            map(resp.get("headers")).forEach((name, raw) -> {
                Map<String, Object> h = map(raw);
                Map<String, Object> header = obj();
                if (h.containsKey("description")) {
                    header.put("description", h.get("description"));
                }
                header.put("schema", paramSchema(h));
                headers.put(name, header);
            });
            out.put("headers", headers);
        }
        return out;
    }
}
