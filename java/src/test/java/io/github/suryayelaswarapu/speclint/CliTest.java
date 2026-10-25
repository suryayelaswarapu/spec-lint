package io.github.suryayelaswarapu.speclint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliTest {
    @TempDir
    Path tmp;

    record Run(int code, String out, String err) {}

    static Run run(String... args) {
        CommandLine cli = Main.commandLine();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int code = cli.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    static String td(String rel) {
        return TestSupport.testdata(rel).toString();
    }

    @Test
    void versionHelpAndRules() {
        assertThat(run("--version").out()).contains("speclint 0.1.0");
        assertThat(run().code()).isEqualTo(2);
        Run rules = run("rules");
        assertThat(rules.out()).contains("operation-id-required").contains("fix");
        Run json = run("rules", "--format", "json");
        assertThat(json.out().trim()).startsWith("[").endsWith("]").contains("servers-defined");
    }

    @Test
    void convert() throws IOException {
        Run r = run("convert", td("weather/legacy-forecast-rpc.wsdl"));
        assertThat(r.code()).isZero();
        assertThat(r.out()).startsWith("openapi: 3.1.0");
        assertThat(r.err()).contains("note:");
        Path target = tmp.resolve("out.json");
        assertThat(run("convert", td("weather/weather-api-v2.yaml"), "-o", target.toString())
                .code()).isZero();
        assertThat(Files.readString(target)).contains("\"openapi\": \"3.1.0\"");
        assertThat(run("convert", td("weather/weather-service.wsdl"), "-f", "json",
                "--no-local-imports").out()).startsWith("{");
    }

    @Test
    void lintFormatsAndExitCodes() throws IOException {
        String spec = td("openapi/weather-api-v3.0.yaml");
        Run r = run("lint", spec);
        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("openapi-version-3-1");
        assertThat(run("lint", spec, "--fail-on", "warning").code()).isEqualTo(1);
        assertThat(run("lint", spec, "--format", "json").out()).contains("\"summary\"");
        Path sarif = tmp.resolve("r.sarif");
        run("lint", spec, "--format", "sarif", "-o", sarif.toString());
        assertThat(Files.readString(sarif)).contains("\"2.1.0\"");
        Path cfg = tmp.resolve("cfg.yaml");
        Files.writeString(cfg, "fail-on: never\n");
        assertThat(run("lint", spec, "-c", cfg.toString(), "--fail-on", "info").code())
                .isEqualTo(1);
    }

    @Test
    void lintRejectsLegacyInputs() {
        Run swagger = run("lint", td("weather/weather-api-v2.yaml"));
        assertThat(swagger.code()).isEqualTo(2);
        assertThat(swagger.err()).contains("modernize");
        Run wsdl = run("lint", td("weather/weather-service.wsdl"));
        assertThat(wsdl.code()).isEqualTo(2);
        assertThat(wsdl.err()).contains("WSDL");
    }

    @Test
    void fix() throws IOException {
        Path out = tmp.resolve("fixed.yaml");
        Run r = run("fix", td("openapi/weather-api-v3.0.yaml"), "-o", out.toString());
        assertThat(r.code()).isZero();
        assertThat(r.err()).contains("fixed [no-legacy-nullable]");
        assertThat(Files.readString(out)).doesNotContain("nullable");
        assertThat(run("fix", td("openapi/weather-api-v3.0.yaml")).out()).contains("openapi:");
    }

    @Test
    void modernize() throws IOException {
        Path out = tmp.resolve("w.yaml");
        Path report = tmp.resolve("r.md");
        Path sarif = tmp.resolve("r.sarif");
        Run r = run("modernize", td("weather/weather-api-v2.yaml"), "-o", out.toString(),
                "--report", report.toString(), "--sarif", sarif.toString());
        assertThat(r.code()).isZero();
        assertThat(Files.readString(report)).contains("migration report")
                .doesNotContain(tmp.toString());
        assertThat(Files.readString(sarif)).contains("\"results\"");
        assertThat(r.err()).contains("problems");
        Run strict = run("modernize", td("weather/weather-service.wsdl"), "--no-fix",
                "--fail-on", "info", "--no-local-imports");
        assertThat(strict.code()).isEqualTo(1);
        assertThat(strict.out()).startsWith("openapi: 3.1.0");
        assertThat(run("modernize", td("weather/weather-service.wsdl")).code()).isZero();
    }

    @Test
    void errors() {
        Run missing = run("lint", tmp.resolve("missing.yaml").toString());
        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.err()).contains("not found");
        Run big = run("--max-size", "10", "lint", td("openapi/weather-api-v3.0.yaml"));
        assertThat(big.code()).isEqualTo(2);
        assertThat(big.err()).contains("limit");
        assertThat(run("lint", "--bogus").code()).isEqualTo(2);
        Run xxe = run("convert", td("security/xxe.wsdl"));
        assertThat(xxe.code()).isEqualTo(2);
        assertThat(xxe.err()).contains("Unsafe XML");
        Run badOut = run("convert", td("weather/weather-api-v2.yaml"), "-o",
                tmp.resolve("no/such/dir/x.yaml").toString());
        assertThat(badOut.code()).isEqualTo(2);
        assertThat(badOut.err()).contains("error:");
        Run badReport = run("modernize", td("weather/weather-api-v2.yaml"), "-o",
                tmp.resolve("ok.yaml").toString(), "--report",
                tmp.resolve("no/such/r.md").toString());
        assertThat(badReport.code()).isEqualTo(2);
    }
}
