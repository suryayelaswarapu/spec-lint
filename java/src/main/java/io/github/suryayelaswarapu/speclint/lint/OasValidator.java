package io.github.suryayelaswarapu.speclint.lint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.lint.Rule.Hit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Structural validation ({@code oas3-valid-schema}) against the official OpenAPI JSON Schemas,
 * vendored in {@code /schemas/openapi} and bundled as resources, so nothing is fetched.
 */
public final class OasValidator {
    static final int MAX_ERRORS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/io/github/suryayelaswarapu/speclint/schemas/";
    private static volatile JsonSchema v30;
    private static volatile JsonSchema v31;

    private OasValidator() {}

    private static JsonSchema load(String file, SpecVersion.VersionFlag flag) {
        try (InputStream in = OasValidator.class.getResourceAsStream(BASE + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled schema " + file);
            }
            JsonNode node = MAPPER.readTree(in);
            return JsonSchemaFactory.getInstance(flag).getSchema(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonSchema schemaFor(String version) {
        if (version.startsWith("3.0")) {
            if (v30 == null) {
                v30 = load("oas-3.0.json", SpecVersion.VersionFlag.V4);
            }
            return v30;
        }
        if (v31 == null) {
            v31 = load("oas-3.1.json", SpecVersion.VersionFlag.V202012);
        }
        return v31;
    }

    /** The {@code oas3-valid-schema} rule. */
    public static List<Hit> check(Map<String, Object> doc) {
        // Never validate a document with unvetted references.
        for (Walk.Ref r : Walk.refs(doc)) {
            if (!r.ref().startsWith("#") || Walk.resolvePointer(doc, r.ref()) == null) {
                return List.of();
            }
        }
        List<Hit> out = new ArrayList<>();
        try {
            JsonSchema schema = schemaFor(Json.str(doc.get("openapi")));
            List<ValidationMessage> errors = new ArrayList<>(
                    schema.validate(MAPPER.valueToTree(doc)));
            errors.sort(Comparator.comparing(ValidationMessage::getMessage));
            for (ValidationMessage error : errors) {
                if (out.size() >= MAX_ERRORS) {
                    break;
                }
                out.add(new Hit("Invalid OpenAPI: " + error.getMessage(),
                        toPath(error.getInstanceLocation())));
            }
        } catch (RuntimeException e) {
            out.add(new Hit("Invalid OpenAPI: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage(), List.of()));
        }
        return out;
    }

    private static List<String> toPath(JsonNodePath location) {
        List<String> path = new ArrayList<>();
        if (location != null) {
            for (int i = 0; i < location.getNameCount(); i++) {
                path.add(String.valueOf(location.getElement(i)));
            }
        }
        return path;
    }
}
