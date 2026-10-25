package io.github.suryayelaswarapu.speclint;

import io.github.suryayelaswarapu.speclint.convert.ConversionResult;
import io.github.suryayelaswarapu.speclint.convert.Converters;
import io.github.suryayelaswarapu.speclint.fix.Fixer;
import io.github.suryayelaswarapu.speclint.io.SourceFile;
import io.github.suryayelaswarapu.speclint.io.SpecIO;
import io.github.suryayelaswarapu.speclint.lint.Finding;
import io.github.suryayelaswarapu.speclint.lint.LintConfig;
import io.github.suryayelaswarapu.speclint.lint.Linter;
import io.github.suryayelaswarapu.speclint.lint.Rule;
import io.github.suryayelaswarapu.speclint.lint.Rules;
import io.github.suryayelaswarapu.speclint.report.Reports;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Command-line interface. Exit codes: 0 success, 1 findings at or above {@code --fail-on},
 * 2 usage or input error.
 */
@Command(name = "speclint", mixinStandardHelpOptions = true, version = "speclint " + Version.VERSION,
        description = "Modernize legacy API contracts (Swagger 2.0, WSDL 1.1) into linted "
                + "OpenAPI 3.1.",
        subcommands = {Main.Convert.class, Main.Lint.class, Main.Fix.class, Main.Modernize.class,
                Main.ListRules.class, Main.Serve.class})
public final class Main implements Callable<Integer> {
    static final int EXIT_OK = 0;
    static final int EXIT_FINDINGS = 1;
    static final int EXIT_ERROR = 2;

    @Spec
    CommandSpec spec;

    /** Starts the web service; tests pass a stub through {@link #commandLine(Main)}. */
    java.util.function.Consumer<String[]> launcher = SpecLintApplication::main;

    @Option(names = "--max-size", defaultValue = "" + SpecIO.DEFAULT_MAX_BYTES,
            description = "Maximum input file size in bytes (default: ${DEFAULT-VALUE}).")
    long maxSize;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return EXIT_ERROR;
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    PrintWriter err() {
        return spec.commandLine().getErr();
    }

    SourceFile read(Path path) {
        return SpecIO.readSource(path, maxSize);
    }

    static void write(Main root, String output, String text) {
        if (output == null) {
            root.out().print(text);
            root.out().flush();
            return;
        }
        try {
            Files.writeString(Path.of(output), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SpecLintException("Cannot write " + output + ": " + e.getMessage(), e);
        }
    }

    static LintConfig config(String path, String failOn) {
        LintConfig config = LintConfig.load(path == null ? null : Path.of(path), null);
        if (failOn != null) {
            config.failOn(failOn);
        }
        return config;
    }

    static int exitFor(List<Finding> findings, LintConfig config) {
        return findings.stream().anyMatch(f -> config.fails(f.severity())) ? EXIT_FINDINGS
                : EXIT_OK;
    }

    static Map<String, Object> requireOas3(SourceFile source) {
        if (SpecKind.looksLikeXml(source.text())) {
            throw new SpecLintException(source.name()
                    + " is WSDL; run 'speclint modernize' to convert it first");
        }
        Map<String, Object> doc = SpecIO.parseStructured(source.text(), source.name());
        if (SpecKind.detectDocument(doc) == SpecKind.SWAGGER2) {
            throw new SpecLintException(source.name()
                    + " is Swagger 2.0; run 'speclint modernize' (or 'convert') first");
        }
        return doc;
    }

    @Command(name = "convert", mixinStandardHelpOptions = true,
            description = "Convert Swagger 2.0 / OpenAPI 3.0 / WSDL 1.1 to OpenAPI 3.1.")
    static final class Convert implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Parameters(paramLabel = "INPUT")
        Path input;

        @Option(names = {"-o", "--output"}, description = "Write result here (default: stdout).")
        String output;

        @Option(names = {"-f", "--format"}, description = "yaml or json (default: from -o).")
        String format;

        @Option(names = "--no-local-imports", description = "Do not read local XSD imports.")
        boolean noLocalImports;

        @Override
        public Integer call() {
            ConversionResult result = Converters.convertSource(root.read(input), !noLocalImports);
            result.warnings().forEach(w -> root.err().println("note: " + w));
            String fmt = format != null ? format : SpecIO.formatForPath(output);
            write(root, output, SpecIO.dump(result.document(), fmt));
            return EXIT_OK;
        }
    }

    @Command(name = "lint", mixinStandardHelpOptions = true,
            description = "Lint an OpenAPI 3.x document against the standard ruleset.")
    static final class Lint implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Parameters(paramLabel = "INPUT")
        Path input;

        @Option(names = {"-c", "--config"}, description = "Config file.")
        String configPath;

        @Option(names = "--fail-on", description = "error | warning | info | never")
        String failOn;

        @Option(names = "--format", defaultValue = "text", description = "text | json | sarif")
        String format;

        @Option(names = {"-o", "--output"}, description = "Write the report here.")
        String output;

        @Override
        public Integer call() {
            SourceFile source = root.read(input);
            Map<String, Object> doc = requireOas3(source);
            LintConfig config = config(configPath, failOn);
            List<Finding> findings = Linter.lint(doc, config, SpecIO.lineIndex(source.text()));
            String name = input.toString();
            String report = switch (format) {
                case "json" -> Reports.json(findings, name);
                case "sarif" -> Reports.sarif(findings, name);
                default -> Reports.text(findings, name);
            };
            write(root, output, report);
            return exitFor(findings, config);
        }
    }

    @Command(name = "fix", mixinStandardHelpOptions = true,
            description = "Apply safe auto-fixes to an OpenAPI 3.x document.")
    static final class Fix implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Parameters(paramLabel = "INPUT")
        Path input;

        @Option(names = {"-o", "--output"}, description = "Write result here (default: stdout).")
        String output;

        @Option(names = {"-c", "--config"}, description = "Config file.")
        String configPath;

        @Override
        public Integer call() {
            Map<String, Object> doc = requireOas3(root.read(input));
            Fixer.Result result = Fixer.apply(doc, config(configPath, null));
            result.applied().forEach(f -> root.err().println(
                    "fixed [" + f.rule() + "] " + f.message()));
            write(root, output, SpecIO.dump(result.document(),
                    SpecIO.formatForPath(output != null ? output : input.toString())));
            return EXIT_OK;
        }
    }

    @Command(name = "modernize", mixinStandardHelpOptions = true,
            description = "Convert, auto-fix and lint in one step.")
    static final class Modernize implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Parameters(paramLabel = "INPUT")
        Path input;

        @Option(names = {"-o", "--output"}, description = "Write result here (default: stdout).")
        String output;

        @Option(names = {"-c", "--config"}, description = "Config file.")
        String configPath;

        @Option(names = "--fail-on", description = "error | warning | info | never")
        String failOn;

        @Option(names = "--report", description = "Write a Markdown report.")
        String report;

        @Option(names = "--sarif", description = "Write findings as SARIF.")
        String sarif;

        @Option(names = "--no-fix", description = "Convert and lint only; skip auto-fixes.")
        boolean noFix;

        @Option(names = "--no-local-imports", description = "Do not read local XSD imports.")
        boolean noLocalImports;

        @Override
        public Integer call() {
            LintConfig config = config(configPath, failOn);
            Modernizer.Result result = Modernizer.modernize(root.read(input), config, !noFix,
                    SpecIO.formatForPath(output), !noLocalImports);
            write(root, output, result.text());
            String outName = output != null ? fileName(output) : "stdout";
            if (report != null) {
                writeFile(report, Reports.markdown(fileName(input.toString()),
                        result.sourceKind().label(), outName, result.warnings(), result.fixes(),
                        result.findings()));
            }
            if (sarif != null) {
                writeFile(sarif, Reports.sarif(result.findings(),
                        output != null ? output : input.toString()));
            }
            result.warnings().forEach(w -> root.err().println("note: " + w));
            root.err().print(Reports.text(result.findings(), outName));
            root.err().flush();
            return exitFor(result.findings(), config);
        }

        private static String fileName(String path) {
            Path name = Path.of(path).getFileName();
            return name == null ? path : name.toString();
        }

        private static void writeFile(String path, String text) {
            try {
                Files.writeString(Path.of(path), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Command(name = "rules", mixinStandardHelpOptions = true,
            description = "List the built-in lint rules.")
    static final class ListRules implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Option(names = "--format", defaultValue = "text", description = "text | json")
        String format;

        @Override
        public Integer call() {
            List<Rule> rules = new ArrayList<>(Rules.ALL.values());
            rules.sort((a, b) -> a.id().compareTo(b.id()));
            if (format.equals("json")) {
                List<Object> items = new ArrayList<>();
                rules.forEach(r -> items.add(Json.obj("id", r.id(), "severity",
                        r.severity().value(), "fixable", r.fixable(), "description",
                        r.description())));
                Map<String, Object> wrapper = Json.obj("rules", items);
                String text = SpecIO.dump(wrapper, "json");
                // print the bare array, matching the other implementations
                root.out().print(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)
                        .replace("\n  ", "\n") + "\n");
            } else {
                for (Rule r : rules) {
                    root.out().printf("%-26s %-7s %s  %s%n", r.id(), r.severity().value(),
                            r.fixable() ? "fix" : "   ", r.description());
                }
            }
            root.out().flush();
            return EXIT_OK;
        }
    }

    @Command(name = "serve", mixinStandardHelpOptions = true,
            description = "Start the REST API and web UI (Spring Boot).")
    static final class Serve implements Callable<Integer> {
        @ParentCommand
        Main root;

        @Option(names = "--host", defaultValue = "127.0.0.1", description = "Interface to bind.")
        String host;

        @Option(names = "--port", defaultValue = "8080", description = "Port to listen on.")
        int port;

        @Override
        public Integer call() {
            root.launcher.accept(new String[] {"--server.address=" + host, "--server.port=" + port});
            return EXIT_OK;
        }
    }

    /** Builds the picocli command line with spec-lint's error handling. */
    static CommandLine commandLine() {
        return commandLine(new Main());
    }

    static CommandLine commandLine(Main main) {
        CommandLine cli = new CommandLine(main);
        cli.setExecutionExceptionHandler((ex, cmd, parsed) -> {
            Throwable cause = ex instanceof UncheckedIOException u ? u.getCause() : ex;
            if (cause instanceof SpecLintException || cause instanceof IOException) {
                cmd.getErr().println("error: " + cause.getMessage());
                cmd.getErr().flush();
                return EXIT_ERROR;
            }
            throw ex;
        });
        return cli;
    }

    public static void main(String[] args) {
        int code = commandLine().execute(args);
        if (args.length == 0 || !args[0].equals("serve") || code != EXIT_OK) {
            System.exit(code); // `serve` keeps running until the server is stopped
        }
    }
}
