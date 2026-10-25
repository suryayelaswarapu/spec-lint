package io.github.suryayelaswarapu.speclint.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.suryayelaswarapu.speclint.Json;
import io.github.suryayelaswarapu.speclint.SpecLintException.InputException;
import io.github.suryayelaswarapu.speclint.SpecLintException.SecurityException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Safe reading, parsing and serialising of spec documents.
 *
 * <ul>
 *   <li>Files larger than {@code maxBytes} are rejected before they are read.
 *   <li>YAML is parsed with SnakeYAML's {@link SafeConstructor} (no arbitrary object tags), a cap
 *       on alias usage (defeats "billion laughs" expansion) and no timestamp resolution, so {@code
 *       version: 2024-01-01} stays a string.
 * </ul>
 */
public final class SpecIO {
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_YAML_ALIASES = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private SpecIO() {}

    /** A YAML resolver identical to SnakeYAML's default minus timestamp detection. */
    static final class NoTimestampResolver extends Resolver {
        @Override
        public void addImplicitResolver(Tag tag, Pattern regexp, String first, int limit) {
            if (!Tag.TIMESTAMP.equals(tag)) {
                super.addImplicitResolver(tag, regexp, first, limit);
            }
        }
    }

    /** Representer that writes multi-line strings as literal blocks. */
    static final class LiteralRepresenter extends Representer {
        LiteralRepresenter(DumperOptions options) {
            super(options);
        }

        @Override
        protected Node representScalar(Tag tag, String value, DumperOptions.ScalarStyle style) {
            if (Tag.STR.equals(tag) && value.contains("\n")) {
                return super.representScalar(tag, value, DumperOptions.ScalarStyle.LITERAL);
            }
            return super.representScalar(tag, value, style);
        }
    }

    private static LoaderOptions loaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(100);
        options.setCodePointLimit(Math.toIntExact(DEFAULT_MAX_BYTES));
        return options;
    }

    private static DumperOptions dumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(100);
        options.setSplitLines(true);
        return options;
    }

    private static Yaml yaml() {
        DumperOptions dump = dumperOptions();
        LoaderOptions load = loaderOptions();
        return new Yaml(
                new SafeConstructor(load), new LiteralRepresenter(dump), dump, load,
                new NoTimestampResolver());
    }

    /** Read a UTF-8 text file, enforcing a size limit. */
    public static SourceFile readSource(Path path, long maxBytes) {
        if (!Files.isRegularFile(path)) {
            throw new InputException("File not found: " + path);
        }
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                throw new SecurityException(
                        path.getFileName() + " is " + size + " bytes; limit is " + maxBytes
                                + " (use --max-size)");
            }
            byte[] bytes = Files.readAllBytes(path);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return new SourceFile(path, text);
        } catch (CharacterCodingException e) {
            throw new InputException(path.getFileName() + " is not valid UTF-8: " + e, e);
        } catch (IOException e) {
            throw new InputException("Cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    public static SourceFile readSource(Path path) {
        return readSource(path, DEFAULT_MAX_BYTES);
    }

    /** Parse JSON or YAML text into a mapping with string keys. */
    public static Map<String, Object> parseStructured(String text, String name) {
        Object data;
        try {
            if (text.stripLeading().startsWith("{")) {
                data = JSON.readValue(text, Object.class);
            } else {
                data = yaml().load(text);
            }
        } catch (JsonProcessingException e) {
            throw new InputException(name + ": could not parse as JSON/YAML: "
                    + e.getOriginalMessage(), e);
        } catch (YAMLException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("aliases")) {
                throw new SecurityException("YAML document uses more than " + MAX_YAML_ALIASES
                        + " aliases; refusing to expand", e);
            }
            throw new InputException(name + ": could not parse as JSON/YAML: " + message, e);
        }
        if (!(data instanceof Map<?, ?>)) {
            throw new InputException(name + ": top level must be a mapping/object");
        }
        return Json.copyMap(Json.map(data)); // also normalises keys to strings
    }

    public static Map<String, Object> parseStructured(String text) {
        return parseStructured(text, "<input>");
    }

    /** Serialise as {@code yaml} or {@code json}, preserving key order. */
    public static String dump(Map<String, Object> data, String format) {
        // A deep copy guarantees no shared references, so YAML never emits anchors/aliases.
        Object copy = Json.deepCopy(data);
        if ("json".equals(format)) {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                    .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            try {
                return JSON.writer(printer).writeValueAsString(copy).replace("\r\n", "\n") + "\n";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        if ("yaml".equals(format)) {
            return yaml().dump(copy);
        }
        throw new IllegalArgumentException("Unknown output format: " + format);
    }

    /** {@code json} for a {@code .json} path, otherwise {@code yaml}. */
    public static String formatForPath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? "json" : "yaml";
    }

    /**
     * Map JSON-pointer-like paths to 1-based line numbers. Best effort: returns an empty map when
     * the text cannot be composed.
     */
    public static Map<List<String>, Integer> lineIndex(String text) {
        Map<List<String>, Integer> index = new HashMap<>();
        Node root;
        try {
            root = yaml().compose(new StringReader(text));
        } catch (YAMLException e) {
            return index;
        }
        if (root == null) {
            return index;
        }
        record Item(Node node, List<String> path) {}
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(root, List.of()));
        while (!stack.isEmpty()) {
            Item item = stack.pop();
            index.putIfAbsent(item.path(), item.node().getStartMark().getLine() + 1);
            if (item.node() instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    String key = tuple.getKeyNode() instanceof ScalarNode s ? s.getValue()
                            : String.valueOf(tuple.getKeyNode());
                    List<String> child = append(item.path(), key);
                    index.put(child, tuple.getKeyNode().getStartMark().getLine() + 1);
                    stack.push(new Item(tuple.getValueNode(), child));
                }
            } else if (item.node() instanceof SequenceNode seq) {
                List<Node> values = seq.getValue();
                for (int i = 0; i < values.size(); i++) {
                    stack.push(new Item(values.get(i), append(item.path(), String.valueOf(i))));
                }
            }
        }
        return index;
    }

    private static List<String> append(List<String> path, String key) {
        String[] out = path.toArray(new String[path.size() + 1]);
        out[path.size()] = key;
        return List.of(out);
    }
}
