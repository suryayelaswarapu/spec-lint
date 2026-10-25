package io.github.suryayelaswarapu.speclint.convert;

import static io.github.suryayelaswarapu.speclint.Json.obj;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.attr;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.child;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.children;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.nsOf;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.resolve;

import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.Naming;
import io.github.suryayelaswarapu.speclint.convert.SafeXml.QName;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * XML Schema (XSD 1.0) to JSON Schema 2020-12, as used by OpenAPI 3.1. Named types become
 * {@code components/schemas} entries emitted lazily, so only reachable types appear in the
 * output. See {@code docs/wsdl-mapping.md} for the mapping table.
 */
public final class XsdConverter {
    public static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    public static final String SOAPENC_NS = "http://schemas.xmlsoap.org/soap/encoding/";
    public static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    static final int MAX_DEPTH = 64;
    private static final Pattern COMPONENT_NAME = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern DECIMAL =
            Pattern.compile("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?\\s*$");
    private static final Pattern INTEGER = Pattern.compile("^\\s*[+-]?\\d+\\s*$");

    static final Map<String, Map<String, Object>> BUILTINS = new HashMap<>();

    static {
        for (String n : List.of("string", "normalizedString", "token", "language", "Name",
                "NCName", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "NMTOKEN", "NMTOKENS",
                "QName", "NOTATION", "gYear", "gYearMonth", "gMonth", "gMonthDay", "gDay")) {
            BUILTINS.put(n, obj("type", "string"));
        }
        BUILTINS.put("anyURI", obj("type", "string", "format", "uri"));
        BUILTINS.put("boolean", obj("type", "boolean"));
        BUILTINS.put("decimal", obj("type", "number"));
        BUILTINS.put("float", obj("type", "number", "format", "float"));
        BUILTINS.put("double", obj("type", "number", "format", "double"));
        BUILTINS.put("integer", obj("type", "integer"));
        BUILTINS.put("int", obj("type", "integer", "format", "int32"));
        BUILTINS.put("long", obj("type", "integer", "format", "int64"));
        BUILTINS.put("short", obj("type", "integer", "minimum", -32768L, "maximum", 32767L));
        BUILTINS.put("byte", obj("type", "integer", "minimum", -128L, "maximum", 127L));
        BUILTINS.put("nonNegativeInteger", obj("type", "integer", "minimum", 0L));
        BUILTINS.put("positiveInteger", obj("type", "integer", "minimum", 1L));
        BUILTINS.put("nonPositiveInteger", obj("type", "integer", "maximum", 0L));
        BUILTINS.put("negativeInteger", obj("type", "integer", "maximum", -1L));
        BUILTINS.put("unsignedLong", obj("type", "integer", "format", "int64", "minimum", 0L));
        BUILTINS.put("unsignedInt", obj("type", "integer", "format", "int64", "minimum", 0L,
                "maximum", 4294967295L));
        BUILTINS.put("unsignedShort", obj("type", "integer", "minimum", 0L, "maximum", 65535L));
        BUILTINS.put("unsignedByte", obj("type", "integer", "minimum", 0L, "maximum", 255L));
        BUILTINS.put("date", obj("type", "string", "format", "date"));
        BUILTINS.put("dateTime", obj("type", "string", "format", "date-time"));
        BUILTINS.put("time", obj("type", "string", "format", "time"));
        BUILTINS.put("duration", obj("type", "string", "format", "duration"));
        BUILTINS.put("base64Binary", obj("type", "string", "contentEncoding", "base64"));
        BUILTINS.put("hexBinary", obj("type", "string", "pattern", "^([0-9a-fA-F]{2})*$"));
        BUILTINS.put("anyType", obj());
        BUILTINS.put("anySimpleType", obj());
    }

    private static final Map<String, String> NUMERIC_FACETS = Map.of(
            "minInclusive", "minimum", "maxInclusive", "maximum",
            "minExclusive", "exclusiveMinimum", "maxExclusive", "exclusiveMaximum");

    private final Consumer<String> warn;
    private final Set<String> warned = new HashSet<>();
    final Map<QName, Element> elements = new HashMap<>();
    final Map<QName, Element> complexTypes = new HashMap<>();
    final Map<QName, Element> simpleTypes = new HashMap<>();
    final Map<QName, Element> groups = new HashMap<>();
    final Map<QName, Element> attributeGroups = new HashMap<>();
    final Map<QName, Element> attributes = new HashMap<>();
    private final Map<String, Object> components = new LinkedHashMap<>();
    private final Map<String, String> names = new HashMap<>();

    public XsdConverter(Consumer<String> warn) {
        this.warn = warn;
    }

    public Map<String, Object> components() {
        return components;
    }

    // --------------------------------------------------------------- indexing

    public void addSchema(Element schema) {
        String tns = attr(schema, "targetNamespace", "");
        for (Element c : children(schema)) {
            String name = attr(c, "name");
            if (!XSD_NS.equals(nsOf(c)) || name == null || name.isEmpty()) {
                continue;
            }
            Map<QName, Element> table = switch (c.getLocalName()) {
                case "element" -> elements;
                case "complexType" -> complexTypes;
                case "simpleType" -> simpleTypes;
                case "group" -> groups;
                case "attributeGroup" -> attributeGroups;
                case "attribute" -> attributes;
                default -> null;
            };
            if (table != null) {
                table.putIfAbsent(new QName(tns, name), c);
            }
        }
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            warn.accept(message);
        }
    }

    // ------------------------------------------------------------- components

    private record NameResult(String name, boolean isNew) {}

    private NameResult componentName(String kind, QName qn) {
        String key = kind + "|" + qn.ns() + "|" + qn.local();
        if (names.containsKey(key)) {
            return new NameResult(names.get(key), false);
        }
        String base = Naming.toPascal(COMPONENT_NAME.matcher(qn.local()).replaceAll("_"));
        if (base.isEmpty()) {
            base = "Schema";
        }
        String name = Naming.unique(base, components.keySet());
        names.put(key, name);
        components.put(name, obj()); // placeholder: supports recursive types
        return new NameResult(name, true);
    }

    /** Register a synthetic component (e.g. an RPC request wrapper). */
    public String newComponent(String preferred, Map<String, Object> schema) {
        String name = Naming.unique(Naming.toPascal(preferred), components.keySet());
        components.put(name, schema);
        return name;
    }

    public static Map<String, Object> ref(String name) {
        return obj("$ref", "#/components/schemas/" + name);
    }

    // ------------------------------------------------------------- public API

    /** Schema for a type QName: builtins inline, named types as {@code $ref}. */
    public Map<String, Object> typeRef(QName qn) {
        if (qn == null) {
            return obj();
        }
        if ((XSD_NS.equals(qn.ns()) || SOAPENC_NS.equals(qn.ns()))
                && BUILTINS.containsKey(qn.local())) {
            return new LinkedHashMap<>(BUILTINS.get(qn.local()));
        }
        if (SOAPENC_NS.equals(qn.ns()) && qn.local().equals("Array")) {
            return obj("type", "array");
        }
        if (complexTypes.containsKey(qn)) {
            NameResult r = componentName("type", qn);
            if (r.isNew()) {
                Element el = complexTypes.get(qn);
                Map<String, Object> schema = complex(el, 0);
                describe(schema, el);
                components.put(r.name(), schema);
            }
            return ref(r.name());
        }
        if (simpleTypes.containsKey(qn)) {
            NameResult r = componentName("type", qn);
            if (r.isNew()) {
                Element el = simpleTypes.get(qn);
                Map<String, Object> schema = simple(el, 0);
                describe(schema, el);
                components.put(r.name(), schema);
            }
            return ref(r.name());
        }
        warnOnce("Unknown XSD type {" + qn.ns() + "}" + qn.local()
                + "; mapped to an unconstrained schema");
        return obj();
    }

    /** Schema for a global element QName (document/literal message parts). */
    public Map<String, Object> elementRef(QName qn) {
        if (qn == null || !elements.containsKey(qn)) {
            if (qn != null) {
                warnOnce("Unknown XSD element {" + qn.ns() + "}" + qn.local() + "; mapped to {}");
            }
            return obj();
        }
        Element el = elements.get(qn);
        boolean inline = child(el, XSD_NS, "complexType") != null
                || child(el, XSD_NS, "simpleType") != null;
        if (!inline && attr(el, "type") != null && !"true".equals(attr(el, "nillable"))) {
            return typeRef(resolve(el, attr(el, "type")));
        }
        NameResult r = componentName("element", qn);
        if (r.isNew()) {
            components.put(r.name(), elementBody(el, 0));
        }
        return ref(r.name());
    }

    // ----------------------------------------------------------- declarations

    /** Text of {@code xsd:annotation/xsd:documentation} or {@code wsdl:documentation}. */
    public static String documentation(Element e) {
        Element annotation = child(e, XSD_NS, "annotation");
        if (annotation != null) {
            Element doc = child(annotation, XSD_NS, "documentation");
            String text = doc == null ? "" : collapse(doc.getTextContent());
            if (!text.isEmpty()) {
                return text;
            }
        }
        Element wdoc = child(e, WSDL_NS, "documentation");
        if (wdoc != null) {
            String text = collapse(wdoc.getTextContent());
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static String collapse(String text) {
        return String.join(" ", text.trim().split("\\s+")).trim();
    }

    private Map<String, Object> describe(Map<String, Object> schema, Element e) {
        String doc = documentation(e);
        if (doc != null && !schema.containsKey("$ref")) {
            schema.putIfAbsent("description", doc);
        }
        return schema;
    }

    private static int[] occurs(Element e) {
        int lo = parseIntOr(attr(e, "minOccurs", "1"), 1);
        String hiRaw = attr(e, "maxOccurs", "1");
        int hi = hiRaw.equals("unbounded") ? -1 : parseIntOr(hiRaw, 1);
        return new int[] {lo, hi};
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Map<String, Object> addNull(Map<String, Object> schema) {
        if (schema.containsKey("type") && !schema.containsKey("$ref")) {
            Object t = schema.get("type");
            List<Object> types = t instanceof List<?> l ? new ArrayList<>(l)
                    : new ArrayList<>(List.of(t));
            types.add("null");
            schema.put("type", types);
            return schema;
        }
        return obj("anyOf", new ArrayList<>(List.of(schema, obj("type", "null"))));
    }

    private Map<String, Object> elementBody(Element el, int depth) {
        Element ct = child(el, XSD_NS, "complexType");
        Element st = child(el, XSD_NS, "simpleType");
        String primitive = null;
        Map<String, Object> schema;
        if (ct != null) {
            schema = complex(ct, depth + 1);
        } else if (st != null) {
            schema = simple(st, depth + 1);
        } else if (attr(el, "type") != null) {
            QName typeQn = resolve(el, attr(el, "type"));
            schema = typeRef(typeQn);
            primitive = primitive(typeQn, 0);
        } else {
            schema = obj();
        }
        applyValueConstraints(schema, el, primitive);
        if ("true".equals(attr(el, "nillable"))) {
            schema = addNull(schema);
        }
        return describe(schema, el);
    }

    private void applyValueConstraints(Map<String, Object> schema, Element node, String prim) {
        if (attr(node, "default") != null) {
            schema.put("default", typed(schema, attr(node, "default"), prim));
        }
        if (attr(node, "fixed") != null) {
            schema.put("const", typed(schema, attr(node, "fixed"), prim));
        }
    }

    private record Particle(String name, Map<String, Object> schema, boolean required) {}

    private Particle elementParticle(Element el, int depth) {
        int[] occ = occurs(el);
        String name;
        Map<String, Object> schema;
        if (attr(el, "ref") != null) {
            QName qn = resolve(el, attr(el, "ref"));
            name = qn != null ? qn.local() : "value";
            schema = elementRef(qn);
        } else {
            name = attr(el, "name", "value");
            schema = elementBody(el, depth);
        }
        if (occ[1] == -1 || occ[1] > 1) {
            Map<String, Object> arr = obj("type", "array", "items", schema);
            if (occ[0] > 1) {
                arr.put("minItems", (long) occ[0]);
            }
            if (occ[1] != -1) {
                arr.put("maxItems", (long) occ[1]);
            }
            schema = arr;
        }
        return new Particle(name, schema, occ[0] > 0);
    }

    /** Accumulates object properties while walking XSD particles. */
    static final class ObjectBuilder {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();
        final List<List<List<String>>> choices = new ArrayList<>();

        String add(String name, Map<String, Object> schema, boolean isRequired) {
            String key = properties.containsKey(name)
                    ? Naming.unique("@" + name, properties.keySet()) : name;
            properties.put(key, schema);
            if (isRequired) {
                required.add(key);
            }
            return key;
        }

        Map<String, Object> build() {
            Map<String, Object> out = obj("type", "object");
            if (!properties.isEmpty()) {
                out.put("properties", properties);
            }
            if (!required.isEmpty()) {
                out.put("required", required);
            }
            List<Object> groups = new ArrayList<>();
            for (List<List<String>> choice : choices) {
                if (choice.stream().allMatch(b -> !b.isEmpty())) {
                    List<Object> oneOf = new ArrayList<>();
                    choice.forEach(b -> oneOf.add(obj("required", new ArrayList<>(b))));
                    groups.add(obj("oneOf", oneOf));
                }
            }
            if (groups.size() == 1) {
                out.putAll(Json.map(groups.get(0)));
            } else if (!groups.isEmpty()) {
                out.put("allOf", groups);
            }
            return out;
        }
    }

    private void walk(List<Element> kids, ObjectBuilder obj, int depth) {
        if (depth > MAX_DEPTH) {
            warnOnce("XSD nesting deeper than 64 levels was truncated");
            return;
        }
        for (Element c : kids) {
            if (!XSD_NS.equals(nsOf(c))) {
                continue;
            }
            switch (c.getLocalName()) {
                case "element" -> {
                    Particle p = elementParticle(c, depth);
                    obj.add(p.name(), p.schema(), p.required());
                }
                case "sequence", "all" -> {
                    if (occurs(c)[1] != 1) {
                        warnOnce("Repeating <sequence> groups are flattened into one object");
                    }
                    walk(children(c), obj, depth + 1);
                }
                case "choice" -> choice(c, obj, depth);
                case "group" -> {
                    Element g = groups.get(resolveOrEmpty(c, attr(c, "ref")));
                    if (g != null) {
                        walk(children(g), obj, depth + 1);
                    }
                }
                case "attribute" -> attribute(c, obj);
                case "attributeGroup" -> {
                    Element g = attributeGroups.get(resolveOrEmpty(c, attr(c, "ref")));
                    if (g != null) {
                        walk(children(g), obj, depth + 1);
                    }
                }
                default -> { /* annotation, any, anyAttribute: nothing to map */ }
            }
        }
    }

    private static QName resolveOrEmpty(Element e, String value) {
        QName qn = resolve(e, value);
        return qn == null ? new QName("", "") : qn;
    }

    private void choice(Element choice, ObjectBuilder obj, int depth) {
        List<List<String>> branches = new ArrayList<>();
        for (Element branch : children(choice)) {
            if (!XSD_NS.equals(nsOf(branch)) || branch.getLocalName().equals("annotation")) {
                continue;
            }
            ObjectBuilder sub = new ObjectBuilder();
            walk(List.of(branch), sub, depth + 1);
            List<String> req = new ArrayList<>();
            for (Map.Entry<String, Object> e : sub.properties.entrySet()) {
                @SuppressWarnings("unchecked")
                String key = obj.add(e.getKey(), (Map<String, Object>) e.getValue(), false);
                if (sub.required.contains(e.getKey())) {
                    req.add(key);
                }
            }
            branches.add(req);
        }
        if (!branches.isEmpty() && !"0".equals(attr(choice, "minOccurs", "1"))) {
            obj.choices.add(branches);
        }
    }

    private void attribute(Element attrEl, ObjectBuilder obj) {
        Element a = attrEl;
        if (attr(a, "ref") != null) {
            QName qn = resolve(a, attr(a, "ref"));
            Element g = qn == null ? null : attributes.get(qn);
            if (g == null) {
                return;
            }
            a = g;
        }
        String name = attr(a, "name");
        if (name == null || name.isEmpty() || "prohibited".equals(attr(a, "use"))) {
            return;
        }
        Element st = child(a, XSD_NS, "simpleType");
        String prim = null;
        Map<String, Object> schema;
        if (st != null) {
            schema = simple(st, 1);
        } else if (attr(a, "type") != null) {
            QName typeQn = resolve(a, attr(a, "type"));
            schema = typeRef(typeQn);
            prim = primitive(typeQn, 0);
        } else {
            schema = obj("type", "string");
        }
        applyValueConstraints(schema, a, prim);
        describe(schema, a);
        obj.add(name, schema, "required".equals(attr(a, "use")));
    }

    // ------------------------------------------------------------------ types

    private Map<String, Object> complex(Element ct, int depth) {
        if ("true".equals(attr(ct, "mixed"))) {
            warnOnce("Mixed-content complex types: text content is not represented");
        }
        Element cc = child(ct, XSD_NS, "complexContent");
        Element sc = child(ct, XSD_NS, "simpleContent");
        if (cc != null) {
            return complexContent(cc, depth);
        }
        if (sc != null) {
            return simpleContent(sc, depth);
        }
        ObjectBuilder obj = new ObjectBuilder();
        walk(children(ct), obj, depth + 1);
        return obj.build();
    }

    private Map<String, Object> complexContent(Element cc, int depth) {
        Element ext = child(cc, XSD_NS, "extension");
        Element res = child(cc, XSD_NS, "restriction");
        Element node = ext != null ? ext : res;
        if (node == null) {
            return obj("type", "object");
        }
        QName base = resolve(node, attr(node, "base"));
        if (res != null && new QName(SOAPENC_NS, "Array").equals(base)) {
            return soapencArray(res, depth);
        }
        ObjectBuilder obj = new ObjectBuilder();
        walk(children(node), obj, depth + 1);
        Map<String, Object> own = obj.build();
        if (ext != null && base != null) {
            Map<String, Object> baseSchema = typeRef(base);
            if (Json.truthy(own.get("properties"))) {
                return obj("allOf", new ArrayList<>(List.of(baseSchema, own)));
            }
            return baseSchema;
        }
        return own;
    }

    /** {@code soapenc:Array} restriction (rpc/encoded style) to a JSON array. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> soapencArray(Element res, int depth) {
        for (Element a : SafeXml.descendants(res, XSD_NS, "attribute")) {
            String raw = a.hasAttributeNS(WSDL_NS, "arrayType")
                    ? a.getAttributeNS(WSDL_NS, "arrayType") : null;
            if (raw != null && !raw.isEmpty()) {
                QName item = resolve(a, raw.split("\\[", 2)[0]);
                return obj("type", "array", "items", typeRef(item));
            }
        }
        ObjectBuilder obj = new ObjectBuilder();
        walk(children(res), obj, depth + 1);
        List<Object> props = new ArrayList<>(obj.properties.values());
        if (props.size() == 1 && "array".equals(Json.map(props.get(0)).get("type"))) {
            return new LinkedHashMap<>((Map<String, Object>) props.get(0));
        }
        return obj("type", "array");
    }

    private Map<String, Object> simpleContent(Element sc, int depth) {
        Element node = child(sc, XSD_NS, "extension");
        if (node == null) {
            node = child(sc, XSD_NS, "restriction");
        }
        if (node == null) {
            return obj("type", "object");
        }
        ObjectBuilder obj = new ObjectBuilder();
        obj.add("value", typeRef(resolve(node, attr(node, "base"))), true);
        walk(children(node), obj, depth + 1);
        warnOnce("simpleContent text is mapped to a 'value' property");
        return obj.build();
    }

    private Map<String, Object> simple(Element st, int depth) {
        if (depth > MAX_DEPTH) {
            return obj();
        }
        Element res = child(st, XSD_NS, "restriction");
        Element lst = child(st, XSD_NS, "list");
        Element uni = child(st, XSD_NS, "union");
        if (res != null) {
            return restriction(res, depth);
        }
        if (lst != null) {
            Element inner = child(lst, XSD_NS, "simpleType");
            Map<String, Object> items = inner != null ? simple(inner, depth + 1)
                    : typeRef(resolve(lst, attr(lst, "itemType")));
            warnOnce("xsd:list (space-separated values) is mapped to a JSON array");
            return obj("type", "array", "items", items);
        }
        if (uni != null) {
            List<Object> members = new ArrayList<>();
            String memberTypes = attr(uni, "memberTypes", "").trim();
            if (!memberTypes.isEmpty()) {
                for (String m : memberTypes.split("\\s+")) {
                    members.add(typeRef(resolve(uni, m)));
                }
            }
            for (Element s : SafeXml.children(uni, XSD_NS, "simpleType")) {
                members.add(simple(s, depth + 1));
            }
            return members.isEmpty() ? obj() : obj("anyOf", members);
        }
        return obj();
    }

    /** Follow restriction chains back to a JSON primitive type name. */
    String primitive(QName qn, int depth) {
        if (qn == null || depth > MAX_DEPTH) {
            return null;
        }
        if (XSD_NS.equals(qn.ns()) || SOAPENC_NS.equals(qn.ns())) {
            Object t = BUILTINS.getOrDefault(qn.local(), Map.of()).get("type");
            return t instanceof String s ? s : null;
        }
        Element g = simpleTypes.get(qn);
        if (g == null) {
            return null;
        }
        Element res = child(g, XSD_NS, "restriction");
        if (res == null) {
            return null;
        }
        return primitive(resolve(res, attr(res, "base")), depth + 1);
    }

    /** Convert a lexical XSD value to the JSON value of the schema's primitive type. */
    static Object typed(Map<String, Object> schema, String raw, String primitive) {
        Object kind = primitive != null ? primitive : schema.get("type");
        if (kind instanceof List<?> l) {
            kind = l.stream().filter(k -> !"null".equals(k)).findFirst().orElse(null);
        }
        if ("integer".equals(kind)) {
            return INTEGER.matcher(raw).matches() ? integer(raw.trim()) : raw;
        }
        if ("number".equals(kind)) {
            if (!DECIMAL.matcher(raw).matches()) {
                return raw;
            }
            boolean isFloat = raw.contains(".") || raw.contains("e") || raw.contains("E");
            return isFloat ? (Object) Double.parseDouble(raw.trim()) : integer(raw.trim());
        }
        if ("boolean".equals(kind)) {
            String v = raw.trim();
            return v.equals("true") || v.equals("1");
        }
        return raw;
    }

    private static Object integer(String raw) {
        BigInteger value = new BigInteger(raw.startsWith("+") ? raw.substring(1) : raw);
        return value.bitLength() < 64 ? (Object) value.longValue() : value;
    }

    private Map<String, Object> restriction(Element res, int depth) {
        Element inner = child(res, XSD_NS, "simpleType");
        QName baseQn = resolve(res, attr(res, "base"));
        Map<String, Object> base = inner != null ? simple(inner, depth + 1) : typeRef(baseQn);
        String prim = primitive(baseQn, 0);
        if (prim == null && base.get("type") instanceof String s) {
            prim = s;
        }
        Map<String, Object> schema = new LinkedHashMap<>(base);
        List<Object> enumValues = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (Element facet : children(res)) {
            String value = attr(facet, "value");
            if (!XSD_NS.equals(nsOf(facet)) || value == null) {
                continue;
            }
            String local = facet.getLocalName();
            switch (local) {
                case "enumeration" -> enumValues.add(typed(schema, value, prim));
                case "pattern" -> patterns.add(value);
                case "minLength", "maxLength" -> schema.put(local, (long) parseIntOr(value, 0));
                case "length" -> {
                    schema.put("minLength", (long) parseIntOr(value, 0));
                    schema.put("maxLength", (long) parseIntOr(value, 0));
                }
                default -> {
                    if (NUMERIC_FACETS.containsKey(local)) {
                        schema.put(NUMERIC_FACETS.get(local),
                                typed(schema, value, prim != null ? prim : "number"));
                    }
                }
            }
        }
        if (!enumValues.isEmpty()) {
            schema.put("enum", enumValues);
        }
        if (!patterns.isEmpty()) {
            // XSD patterns are implicitly anchored; JSON Schema patterns are not.
            schema.put("pattern", "^(?:" + String.join("|", patterns) + ")$");
        }
        return schema;
    }
}
