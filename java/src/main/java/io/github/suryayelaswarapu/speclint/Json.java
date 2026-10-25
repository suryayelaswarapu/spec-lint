package io.github.suryayelaswarapu.speclint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for the untyped JSON data model used throughout spec-lint: {@code Map<String,Object>}
 * (insertion ordered), {@code List<Object>}, strings, numbers, booleans and {@code null}.
 *
 * <p>The accessors are deliberately forgiving: specs in the wild are often malformed, and a
 * linter must report problems rather than crash on them.
 */
public final class Json {
    private Json() {}

    /** A new, insertion-ordered map. */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    /** Build an ordered map from alternating key/value arguments. */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    /** {@code value} as a map, or an immutable empty map when it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    /** {@code value} as a list, or an immutable empty list when it is not one. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : Collections.emptyList();
    }

    public static boolean isMap(Object value) {
        return value instanceof Map<?, ?>;
    }

    /** Python-style truthiness: null, false, 0, "" and empty collections are false. */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    /** {@code String.valueOf}, but {@code ""} for null. */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Deep copy maps and lists; scalars are immutable and shared. */
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), deepCopy(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(deepCopy(v)));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyMap(Map<String, Object> value) {
        return (Map<String, Object>) deepCopy(value);
    }

    /** The child map at {@code key}, created (and stored) when missing or not a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object current = parent.get(key);
        if (current instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> created = obj();
        parent.put(key, created);
        return created;
    }
}
