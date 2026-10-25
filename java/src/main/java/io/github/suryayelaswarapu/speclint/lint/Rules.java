package io.github.suryayelaswarapu.speclint.lint;

import static io.github.suryayelaswarapu.speclint.Json.list;
import static io.github.suryayelaswarapu.speclint.Json.map;
import static io.github.suryayelaswarapu.speclint.Json.str;
import static io.github.suryayelaswarapu.speclint.Json.truthy;
import static io.github.suryayelaswarapu.speclint.lint.Walk.path;

import io.github.suryayelaswarapu.speclint.Naming;
import io.github.suryayelaswarapu.speclint.lint.Rule.Hit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in ruleset. The catalogue (ids, severities, intent) lives in {@code docs/rules.md}
 * and is shared by the Python, Java and Go implementations.
 */
public final class Rules {
    private static final Severity E = Severity.ERROR;
    private static final Severity W = Severity.WARNING;
    private static final Severity I = Severity.INFO;
    static final Pattern SEMVER =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$");
    static final Pattern PATH_SEGMENT =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*(?:\\.[a-z0-9]+)?$");
    static final Pattern PATH_TEMPLATE = Pattern.compile("\\{([^}/]+)\\}");
    static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]");

    /** All rules, keyed by id, in registration order. */
    public static final Map<String, Rule> ALL;

    static {
        Map<String, Rule> m = new LinkedHashMap<>();
        add(m, "no-remote-refs", E,
                "$ref must point inside the document; remote refs are never fetched.", false,
                Rules::noRemoteRefs);
        add(m, "ref-resolves", E, "Every local $ref must resolve to an existing node.", false,
                Rules::refResolves);
        add(m, "oas3-valid-schema", E,
                "Document must be valid against the OpenAPI 3.x specification.", false,
                OasValidator::check);
        add(m, "openapi-version-3-1", W,
                "Target OpenAPI 3.1 (full JSON Schema 2020-12 support).", false,
                Rules::openapiVersion);
        add(m, "info-description", W, "info.description explains what the API is for.", false,
                d -> infoField(d, "description"));
        add(m, "info-contact", W, "info.contact tells consumers who owns the API.", false,
                d -> infoField(d, "contact"));
        add(m, "info-license", I, "info.license states the terms of use.", false,
                d -> infoField(d, "license"));
        add(m, "info-version-semver", W,
                "info.version should be semantic (MAJOR.MINOR.PATCH).", false,
                Rules::infoVersion);
        add(m, "servers-defined", E, "At least one server URL must be declared.", false,
                Rules::serversDefined);
        add(m, "servers-https", W,
                "Absolute server URLs must use https (localhost excepted).", false,
                Rules::serversHttps);
        add(m, "security-defined", W,
                "Operations should be protected by a declared security scheme.", false,
                Rules::securityDefined);
        add(m, "operation-id-required", E, "Every operation needs an operationId.", true,
                Rules::operationIdRequired);
        add(m, "operation-id-unique", E, "operationId values must be unique.", false,
                Rules::operationIdUnique);
        add(m, "operation-id-camel-case", W, "operationId should be camelCase.", true,
                Rules::operationIdCamel);
        add(m, "operation-summary", W, "Every operation needs a short summary.", false,
                Rules::operationSummary);
        add(m, "operation-tags", W, "Every operation needs at least one tag.", true,
                Rules::operationTags);
        add(m, "operation-tag-defined", W,
                "Tags used by operations are declared at the top level.", true,
                Rules::operationTagDefined);
        add(m, "operation-success-response", E,
                "Every operation defines a 2xx or 3xx response.", false,
                Rules::operationSuccess);
        add(m, "operation-error-response", W,
                "Every operation documents its errors (4xx/5xx/default).", true,
                Rules::operationError);
        add(m, "error-response-schema", W,
                "Error responses must describe their body with a schema.", true,
                Rules::errorResponseSchema);
        add(m, "parameter-description", I, "Parameters should be described.", false,
                Rules::parameterDescription);
        add(m, "path-kebab-case", W, "Static path segments are lower kebab-case.", false,
                Rules::pathKebabCase);
        add(m, "path-no-trailing-slash", W, "Paths must not end with a slash.", false,
                Rules::pathTrailingSlash);
        add(m, "path-params-defined", E, "Path templates and path parameters must match.",
                false, Rules::pathParamsDefined);
        add(m, "schema-name-pascal-case", W, "components.schemas names are PascalCase.", true,
                Rules::schemaNamePascal);
        add(m, "property-camel-case", W, "Schema property names are camelCase.", false,
                Rules::propertyCamelCase);
        add(m, "no-inline-object-schemas", W,
                "Request/response bodies reference named schemas instead of inline objects.",
                true, Rules::noInlineObjects);
        add(m, "no-legacy-nullable", W,
                "Use type: [T, 'null'] instead of nullable / x-nullable.", true,
                Rules::noLegacyNullable);
        add(m, "no-unused-components", W, "Every component is referenced somewhere.", false,
                Rules::noUnusedComponents);
        ALL = Collections.unmodifiableMap(m);
    }

    private Rules() {}

    private static void add(Map<String, Rule> m, String id, Severity severity, String desc,
            boolean fixable, Function<Map<String, Object>, List<Hit>> check) {
        m.put(id, new Rule(id, severity, desc, fixable, check));
    }

    private static String upper(String method) {
        return method.toUpperCase(Locale.ROOT);
    }

    static boolean isErrorCode(String code) {
        return code.equals("default") || code.startsWith("4") || code.startsWith("5");
    }

    // -------------------------------------------------------------- references

    static List<Hit> noRemoteRefs(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Ref r : Walk.refs(doc)) {
            if (!r.ref().startsWith("#")) {
                String kind = r.ref().contains("://") ? "remote URL" : "external file";
                out.add(new Hit("$ref to " + kind + " '" + r.ref()
                        + "' is not allowed; bundle it into this document", r.path()));
            }
        }
        return out;
    }

    static List<Hit> refResolves(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Ref r : Walk.refs(doc)) {
            if (r.ref().startsWith("#") && Walk.resolvePointer(doc, r.ref()) == null) {
                out.add(new Hit("$ref '" + r.ref() + "' does not resolve", r.path()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ info/servers

    static List<Hit> openapiVersion(Map<String, Object> doc) {
        String version = str(doc.get("openapi"));
        if (!version.startsWith("3.1")) {
            return List.of(new Hit("openapi is '" + version + "'; upgrade to 3.1.x",
                    List.of("openapi")));
        }
        return List.of();
    }

    static List<Hit> infoField(Map<String, Object> doc, String field) {
        if (!truthy(map(doc.get("info")).get(field))) {
            return List.of(new Hit("info." + field + " is missing", List.of("info")));
        }
        return List.of();
    }

    static List<Hit> infoVersion(Map<String, Object> doc) {
        String version = str(map(doc.get("info")).get("version"));
        if (!SEMVER.matcher(version).matches()) {
            return List.of(new Hit("info.version '" + version + "' is not semantic versioning",
                    List.of("info", "version")));
        }
        return List.of();
    }

    static List<Hit> serversDefined(Map<String, Object> doc) {
        if (list(doc.get("servers")).isEmpty()) {
            return List.of(new Hit("No servers defined", List.of()));
        }
        return List.of();
    }

    static List<Hit> serversHttps(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        List<Object> servers = list(doc.get("servers"));
        for (int i = 0; i < servers.size(); i++) {
            String url = str(map(servers.get(i)).get("url"));
            if (url.startsWith("http://")
                    && LOCAL_HOSTS.stream().noneMatch(h -> url.substring(7).startsWith(h))) {
                out.add(new Hit("Server URL '" + url + "' is not https",
                        List.of("servers", String.valueOf(i), "url")));
            }
        }
        return out;
    }

    static List<Hit> securityDefined(Map<String, Object> doc) {
        if (!truthy(map(doc.get("components")).get("securitySchemes"))) {
            return List.of(new Hit("No components.securitySchemes defined", List.of()));
        }
        if (truthy(doc.get("security"))) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            if (!o.op().containsKey("security")) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no security requirement",
                        List.of("paths", o.path(), o.method())));
            }
        }
        return out;
    }

    // -------------------------------------------------------------- operations

    static List<Hit> operationIdRequired(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            if (!truthy(o.op().get("operationId"))) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no operationId",
                        List.of("paths", o.path(), o.method())));
            }
        }
        return out;
    }

    static List<Hit> operationIdUnique(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Walk.Op o : Walk.operations(doc)) {
            Object id = o.op().get("operationId");
            if (truthy(id) && !seen.add(str(id))) {
                out.add(new Hit("Duplicate operationId '" + id + "'",
                        List.of("paths", o.path(), o.method(), "operationId")));
            }
        }
        return out;
    }

    static List<Hit> operationIdCamel(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            Object id = o.op().get("operationId");
            if (truthy(id) && !Naming.CAMEL_CASE.matcher(str(id)).matches()) {
                out.add(new Hit("operationId '" + id + "' is not camelCase",
                        List.of("paths", o.path(), o.method(), "operationId")));
            }
        }
        return out;
    }

    static List<Hit> operationSummary(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            if (!truthy(o.op().get("summary"))) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no summary",
                        List.of("paths", o.path(), o.method())));
            }
        }
        return out;
    }

    static List<Hit> operationTags(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            if (!truthy(o.op().get("tags"))) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no tags",
                        List.of("paths", o.path(), o.method())));
            }
        }
        return out;
    }

    static List<Hit> operationTagDefined(Map<String, Object> doc) {
        Set<Object> declared = new HashSet<>();
        for (Object t : list(doc.get("tags"))) {
            if (t instanceof Map<?, ?>) {
                declared.add(map(t).get("name"));
            }
        }
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            List<Object> tags = list(o.op().get("tags"));
            for (int i = 0; i < tags.size(); i++) {
                if (!declared.contains(tags.get(i))) {
                    out.add(new Hit("Tag '" + tags.get(i) + "' is not declared in top-level tags",
                            List.of("paths", o.path(), o.method(), "tags", String.valueOf(i))));
                }
            }
        }
        return out;
    }

    static List<Hit> operationSuccess(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            boolean ok = map(o.op().get("responses")).keySet().stream()
                    .anyMatch(c -> c.startsWith("2") || c.startsWith("3"));
            if (!ok) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no success response",
                        List.of("paths", o.path(), o.method(), "responses")));
            }
        }
        return out;
    }

    static List<Hit> operationError(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            boolean ok = map(o.op().get("responses")).keySet().stream()
                    .anyMatch(Rules::isErrorCode);
            if (!ok) {
                out.add(new Hit(upper(o.method()) + " " + o.path() + " has no error response",
                        List.of("paths", o.path(), o.method(), "responses")));
            }
        }
        return out;
    }

    static boolean hasSchema(Object content) {
        return content instanceof Map<?, ?> m && m.values().stream()
                .anyMatch(v -> v instanceof Map<?, ?> mv && mv.containsKey("schema"));
    }

    static List<Hit> errorResponseSchema(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            map(o.op().get("responses")).forEach((code, resp) -> {
                if (!isErrorCode(code)) {
                    return;
                }
                Object resolved = Walk.deref(doc, resp);
                Object content = resolved instanceof Map<?, ?> ? map(resolved).get("content")
                        : null;
                if (!hasSchema(content)) {
                    out.add(new Hit(upper(o.method()) + " " + o.path() + " response " + code
                            + " has no schema",
                            List.of("paths", o.path(), o.method(), "responses", code)));
                }
            });
        }
        return out;
    }

    static List<Hit> parameterDescription(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            List<Object> params = list(o.op().get("parameters"));
            for (int i = 0; i < params.size(); i++) {
                Map<String, Object> p = map(params.get(i));
                if (params.get(i) instanceof Map<?, ?> && !p.containsKey("$ref")
                        && !truthy(p.get("description"))) {
                    out.add(new Hit("Parameter '" + p.get("name") + "' has no description",
                            List.of("paths", o.path(), o.method(), "parameters",
                                    String.valueOf(i))));
                }
            }
        }
        map(map(doc.get("components")).get("parameters")).forEach((name, p) -> {
            if (p instanceof Map<?, ?> && !truthy(map(p).get("description"))) {
                out.add(new Hit("Parameter '" + map(p).get("name") + "' has no description",
                        List.of("components", "parameters", name)));
            }
        });
        return out;
    }

    // ------------------------------------------------------------------- paths

    static List<Hit> pathKebabCase(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (String path : map(doc.get("paths")).keySet()) {
            for (String segment : path.split("/")) {
                if (!segment.isEmpty() && !segment.startsWith("{")
                        && !PATH_SEGMENT.matcher(segment).matches()) {
                    out.add(new Hit("Path segment '" + segment + "' in '" + path
                            + "' is not kebab-case", List.of("paths", path)));
                    break;
                }
            }
        }
        return out;
    }

    static List<Hit> pathTrailingSlash(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (String path : map(doc.get("paths")).keySet()) {
            if (path.length() > 1 && path.endsWith("/")) {
                out.add(new Hit("Path '" + path + "' ends with a slash", List.of("paths", path)));
            }
        }
        return out;
    }

    static List<Hit> pathParamsDefined(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Op o : Walk.operations(doc)) {
            Set<String> template = new TreeSet<>();
            Matcher m = PATH_TEMPLATE.matcher(o.path());
            while (m.find()) {
                template.add(m.group(1));
            }
            Set<String> declared = new TreeSet<>();
            List<Object> all = new ArrayList<>(list(o.item().get("parameters")));
            all.addAll(list(o.op().get("parameters")));
            for (Object raw : all) {
                Object p = Walk.deref(doc, raw);
                if (p instanceof Map<?, ?> && "path".equals(map(p).get("in"))) {
                    declared.add(str(map(p).get("name")));
                }
            }
            List<String> where = List.of("paths", o.path(), o.method());
            String label = upper(o.method()) + " " + o.path();
            for (String name : template) {
                if (!declared.contains(name)) {
                    out.add(new Hit(label + ": path parameter '" + name + "' is not declared",
                            where));
                }
            }
            for (String name : declared) {
                if (!template.contains(name)) {
                    out.add(new Hit(label + ": parameter '" + name + "' is not in the path",
                            where));
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- schemas

    static List<Hit> schemaNamePascal(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (String name : map(map(doc.get("components")).get("schemas")).keySet()) {
            if (!Naming.PASCAL_CASE.matcher(name).matches()) {
                out.add(new Hit("Schema name '" + name + "' is not PascalCase",
                        List.of("components", "schemas", name)));
            }
        }
        return out;
    }

    static List<Hit> propertyCamelCase(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        List<Object[]> roots = new ArrayList<>();
        map(map(doc.get("components")).get("schemas")).forEach((name, schema) ->
                roots.add(new Object[] {schema, List.of("components", "schemas", name)}));
        for (Walk.Media media : Walk.mediaSchemas(doc)) {
            roots.add(new Object[] {media.schema(), media.path()});
        }
        for (Object[] root : roots) {
            @SuppressWarnings("unchecked")
            List<String> base = (List<String>) root[1];
            for (Walk.Prop p : Walk.schemaProperties(root[0], base)) {
                if (!seen.contains(p.path()) && !Naming.CAMEL_CASE.matcher(p.name()).matches()) {
                    seen.add(p.path());
                    out.add(new Hit("Property '" + p.name() + "' is not camelCase", p.path()));
                }
            }
        }
        return out;
    }

    static List<Hit> noInlineObjects(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        for (Walk.Media m : Walk.mediaSchemas(doc)) {
            Map<String, Object> s = m.schema();
            if (!s.containsKey("$ref")
                    && ("object".equals(s.get("type")) || s.containsKey("properties"))) {
                String where = m.kind().equals("request") ? "request body"
                        : "response " + m.kind().substring(m.kind().indexOf(':') + 1);
                out.add(new Hit(m.label() + " " + where + " uses an inline object schema",
                        m.path()));
            }
        }
        return out;
    }

    static List<Hit> noLegacyNullable(Map<String, Object> doc) {
        List<Hit> out = new ArrayList<>();
        record Item(Object node, List<String> path) {}
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(doc, List.of()));
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            if (it.node() instanceof Map<?, ?> m) {
                for (String key : List.of("nullable", "x-nullable")) {
                    if (m.get(key) instanceof Boolean) {
                        out.add(new Hit("'" + key + "' is legacy syntax; use type: [T, 'null']",
                                path(it.path(), key)));
                    }
                }
                m.forEach((k, v) -> {
                    if (!"example".equals(k)) {
                        stack.push(new Item(v, path(it.path(), String.valueOf(k))));
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

    static List<Hit> noUnusedComponents(Map<String, Object> doc) {
        // Collect "section/escapedName" once, so the check is linear in refs + components.
        String prefix = "#/components/";
        Set<String> used = new HashSet<>();
        for (Walk.Ref r : Walk.refs(doc)) {
            if (r.ref().startsWith(prefix)) {
                String[] parts = r.ref().substring(prefix.length()).split("/", 3);
                if (parts.length >= 2) {
                    used.add(parts[0] + "/" + parts[1]);
                }
            }
        }
        Map<String, Object> comps = map(doc.get("components"));
        List<Hit> out = new ArrayList<>();
        for (String section : Walk.COMPONENT_SECTIONS) {
            for (String name : map(comps.get(section)).keySet()) {
                String key = section + "/" + name.replace("~", "~0").replace("/", "~1");
                if (!used.contains(key)) {
                    out.add(new Hit("components." + section + "." + name + " is never referenced",
                            List.of("components", section, name)));
                }
            }
        }
        return out;
    }
}
