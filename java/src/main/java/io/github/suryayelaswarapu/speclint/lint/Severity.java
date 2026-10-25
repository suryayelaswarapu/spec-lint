package io.github.suryayelaswarapu.speclint.lint;

import java.util.Locale;

/** Finding severity. {@code OFF} disables a rule. */
public enum Severity {
    ERROR(3), WARNING(2), INFO(1), OFF(0);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse {@code error|warning|info|off}; throws {@link IllegalArgumentException} otherwise. */
    public static Severity parse(String value) {
        for (Severity s : values()) {
            if (s.value().equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("invalid severity: " + value);
    }
}
