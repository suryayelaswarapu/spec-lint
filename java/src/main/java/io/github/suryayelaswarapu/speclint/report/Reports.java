package io.github.suryayelaswarapu.speclint.report;

import static io.github.suryayelaswarapu.speclint.Json.obj;

import io.github.suryayelaswarapu.speclint.Version;
import io.github.suryayelaswarapu.speclint.fix.Fixer.AppliedFix;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Render lint results as text, JSON, SARIF 2.1.0 or a Markdown migration report. */
public final class Reports {
    public static final String REPO_URL = "https://github.com/suryayelaswarapu/spec-lint";
    private static final Map<String, String> SARIF_LEVEL =
            Map.of("error", "error", "warning", "warning", "info", "note");

    private Reports() {}

    public static String text(List<Finding> findings, String filename) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            String loc = f.line() != null ? filename + ":" + f.line() : filename;
            sb.append(String.format("%s  %-7s  %-26s  %s%n", loc, f.severity().value(), f.rule(),
                    f.message()));
        }
        Map<String, Integer> c = Linter.summarize(findings);
        int total = c.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 0) {
            sb.append(String.format("%n%d problem%s (%d errors, %d warnings, %d info)%n", total,
                    total == 1 ? "" : "s", c.get("error"), c.get("warning"), c.get("info")));
        } else {
            sb.append(filename).append(": no problems found").append(System.lineSeparator());
        }
        return sb.toString().replace("\r\n", "\n");
    }

    public static String json(List<Finding> findings, String filename) {
        List<Object> items = new ArrayList<>();
        findings.forEach(f -> items.add(f.toMap()));
        return SpecIO.dump(obj("tool", "speclint", "version", Version.VERSION, "file", filename,
                "summary", Linter.summarize(findings), "findings", items), "json");
    }

    /** SARIF 2.1.0, accepted by GitHub code scanning. */
    public static String sarif(List<Finding> findings, String filename) {
        List<String> used = new ArrayList<>(new TreeSet<>(findings.stream()
                .map(Finding::rule).toList()));
        Map<String, Integer> index = new HashMap<>();
        List<Object> rules = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            String id = used.get(i);
            index.put(id, i);
            rules.add(obj("id", id,
                    "shortDescription", obj("text", Rules.ALL.get(id).description()),
                    "helpUri", REPO_URL + "/blob/main/docs/rules.md#" + id,
                    "defaultConfiguration",
                    obj("level", SARIF_LEVEL.get(Rules.ALL.get(id).severity().value()))));
        }
        List<Object> results = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> physical = obj("artifactLocation", obj("uri", filename));
            if (f.line() != null) {
                physical.put("region", obj("startLine", f.line()));
            }
            results.add(obj(
                    "ruleId", f.rule(),
                    "ruleIndex", index.get(f.rule()),
                    "level", SARIF_LEVEL.get(f.severity().value()),
                    "message", obj("text", f.message()),
                    "locations", List.of(obj(
                            "physicalLocation", physical,
                            "logicalLocations", List.of(obj("fullyQualifiedName",
                                    f.pointer().isEmpty() ? "/" : f.pointer()))))));
        }
        return SpecIO.dump(obj(
                "$schema", "https://json.schemastore.org/sarif-2.1.0.json",
                "version", "2.1.0",
                "runs", List.of(obj(
                        "tool", obj("driver", obj("name", "speclint", "version", Version.VERSION,
                                "informationUri", REPO_URL, "rules", rules)),
                        "results", results))), "json");
    }

    private static String md(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    public static String markdown(String source, String sourceKind, String output,
            List<String> warnings, List<AppliedFix> fixes, List<Finding> findings) {
        Map<String, Integer> c = Linter.summarize(findings);
        List<String> out = new ArrayList<>(List.of(
                "# spec-lint migration report",
                "",
                "| Source | Detected format | Output |\n|---|---|---|\n| `" + source + "` | "
                        + sourceKind + " | `" + output + "` (OpenAPI 3.1) |",
                "",
                "## Summary",
                "",
                "- Conversion notes: **" + warnings.size() + "**",
                "- Auto-fixes applied: **" + fixes.size() + "**",
                "- Remaining findings: **" + c.get("error") + "** errors, **" + c.get("warning")
                        + "** warnings, **" + c.get("info") + "** info",
                "",
                "## Conversion notes",
                ""));
        if (warnings.isEmpty()) {
            out.add("_None._");
        }
        warnings.forEach(w -> out.add("- " + md(w)));
        out.addAll(List.of("", "## Auto-fixes applied", ""));
        if (fixes.isEmpty()) {
            out.add("_None._");
        } else {
            out.add("| Rule | Change |");
            out.add("|---|---|");
            fixes.forEach(f -> out.add("| `" + f.rule() + "` | " + md(f.message()) + " |"));
        }
        out.addAll(List.of("", "## Remaining findings", ""));
        if (findings.isEmpty()) {
            out.add("_No findings: the contract meets the standard._ \u2705");
        } else {
            out.add("| Severity | Rule | Line | Message |");
            out.add("|---|---|---|---|");
            findings.forEach(f -> out.add("| " + f.severity().value() + " | `" + f.rule()
                    + "` | " + (f.line() == null ? "" : f.line()) + " | " + md(f.message())
                    + " |"));
        }
        out.addAll(List.of("", "<sub>Generated by spec-lint " + Version.VERSION + "</sub>", ""));
        return String.join("\n", out);
    }
}
