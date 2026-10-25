package io.github.suryayelaswarapu.speclint;

import java.io.PrintWriter;
import java.io.StringWriter;
import picocli.CommandLine;

/** Test helper exposing package-private CLI hooks to other test packages. */
public final class CliAccess {
    private CliAccess() {}

    public static int run(String... args) {
        CommandLine cli = Main.commandLine();
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(new StringWriter()));
        return cli.execute(args);
    }

    /** Runs {@code serve} with a stub launcher and returns the arguments Spring would get. */
    public static String[] serveArgs(String... args) {
        java.util.concurrent.atomic.AtomicReference<String[]> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        Main main = new Main();
        main.launcher = seen::set;
        CommandLine cli = Main.commandLine(main);
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(new StringWriter()));
        if (cli.execute(args) != Main.EXIT_OK) {
            throw new AssertionError("serve failed");
        }
        return seen.get();
    }
}
