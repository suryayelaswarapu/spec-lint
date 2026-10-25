package io.github.suryayelaswarapu.speclint.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the registered rules over an OpenAPI 3.x document. */
public final class Linter {
    private Linter() {}

    public static List<Finding> lint(Map<String, Object> doc, LintConfig config,
            Map<List<String>, Integer> lines) {
        LintConfig cfg = config != null ? config : new LintConfig();
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : Rules.ALL.values()) {
            Severity severity = cfg.severity(rule.id());
            if (severity == Severity.OFF) {
                continue;
            }
            for (Rule.Hit hit : rule.check().apply(doc)) {
                Integer line = null;
                if (lines != null && !lines.isEmpty()) {
                    // Nearest ancestor that has a known line.
                    for (int depth = hit.path().size(); depth >= 0; depth--) {
                        Integer l = lines.get(hit.path().subList(0, depth));
                        if (l != null) {
                            line = l;
                            break;
                        }
                    }
                }
                findings.add(new Finding(rule.id(), severity, hit.message(),
                        List.copyOf(hit.path()), line));
            }
        }
        findings.sort(Comparator.comparingInt((Finding f) -> f.line() == null ? 0 : f.line())
                .thenComparing(Finding::rule)
                .thenComparing(Finding::pointer));
        return findings;
    }

    public static List<Finding> lint(Map<String, Object> doc) {
        return lint(doc, null, null);
    }

    public static Map<String, Integer> summarize(List<Finding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("error", 0);
        counts.put("warning", 0);
        counts.put("info", 0);
        for (Finding f : findings) {
            counts.merge(f.severity().value(), 1, Integer::sum);
        }
        return counts;
    }
}
