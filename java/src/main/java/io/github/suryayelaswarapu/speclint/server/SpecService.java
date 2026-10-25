package io.github.suryayelaswarapu.speclint.server;

import static io.github.suryayelaswarapu.speclint.Json.obj;

import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.Modernizer;
import io.github.suryayelaswarapu.speclint.SpecKind;
import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.Version;
import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.Converters;
import io.github.suryayelaswarapu.speclint.fix.Fixer;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import io.github.suryayelaswarapu.speclint.lint.Rule;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import io.github.suryayelaswarapu.speclint.report.Reports;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Framework-free request handling (the controller only does HTTP). Mirrors the Python and Go
 * services: the server never reads its own file system (local XSD imports are disabled) and
 * never fetches remote references.
 */
@Service
public class SpecService {
    public static final String IMPLEMENTATION = "java";
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9._ -]");

    /** Keep only a short, plain base name; it is used in messages and the report only. */
    public static String safeFilename(Object name) {
        String base = name == null ? "" : String.valueOf(name).replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        base = UNSAFE_NAME.matcher(base).replaceAll("_");
        if (base.length() > 200) {
            base = base.substring(0, 200);
        }
        return base.isEmpty() ? "spec" : base;
    }

    private static SourceFile source(Map<String, Object> body) {
        if (!(body.get("spec") instanceof String spec) || spec.isBlank()) {
            throw new InputException("'spec' must be a non-empty string");
        }
        // A virtual path: nothing is ever read from it (local imports are disabled).
        return new SourceFile(Path.of(safeFilename(body.get("filename"))), spec);
    }

    private static LintConfig config(Map<String, Object> body) {
        Object raw = body.get("config");
        if (raw == null) {
            return new LintConfig();
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new InputException("'config' must be an object");
        }
        Map<String, Object> picked = obj();
        Json.map(raw).forEach((k, v) -> {
            if (k.equals("extends") || k.equals("rules")) {
                picked.put(k, v);
            }
        });
        return LintConfig.fromMap(picked);
    }

    private static Map<String, Object> requireOas3(SourceFile source) {
        if (SpecKind.looksLikeXml(source.text())) {
            throw new InputException("Input is WSDL; use /api/v1/modernize or /api/v1/convert");
        }
        Map<String, Object> doc = SpecIO.parseStructured(source.text(), source.name());
        if (SpecKind.detectDocument(doc) == SpecKind.SWAGGER2) {
            throw new InputException(
                    "Input is Swagger 2.0; use /api/v1/modernize or /api/v1/convert");
        }
        return doc;
    }

    private static List<Object> findings(List<Finding> findings) {
        List<Object> out = new ArrayList<>();
        findings.forEach(f -> out.add(f.toMap()));
        return out;
    }

    private static List<Object> fixes(List<Fixer.AppliedFix> fixes) {
        List<Object> out = new ArrayList<>();
        fixes.forEach(f -> out.add(obj("rule", f.rule(), "message", f.message(), "path", f.path())));
        return out;
    }

    public Map<String, Object> health() {
        return obj("status", "UP", "version", Version.VERSION, "implementation", IMPLEMENTATION);
    }

    public Map<String, Object> rules() {
        List<Rule> sorted = new ArrayList<>(Rules.ALL.values());
        sorted.sort(Comparator.comparing(Rule::id));
        List<Object> out = new ArrayList<>();
        sorted.forEach(r -> out.add(obj("id", r.id(), "severity", r.severity().value(),
                "fixable", r.fixable(), "description", r.description())));
        return obj("rules", out);
    }

    public Map<String, Object> convert(Map<String, Object> body) {
        ConversionResult result = Converters.convertSource(source(body), false);
        return obj("sourceKind", result.sourceKind().value(), "openapi", result.document(),
                "yaml", SpecIO.dump(result.document(), "yaml"), "warnings", result.warnings());
    }

    public Map<String, Object> lint(Map<String, Object> body) {
        SourceFile src = source(body);
        List<Finding> found = Linter.lint(requireOas3(src), config(body),
                SpecIO.lineIndex(src.text()));
        return obj("summary", Linter.summarize(found), "findings", findings(found));
    }

    public Map<String, Object> fix(Map<String, Object> body) {
        SourceFile src = source(body);
        Fixer.Result result = Fixer.apply(requireOas3(src), config(body));
        return obj("openapi", result.document(), "yaml", SpecIO.dump(result.document(), "yaml"),
                "fixes", fixes(result.applied()));
    }

    public Map<String, Object> modernize(Map<String, Object> body) {
        SourceFile src = source(body);
        Object fix = body.getOrDefault("fix", true);
        if (!(fix instanceof Boolean doFix)) {
            throw new InputException("'fix' must be a boolean");
        }
        Modernizer.Result r = Modernizer.modernize(src, config(body), doFix, "yaml", false);
        String stem = src.name().contains(".")
                ? src.name().substring(0, src.name().lastIndexOf('.')) : src.name();
        return obj(
                "sourceKind", r.sourceKind().value(),
                "openapi", r.document(),
                "yaml", r.text(),
                "warnings", r.warnings(),
                "fixes", fixes(r.fixes()),
                "summary", Linter.summarize(r.findings()),
                "findings", findings(r.findings()),
                "report", Reports.markdown(src.name(), r.sourceKind().label(),
                        stem + ".openapi.yaml", r.warnings(), r.fixes(), r.findings()));
    }
}
