package io.github.suryayelaswarapu.speclint.lint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One lint result. {@code line} is null when unknown. */
public record Finding(String rule, Severity severity, String message, List<String> path,
        Integer line) {

    /** Encode a path as an RFC 6901 JSON Pointer ({@code [paths, /a]} becomes {@code /paths/~1a}). */
    public static String pointer(List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (String p : path) {
            sb.append('/').append(p.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    public String pointer() {
        return pointer(path);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", rule);
        out.put("severity", severity.value());
        out.put("message", message);
        out.put("path", pointer());
        if (line != null) {
            out.put("line", line);
        }
        return out;
    }
}
