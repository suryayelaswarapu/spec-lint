package io.github.suryayelaswarapu.speclint.convert;

import static io.github.suryayelaswarapu.speclint.Json.obj;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.attr;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.child;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.children;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.descendants;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.is;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.nsOf;
import static io.github.suryayelaswarapu.speclint.convert.SafeXml.resolve;
import static io.github.suryayelaswarapu.speclint.convert.XsdConverter.WSDL_NS;
import static io.github.suryayelaswarapu.speclint.convert.XsdConverter.XSD_NS;
import static io.github.suryayelaswarapu.speclint.convert.XsdConverter.documentation;

import io.github.suryayelaswarapu.speclint.Naming;
import io.github.suryayelaswarapu.speclint.SpecKind;
import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import io.github.suryayelaswarapu.speclint.convert.SafeXml.QName;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * WSDL 1.1 (SOAP 1.1/1.2) to OpenAPI 3.1. The output describes a JSON/REST facade for the SOAP
 * service: each operation becomes {@code POST /operation-name}, messages become JSON bodies,
 * every operation gets a {@code 500 SoapFault}, and SOAP details are kept under {@code x-soap}.
 */
public final class WsdlConverter {
    public static final String SOAP11_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    public static final String SOAP12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
    static final int DEFAULT_MAX_IMPORTED_DOCS = 50;

    static final Map<String, Object> SOAP_FAULT_SCHEMA = obj(
            "type", "object",
            "description", "SOAP Fault translated to JSON.",
            "required", List.of("faultcode", "faultstring"),
            "properties", obj(
                    "faultcode", obj("type", "string", "examples", List.of("soap:Server")),
                    "faultstring", obj("type", "string"),
                    "faultactor", obj("type", "string"),
                    "detail", obj("description", "Application-specific fault detail.")));

    private record Part(String name, QName element, QName type) {}

    private record Operation(String name, String doc, QName input, QName output,
            List<Map.Entry<String, QName>> faults) {}

    private record BindingOp(String soapAction, String style, String use) {}

    private record Binding(String name, QName portType, String soapVersion, String style,
            Map<String, BindingOp> ops) {}

    private record Port(String service, String serviceDoc, String name, QName binding,
            String address) {}

    private record Selected(Port port, Binding binding, QName portType) {}

    private final List<String> warnings = new ArrayList<>();
    private final Path baseDir;
    private final boolean allowLocalImports;
    private final XsdConverter xsd = new XsdConverter(warnings::add);
    private final Map<QName, List<Part>> messages = new LinkedHashMap<>();
    private final Map<QName, List<Operation>> portTypes = new LinkedHashMap<>();
    private final Map<QName, Binding> bindings = new LinkedHashMap<>();
    private final List<Port> ports = new ArrayList<>();
    private final Set<Path> loaded = new HashSet<>();
    private final int maxImportedDocs;
    private final long maxImportBytes;

    public WsdlConverter(Path baseDir, boolean allowLocalImports) {
        this(baseDir, allowLocalImports, DEFAULT_MAX_IMPORTED_DOCS, SpecIO.DEFAULT_MAX_BYTES);
    }

    /** Creates a converter with explicit limits on local XSD imports (count and file size). */
    public WsdlConverter(Path baseDir, boolean allowLocalImports, int maxImportedDocs,
            long maxImportBytes) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
        this.allowLocalImports = allowLocalImports;
        this.maxImportedDocs = maxImportedDocs;
        this.maxImportBytes = maxImportBytes;
    }

    // ---------------------------------------------------------------- loading

    private void loadSchemas(Element container) {
        List<Element> schemas = new ArrayList<>();
        if (is(container, XSD_NS, "schema")) {
            schemas.add(container);
        }
        schemas.addAll(descendants(container, XSD_NS, "schema"));
        for (Element schema : schemas) {
            xsd.addSchema(schema);
            List<Element> imports = new ArrayList<>(descendants(schema, XSD_NS, "import"));
            imports.addAll(descendants(schema, XSD_NS, "include"));
            for (Element imp : imports) {
                String location = attr(imp, "schemaLocation");
                if (location != null && !location.isEmpty()) {
                    loadExternalSchema(location);
                }
            }
        }
    }

    private void loadExternalSchema(String location) {
        if (location.contains("://") || location.startsWith("//")) {
            warnings.add("Remote schema import '" + location
                    + "' was not fetched (remote fetching is disabled)");
            return;
        }
        if (!allowLocalImports || baseDir == null) {
            warnings.add("Local schema import '" + location + "' skipped (imports disabled)");
            return;
        }
        Path target = baseDir.resolve(location).toAbsolutePath().normalize();
        if (!target.startsWith(baseDir)) {
            throw new SecurityException("Schema import '" + location
                    + "' escapes the input directory");
        }
        if (loaded.contains(target)) {
            return;
        }
        if (loaded.size() >= maxImportedDocs) {
            throw new SecurityException("More than " + maxImportedDocs
                    + " imported schema documents");
        }
        if (!Files.isRegularFile(target)) {
            warnings.add("Schema import '" + location + "' not found; skipped");
            return;
        }
        try {
            if (Files.size(target) > maxImportBytes) {
                throw new SecurityException("Imported schema '" + location
                        + "' exceeds the size limit");
            }
            loaded.add(target);
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            loadSchemas(SafeXml.parse(text));
        } catch (IOException e) {
            throw new InputException("Cannot read schema import '" + location + "'", e);
        }
    }

    private static Element w(Element parent, String local) {
        return child(parent, WSDL_NS, local);
    }

    private static List<Element> ws(Element parent, String local) {
        return children(parent, WSDL_NS, local);
    }

    private void index(Element root) {
        if (!is(root, WSDL_NS, "definitions")) {
            throw new InputException(
                    "Not a WSDL 1.1 document: root element must be wsdl:definitions");
        }
        String tns = attr(root, "targetNamespace", "");
        for (Element imp : ws(root, "import")) {
            warnings.add("wsdl:import of '" + attr(imp, "location")
                    + "' is not supported; inline it instead");
        }
        Element types = w(root, "types");
        if (types != null) {
            loadSchemas(types);
        }
        for (Element msg : ws(root, "message")) {
            List<Part> parts = new ArrayList<>();
            for (Element p : ws(msg, "part")) {
                parts.add(new Part(attr(p, "name", "part"), resolve(p, attr(p, "element")),
                        resolve(p, attr(p, "type"))));
            }
            messages.put(new QName(tns, attr(msg, "name", "")), parts);
        }
        for (Element pt : ws(root, "portType")) {
            List<Operation> ops = new ArrayList<>();
            for (Element op : ws(pt, "operation")) {
                Element in = w(op, "input");
                Element out = w(op, "output");
                List<Map.Entry<String, QName>> faults = new ArrayList<>();
                for (Element f : ws(op, "fault")) {
                    QName q = resolve(f, attr(f, "message"));
                    if (q != null) {
                        faults.add(Map.entry(attr(f, "name", "fault"), q));
                    }
                }
                ops.add(new Operation(attr(op, "name", "operation"), documentation(op),
                        in == null ? null : resolve(in, attr(in, "message")),
                        out == null ? null : resolve(out, attr(out, "message")), faults));
            }
            portTypes.put(new QName(tns, attr(pt, "name", "")), ops);
        }
        for (Element b : ws(root, "binding")) {
            QName ptQn = resolve(b, attr(b, "type"));
            if (ptQn == null) {
                continue;
            }
            String version = null;
            String style = "document";
            for (String[] pair : new String[][] {{SOAP11_NS, "1.1"}, {SOAP12_NS, "1.2"}}) {
                Element sb = child(b, pair[0], "binding");
                if (sb != null) {
                    version = pair[1];
                    style = attr(sb, "style", "document");
                }
            }
            Map<String, BindingOp> bops = new LinkedHashMap<>();
            for (Element bop : ws(b, "operation")) {
                String action = null;
                String opStyle = null;
                String use = null;
                for (Element c : descendants(bop, null, null)) {
                    String ns = nsOf(c);
                    if (!ns.equals(SOAP11_NS) && !ns.equals(SOAP12_NS)) {
                        continue;
                    }
                    if (c.getLocalName().equals("operation")) {
                        String a = attr(c, "soapAction");
                        action = a == null || a.isEmpty() ? null : a;
                        opStyle = attr(c, "style");
                    } else if (c.getLocalName().equals("body") && use == null) {
                        use = attr(c, "use");
                    }
                }
                bops.put(attr(bop, "name", ""), new BindingOp(action, opStyle, use));
            }
            bindings.put(new QName(tns, attr(b, "name", "")),
                    new Binding(attr(b, "name", ""), ptQn, version, style, bops));
        }
        for (Element svc : ws(root, "service")) {
            for (Element port : ws(svc, "port")) {
                String address = null;
                for (String ns : List.of(SOAP11_NS, SOAP12_NS)) {
                    Element addr = child(port, ns, "address");
                    if (addr != null) {
                        address = attr(addr, "location");
                    }
                }
                QName bQn = resolve(port, attr(port, "binding"));
                if (bQn != null) {
                    ports.add(new Port(attr(svc, "name", "Service"), documentation(svc),
                            attr(port, "name", ""), bQn, address));
                }
            }
        }
    }

    // -------------------------------------------------------------- selection

    private static int rank(String version) {
        return "1.1".equals(version) ? 0 : 1;
    }

    /** One (port, binding, portType) per portType, preferring SOAP 1.1 bindings. */
    private List<Selected> selected() {
        Map<QName, Selected> chosen = new LinkedHashMap<>();
        for (Port port : ports) {
            Binding binding = bindings.get(port.binding());
            if (binding == null) {
                warnings.add("Port " + port.name() + ": binding not found");
                continue;
            }
            if (binding.soapVersion() == null) {
                warnings.add("Port " + port.name() + ": non-SOAP binding skipped");
                continue;
            }
            Selected current = chosen.get(binding.portType());
            if (current == null || (current.binding() != null
                    && rank(binding.soapVersion()) < rank(current.binding().soapVersion()))) {
                chosen.put(binding.portType(), new Selected(port, binding, binding.portType()));
            }
        }
        for (Binding binding : bindings.values()) {
            if (binding.soapVersion() != null && !chosen.containsKey(binding.portType())) {
                chosen.put(binding.portType(), new Selected(null, binding, binding.portType()));
            }
        }
        for (QName pt : portTypes.keySet()) {
            chosen.putIfAbsent(pt, new Selected(null, null, pt));
        }
        return new ArrayList<>(chosen.values());
    }

    // ------------------------------------------------------------- operations

    private Map<String, Object> messageSchema(QName msgQn, String style, String wrapper) {
        if (msgQn == null) {
            return null;
        }
        List<Part> parts = messages.get(msgQn);
        if (parts == null) {
            warnings.add("Message " + msgQn.local() + " not found");
            return null;
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (style.equals("document") && parts.size() == 1 && parts.get(0).element() != null) {
            return xsd.elementRef(parts.get(0).element());
        }
        Map<String, Object> props = obj();
        for (Part part : parts) {
            props.put(part.name(), part.element() != null ? xsd.elementRef(part.element())
                    : xsd.typeRef(part.type()));
        }
        Map<String, Object> schema = obj("type", "object", "required",
                new ArrayList<>(props.keySet()), "properties", props);
        return XsdConverter.ref(xsd.newComponent(wrapper, schema));
    }

    public ConversionResult convert(String text) {
        Element root = SafeXml.parse(text);
        index(root);

        Map<String, Object> paths = obj();
        Map<String, Map<String, Object>> tags = new LinkedHashMap<>();
        Set<String> opIds = new HashSet<>();
        List<String> addresses = new ArrayList<>();
        boolean usesFault = false;
        boolean warnedEncoded = false;

        List<Selected> selected = selected();
        if (selected.isEmpty()) {
            throw new InputException("WSDL defines no portType operations to convert");
        }
        for (Selected sel : selected) {
            Port port = sel.port();
            Binding binding = sel.binding();
            String tag = port != null && selected.size() == 1 ? port.service()
                    : sel.portType().local();
            Map<String, Object> tagObj = obj("name", tag);
            if (port != null && port.serviceDoc() != null) {
                tagObj.put("description", port.serviceDoc());
            }
            tags.putIfAbsent(tag, tagObj);
            if (port != null && port.address() != null && !addresses.contains(port.address())) {
                addresses.add(port.address());
            }

            for (Operation op : portTypes.getOrDefault(sel.portType(), List.of())) {
                BindingOp bop = binding == null ? null : binding.ops().get(op.name());
                String style = bop != null && bop.style() != null && !bop.style().isEmpty()
                        ? bop.style() : (binding != null ? binding.style() : "document");
                if (bop != null && "encoded".equals(bop.use()) && !warnedEncoded) {
                    warnedEncoded = true;
                    warnings.add("rpc/encoded binding detected (not WS-I compliant); "
                            + "mapped as literal");
                }
                String path = "/" + Naming.toKebab(op.name());
                if (paths.containsKey(path)) {
                    path = "/" + Naming.toKebab(sel.portType().local()) + "/"
                            + Naming.toKebab(op.name());
                }
                String basePath = path;
                for (int n = 2; paths.containsKey(path); n++) {
                    path = basePath + "-" + n;
                }
                String opId = Naming.unique(Naming.toCamel(op.name()), opIds);
                opIds.add(opId);

                Map<String, Object> operation = obj(
                        "tags", new ArrayList<>(List.of(tag)),
                        "summary", op.name(),
                        "operationId", opId);
                if (op.doc() != null) {
                    operation.put("description", op.doc());
                }
                Map<String, Object> req = messageSchema(op.input(), style, op.name() + "Request");
                if (req != null) {
                    operation.put("requestBody", obj("required", true, "content",
                            obj("application/json", obj("schema", req))));
                }
                Map<String, Object> responses = obj();
                Map<String, Object> resp = messageSchema(op.output(), style,
                        op.name() + "Response");
                if (op.output() == null) {
                    responses.put("202", obj("description", "Accepted (one-way operation)"));
                } else if (resp != null) {
                    responses.put("200", obj("description", "Successful response", "content",
                            obj("application/json", obj("schema", resp))));
                } else {
                    responses.put("204", obj("description", "Successful response (empty)"));
                }

                usesFault = true;
                Map<String, Object> faultSchema = obj("$ref", "#/components/schemas/SoapFault");
                List<Object> details = new ArrayList<>();
                for (Map.Entry<String, QName> fault : op.faults()) {
                    Map<String, Object> s = messageSchema(fault.getValue(), "document",
                            fault.getKey() + "Detail");
                    if (s != null) {
                        details.add(s);
                    }
                }
                if (!details.isEmpty()) {
                    Object detail = details.size() == 1 ? details.get(0) : obj("oneOf", details);
                    faultSchema = obj("allOf", new ArrayList<>(List.of(faultSchema,
                            obj("properties", obj("detail", detail)))));
                }
                responses.put("500", obj("description", "SOAP Fault", "content",
                        obj("application/json", obj("schema", faultSchema))));
                operation.put("responses", responses);

                Map<String, Object> soap = obj("operation", op.name(), "style", style);
                if (bop != null && bop.soapAction() != null) {
                    soap.put("soapAction", bop.soapAction());
                }
                if (binding != null) {
                    soap.put("binding", binding.name());
                    soap.put("soapVersion", binding.soapVersion());
                }
                if (port != null) {
                    soap.put("service", port.service());
                    soap.put("port", port.name());
                }
                operation.put("x-soap", soap);
                paths.put(path, obj("post", operation));
            }
        }

        Map<String, Object> schemas = new LinkedHashMap<>(xsd.components());
        if (usesFault) {
            schemas.put("SoapFault", SOAP_FAULT_SCHEMA);
        }
        String title = selected.stream().filter(s -> s.port() != null)
                .map(s -> s.port().service()).findFirst().orElse(null);
        if (title == null || title.isEmpty()) {
            String name = attr(root, "name");
            title = name == null || name.isEmpty() ? "SOAP" : name;
        }
        String description = documentation(root);
        String note = "Generated by spec-lint from a WSDL 1.1 contract (JSON facade over SOAP).";
        Map<String, Object> info = obj("title", title, "version", "1.0.0", "description",
                ((description == null ? "" : description) + "\n\n" + note).strip());
        warnings.add("WSDL has no API version; info.version set to 1.0.0");

        Map<String, Object> server = obj("url", "/", "description",
                "REST facade (deploy behind an API gateway that calls the SOAP backend)");
        if (!addresses.isEmpty()) {
            server.put("x-soap-addresses", addresses);
        }
        Map<String, Object> out = obj("openapi", "3.1.0", "info", info);
        out.put("servers", new ArrayList<>(List.of(server)));
        out.put("tags", new ArrayList<>(tags.values()));
        out.put("paths", paths);
        out.put("components", obj("schemas", schemas));
        return new ConversionResult(out, SpecKind.WSDL11, warnings);
    }
}
