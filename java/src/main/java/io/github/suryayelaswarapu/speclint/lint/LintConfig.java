package io.github.suryayelaswarapu.speclint.lint;

import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.SpecLintException;
import io.github.suryayelaswarapu.speclint.SpecLintException.ConfigException;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lint configuration from {@code .speclint.yaml}:
 *
 * <pre>
 * extends: recommended     # or: strict
 * fail-on: error           # error | warning | info | never
 * rules:
 *   property-camel-case: off
 * </pre>
 */
public final class LintConfig {
    public static final List<String> CONFIG_FILENAMES = List.of(".speclint.yaml", ".speclint.yml");
    public static final List<String> FAIL_ON = List.of("error", "warning", "info", "never");

    private final String extendsName;
    private String failOn;
    private final Map<String, Severity> overrides;

    public LintConfig(String extendsName, String failOn, Map<String, Severity> overrides) {
        this.extendsName = extendsName;
        this.failOn = failOn;
        this.overrides = new HashMap<>(overrides);
    }

    public LintConfig() {
        this("recommended", "error", Map.of());
    }

    public String failOn() {
        return failOn;
    }

    public void failOn(String value) {
        this.failOn = value;
    }

    public Severity severity(String ruleId) {
        if (overrides.containsKey(ruleId)) {
            return overrides.get(ruleId);
        }
        Severity base = Rules.ALL.get(ruleId).severity();
        if (extendsName.equals("strict")) {
            return switch (base) {
                case WARNING -> Severity.ERROR;
                case INFO -> Severity.WARNING;
                default -> base;
            };
        }
        return base;
    }

    public boolean enabled(String ruleId) {
        return severity(ruleId) != Severity.OFF;
    }

    public boolean fails(Severity severity) {
        if (failOn.equals("never")) {
            return false;
        }
        return severity.rank() >= Severity.parse(failOn).rank();
    }

    /** Load from {@code path}, else {@code .speclint.yaml} in {@code searchDir}, else defaults. */
    public static LintConfig load(Path path, Path searchDir) {
        if (path == null) {
            Path dir = searchDir != null ? searchDir : Path.of("").toAbsolutePath();
            for (String name : CONFIG_FILENAMES) {
                if (Files.isRegularFile(dir.resolve(name))) {
                    path = dir.resolve(name);
                    break;
                }
            }
        }
        if (path == null) {
            return new LintConfig();
        }
        Map<String, Object> data;
        try {
            data = SpecIO.parseStructured(SpecIO.readSource(path, 256 * 1024).text(),
                    path.toString());
        } catch (SpecLintException e) {
            throw new ConfigException("Cannot read config " + path + ": " + e.getMessage(), e);
        }
        return fromMap(data);
    }

    /** Build a config from a parsed .speclint.yaml (or an API request's {@code config}). */
    public static LintConfig fromMap(Map<String, Object> data) {
        String ext = data.containsKey("extends") ? Json.str(data.get("extends")) : "recommended";
        if (!ext.equals("recommended") && !ext.equals("strict")) {
            throw new ConfigException(
                    "extends must be 'recommended' or 'strict', not '" + ext + "'");
        }
        String failOn = data.containsKey("fail-on") ? Json.str(data.get("fail-on")) : "error";
        if (!FAIL_ON.contains(failOn)) {
            throw new ConfigException("fail-on must be one of " + String.join(", ", FAIL_ON));
        }
        Object rawRules = data.get("rules");
        if (rawRules != null && !(rawRules instanceof Map<?, ?>)) {
            throw new ConfigException("rules must be a mapping of rule-id: severity");
        }
        Map<String, Severity> overrides = new HashMap<>();
        for (Map.Entry<String, Object> e : Json.map(rawRules).entrySet()) {
            if (!Rules.ALL.containsKey(e.getKey())) {
                throw new ConfigException("Unknown rule '" + e.getKey()
                        + "' (run 'speclint rules' to list them)");
            }
            // YAML 1.1 reads a bare `off` as false
            String level = Boolean.FALSE.equals(e.getValue()) ? "off" : Json.str(e.getValue());
            try {
                overrides.put(e.getKey(), Severity.parse(level));
            } catch (IllegalArgumentException ex) {
                throw new ConfigException("Rule '" + e.getKey() + "': invalid severity '"
                        + e.getValue() + "'", ex);
            }
        }
        return new LintConfig(ext, failOn, overrides);
    }
}
