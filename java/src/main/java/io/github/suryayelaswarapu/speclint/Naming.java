package io.github.suryayelaswarapu.speclint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifier case helpers shared by converters, rules and fixes. */
public final class Naming {
    private static final Pattern WORD_SPLIT = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern CAMEL_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    public static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    public static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
    public static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private Naming() {}

    /** Split {@code GetForecast_byDay-v2} into {@code [Get, Forecast, by, Day, v2]}. */
    public static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        for (String chunk : WORD_SPLIT.split(name)) {
            if (chunk.isEmpty()) {
                continue;
            }
            for (String part : CAMEL_BOUNDARY.split(chunk)) {
                if (!part.isEmpty()) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    private static String cap(String part) {
        return part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1);
    }

    public static String toCamel(String name) {
        List<String> parts = words(name);
        if (parts.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(parts.get(0).toLowerCase(Locale.ROOT));
        for (String p : parts.subList(1, parts.size())) {
            sb.append(cap(p.toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    /** {@code forecast_day -> ForecastDay}; existing capitals are kept ({@code NDFDgen}). */
    public static String toPascal(String name) {
        StringBuilder sb = new StringBuilder();
        for (String p : words(name)) {
            sb.append(cap(p));
        }
        return sb.isEmpty() ? name : sb.toString();
    }

    public static String toKebab(String name) {
        List<String> parts = words(name);
        if (parts.isEmpty()) {
            return name;
        }
        return String.join("-", parts.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList());
    }

    /** {@code name}, or {@code name2}, {@code name3}... whichever is not taken. */
    public static String unique(String name, Collection<String> taken) {
        if (!taken.contains(name)) {
            return name;
        }
        int i = 2;
        while (taken.contains(name + i)) {
            i++;
        }
        return name + i;
    }
}
