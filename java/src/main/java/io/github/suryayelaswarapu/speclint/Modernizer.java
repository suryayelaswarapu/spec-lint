package io.github.suryayelaswarapu.speclint;

import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.Converters;
import io.github.suryayelaswarapu.speclint.fix.Fixer;
import io.github.suryayelaswarapu.speclint.fix.Fixer.AppliedFix;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import java.util.List;
import java.util.Map;

/** The {@code modernize} pipeline: detect, convert, auto-fix, serialise, lint. */
public final class Modernizer {
    private Modernizer() {}

    public record Result(Map<String, Object> document, String text, SpecKind sourceKind,
            List<String> warnings, List<AppliedFix> fixes, List<Finding> findings) {}

    public static Result modernize(SourceFile source, LintConfig config, boolean fix,
            String format, boolean allowLocalImports) {
        LintConfig cfg = config != null ? config : new LintConfig();
        ConversionResult converted = Converters.convertSource(source, allowLocalImports);
        Map<String, Object> doc = converted.document();
        List<AppliedFix> fixes = List.of();
        if (fix) {
            Fixer.Result fixed = Fixer.apply(doc, cfg);
            doc = fixed.document();
            fixes = fixed.applied();
        }
        String text = SpecIO.dump(doc, format);
        // Lint what was written, so reported line numbers match the output file.
        List<Finding> findings = Linter.lint(SpecIO.parseStructured(text), cfg,
                SpecIO.lineIndex(text));
        return new Result(doc, text, converted.sourceKind(), converted.warnings(), fixes,
                findings);
    }
}
