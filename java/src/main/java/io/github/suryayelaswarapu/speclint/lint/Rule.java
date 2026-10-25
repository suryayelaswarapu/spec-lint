package io.github.suryayelaswarapu.speclint.lint;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A lint rule: identity, default severity and a check that returns hits. */
public record Rule(String id, Severity severity, String description, boolean fixable,
        Function<Map<String, Object>, List<Hit>> check) {

    /** A raw rule result before severity and line numbers are attached. */
    public record Hit(String message, List<String> path) {}
}
