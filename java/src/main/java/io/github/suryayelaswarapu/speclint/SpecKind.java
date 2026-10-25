package io.github.suryayelaswarapu.speclint;

import io.github.suryayelaswarapu.speclint.SpecLintException.UnsupportedSpecException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The kinds of API description spec-lint understands, plus detection logic. */
public enum SpecKind {
    SWAGGER2("swagger-2.0", "Swagger 2.0"),
    OPENAPI30("openapi-3.0", "OpenAPI 3.0"),
    OPENAPI31("openapi-3.1", "OpenAPI 3.1"),
    WSDL11("wsdl-1.1", "WSDL 1.1");

    public static final String WSDL20_NS = "http://www.w3.org/ns/wsdl";
    private static final Pattern ROOT_TAG =
            Pattern.compile("<(?:[\\w.-]+:)?(definitions|description)\\b[^>]*>", Pattern.DOTALL);

    private final String value;
    private final String label;

    SpecKind(String value, String label) {
        this.value = value;
        this.label = label;
    }

    public String value() {
        return value;
    }

    public String label() {
        return label;
    }

    public static boolean looksLikeXml(String text) {
        return text.stripLeading().startsWith("<");
    }

    /** Cheap, parser-free check of the XML root element. */
    public static SpecKind detectXml(String text) {
        Matcher m = ROOT_TAG.matcher(text);
        if (!m.find()) {
            throw new UnsupportedSpecException(
                    "XML input is not a WSDL document (no <definitions> root)");
        }
        if (m.group(1).equals("description") || m.group(0).contains(WSDL20_NS)) {
            throw new UnsupportedSpecException("WSDL 2.0 is not supported yet; only WSDL 1.1");
        }
        return WSDL11;
    }

    /** Classify a parsed JSON/YAML document. */
    public static SpecKind detectDocument(Map<String, Object> doc) {
        if (Json.str(doc.get("swagger")).startsWith("2")) {
            return SWAGGER2;
        }
        String version = Json.str(doc.get("openapi"));
        if (version.startsWith("3.0")) {
            return OPENAPI30;
        }
        if (version.startsWith("3.1")) {
            return OPENAPI31;
        }
        if (doc.containsKey("swagger") || doc.containsKey("openapi")) {
            throw new UnsupportedSpecException("Unsupported spec version: swagger="
                    + doc.get("swagger") + " openapi=" + version);
        }
        throw new UnsupportedSpecException(
                "Not an OpenAPI/Swagger document (no 'openapi' or 'swagger' key)");
    }
}
