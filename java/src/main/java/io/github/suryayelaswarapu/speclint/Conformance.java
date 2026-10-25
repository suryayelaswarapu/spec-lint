package io.github.suryayelaswarapu.speclint;

import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.Converters;
import io.github.suryayelaswarapu.speclint.fix.Fixer;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds a cross-language conformance record (see {@code testdata/conformance}). The Python
 * implementation generates the golden files; Java and Go must reproduce them exactly.
 */
public final class Conformance {
    public static final String VALIDATOR_RULE = "oas3-valid-schema";

    private Conformance() {}

    public static Map<String, Object> buildCase(Path testdata, String rel) {
        ConversionResult converted = Converters.convertSource(
                SpecIO.readSource(testdata.resolve(rel)));
        Fixer.Result fixed = Fixer.apply(converted.document());
        List<String> fixes = new ArrayList<>();
        fixed.applied().forEach(f -> fixes.add(f.rule() + "|" + f.path()));
        Collections.sort(fixes);
        List<String> findings = new ArrayList<>();
        for (Finding f : Linter.lint(fixed.document())) {
            if (!f.rule().equals(VALIDATOR_RULE)) {
                findings.add(f.rule() + "|" + f.severity().value() + "|" + f.pointer());
            }
        }
        Collections.sort(findings);
        return Json.obj(
                "input", rel,
                "sourceKind", converted.sourceKind().value(),
                "warnings", converted.warnings().size(),
                "converted", converted.document(),
                "modernized", fixed.document(),
                "fixes", fixes,
                "findings", findings);
    }
}
