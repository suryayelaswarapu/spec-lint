package app

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/suryayelaswarapu/spec-lint/go/internal/convert"
	"github.com/suryayelaswarapu/spec-lint/go/internal/doc"
	"github.com/suryayelaswarapu/spec-lint/go/internal/fix"
	"github.com/suryayelaswarapu/spec-lint/go/internal/lint"
	"github.com/suryayelaswarapu/spec-lint/go/internal/pipeline"
	"github.com/suryayelaswarapu/spec-lint/go/internal/report"
	"github.com/suryayelaswarapu/spec-lint/go/internal/server"
	"github.com/suryayelaswarapu/spec-lint/go/internal/specio"
)

// Exit codes shared by all implementations.
const (
	ExitOK       = 0
	ExitFindings = 1
	ExitError    = 2
)

const usage = `speclint: modernize legacy API contracts (Swagger 2.0, WSDL 1.1) into linted OpenAPI 3.1.

Usage:
  speclint [--max-size BYTES] <command> [options] INPUT

Commands:
  convert    Convert Swagger 2.0 / OpenAPI 3.0 / WSDL 1.1 to OpenAPI 3.1
  lint       Lint an OpenAPI 3.x document against the standard ruleset
  fix        Apply safe auto-fixes to an OpenAPI 3.x document
  modernize  Convert, auto-fix and lint in one step
  rules      List the built-in lint rules
  serve      Start the REST API and web UI (--host, --port)

Exit codes: 0 success, 1 findings at or above --fail-on, 2 usage or input error.
`

type cli struct {
	stdout, stderr io.Writer
	maxSize        int64
}

// Run executes the CLI and returns the exit code.
func Run(args []string, stdout, stderr io.Writer) int {
	c := &cli{stdout: stdout, stderr: stderr, maxSize: specio.DefaultMaxBytes}
	global := flag.NewFlagSet("speclint", flag.ContinueOnError)
	global.SetOutput(io.Discard)
	global.Int64Var(&c.maxSize, "max-size", specio.DefaultMaxBytes, "maximum input size in bytes")
	version := global.Bool("version", false, "print version")
	help := global.Bool("help", false, "show help")
	global.BoolVar(help, "h", false, "show help")
	if err := global.Parse(args); err != nil {
		fmt.Fprintf(stderr, "error: %v\n\n%s", err, usage)
		return ExitError
	}
	if *version {
		fmt.Fprintf(stdout, "speclint %s\n", report.Version)
		return ExitOK
	}
	rest := global.Args()
	if *help || len(rest) == 0 {
		fmt.Fprint(stdout, usage)
		if *help {
			return ExitOK
		}
		return ExitError
	}
	if c.maxSize < 1 {
		fmt.Fprintln(stderr, "error: --max-size must be at least 1")
		return ExitError
	}
	code, err := c.dispatch(rest[0], rest[1:])
	if err != nil {
		fmt.Fprintf(stderr, "error: %v\n", err)
		return ExitError
	}
	return code
}

// parseInterspersed lets options appear before or after the positional argument.
func parseInterspersed(fs *flag.FlagSet, args []string) ([]string, error) {
	var positional []string
	for {
		if err := fs.Parse(args); err != nil {
			return nil, err
		}
		if fs.NArg() == 0 {
			return positional, nil
		}
		positional = append(positional, fs.Arg(0))
		args = fs.Args()[1:]
	}
}

type options struct {
	output, format, config, failOn, report, sarif string
	noFix, noLocalImports                         bool
}

func newFlags(name string, o *options, which ...string) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	for _, w := range which {
		switch w {
		case "output":
			fs.StringVar(&o.output, "o", "", "output file")
			fs.StringVar(&o.output, "output", "", "output file")
		case "format":
			fs.StringVar(&o.format, "f", "", "format")
			fs.StringVar(&o.format, "format", "", "format")
		case "config":
			fs.StringVar(&o.config, "c", "", "config file")
			fs.StringVar(&o.config, "config", "", "config file")
		case "fail-on":
			fs.StringVar(&o.failOn, "fail-on", "", "error|warning|info|never")
		case "report":
			fs.StringVar(&o.report, "report", "", "markdown report")
		case "sarif":
			fs.StringVar(&o.sarif, "sarif", "", "sarif report")
		case "no-fix":
			fs.BoolVar(&o.noFix, "no-fix", false, "skip fixes")
		case "no-local-imports":
			fs.BoolVar(&o.noLocalImports, "no-local-imports", false, "skip local XSD imports")
		}
	}
	return fs
}

func (c *cli) dispatch(cmd string, args []string) (int, error) {
	var o options
	var fs *flag.FlagSet
	switch cmd {
	case "convert":
		fs = newFlags(cmd, &o, "output", "format", "no-local-imports")
	case "lint":
		fs = newFlags(cmd, &o, "output", "format", "config", "fail-on")
	case "fix":
		fs = newFlags(cmd, &o, "output", "config")
	case "modernize":
		fs = newFlags(cmd, &o, "output", "config", "fail-on", "report", "sarif", "no-fix",
			"no-local-imports")
	case "rules":
		fs = newFlags(cmd, &o, "format")
	case "serve":
		fs = flag.NewFlagSet(cmd, flag.ContinueOnError)
		fs.SetOutput(io.Discard)
		host := fs.String("host", "127.0.0.1", "interface to bind")
		port := fs.Int("port", 8080, "port to listen on")
		if _, err := parseInterspersed(fs, args); err != nil {
			return c.flagError(cmd, fs, err)
		}
		return c.serve(*host, *port)
	default:
		return ExitError, fmt.Errorf("unknown command %q (run 'speclint --help')", cmd)
	}
	positional, err := parseInterspersed(fs, args)
	if err != nil {
		return c.flagError(cmd, fs, err)
	}
	if o.failOn != "" && !contains(lint.FailOnValues, o.failOn) {
		return ExitError, fmt.Errorf("--fail-on must be one of %s", strings.Join(lint.FailOnValues, ", "))
	}
	if cmd == "rules" {
		return c.rules(o)
	}
	if len(positional) != 1 {
		return ExitError, fmt.Errorf("%s expects exactly one INPUT file", cmd)
	}
	input := positional[0]
	switch cmd {
	case "convert":
		return c.convert(input, o)
	case "lint":
		return c.lint(input, o)
	case "fix":
		return c.fix(input, o)
	}
	return c.modernize(input, o)
}

var commandSummary = map[string]string{
	"convert":   "Convert Swagger 2.0 / OpenAPI 3.0 / WSDL 1.1 to OpenAPI 3.1.",
	"lint":      "Lint an OpenAPI 3.x document against the standard ruleset.",
	"fix":       "Apply safe auto-fixes to an OpenAPI 3.x document.",
	"modernize": "Convert, auto-fix and lint in one step.",
	"rules":     "List the built-in lint rules.",
	"serve":     "Start the REST API and web UI.",
}

// flagError turns -h/--help into the command's help (exit 0) and anything else into a usage error.
func (c *cli) flagError(cmd string, fs *flag.FlagSet, err error) (int, error) {
	if !errors.Is(err, flag.ErrHelp) {
		return ExitError, err
	}
	arg := " INPUT"
	if cmd == "rules" || cmd == "serve" {
		arg = ""
	}
	fmt.Fprintf(c.stdout, "%s\n\nUsage:\n  speclint %s [options]%s\n\nOptions:\n", commandSummary[cmd], cmd, arg)
	fs.SetOutput(c.stdout)
	fs.PrintDefaults()
	return ExitOK, nil
}

func contains(list []string, v string) bool {
	for _, e := range list {
		if e == v {
			return true
		}
	}
	return false
}

func (c *cli) write(path, text string) error {
	if path == "" {
		_, err := io.WriteString(c.stdout, text)
		return err
	}
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		var pe *os.PathError
		if errors.As(err, &pe) {
			return fmt.Errorf("cannot write %s: %v", path, pe.Err)
		}
		return err
	}
	return nil
}

func (c *cli) config(o options) (*lint.Config, error) {
	cfg, err := lint.LoadConfig(o.config, "")
	if err != nil {
		return nil, err
	}
	if o.failOn != "" {
		cfg.FailOn = o.failOn
	}
	return cfg, nil
}

func exitFor(findings []lint.Finding, cfg *lint.Config) int {
	for _, f := range findings {
		if cfg.Fails(f.Severity) {
			return ExitFindings
		}
	}
	return ExitOK
}

func requireOAS3(src specio.SourceFile) (*doc.Map, error) {
	if specio.LooksLikeXML(src.Text) {
		return nil, fmt.Errorf("%s is WSDL; run 'speclint modernize' to convert it first", src.Name())
	}
	d, err := specio.ParseStructured(src.Text, src.Name())
	if err != nil {
		return nil, err
	}
	if kind, err := specio.DetectDocument(d); err != nil {
		return nil, err
	} else if kind == specio.Swagger2 {
		return nil, fmt.Errorf("%s is Swagger 2.0; run 'speclint modernize' (or 'convert') first", src.Name())
	}
	return d, nil
}

func (c *cli) convert(input string, o options) (int, error) {
	src, err := specio.ReadSource(input, c.maxSize)
	if err != nil {
		return ExitError, err
	}
	res, err := convert.ConvertSource(src, !o.noLocalImports)
	if err != nil {
		return ExitError, err
	}
	for _, w := range res.Warnings {
		fmt.Fprintf(c.stderr, "note: %s\n", w)
	}
	format := o.format
	if format == "" {
		format = specio.FormatForPath(o.output)
	}
	text, err := specio.Dump(res.Document, format)
	if err != nil {
		return ExitError, err
	}
	return ExitOK, c.write(o.output, text)
}

func (c *cli) lint(input string, o options) (int, error) {
	src, err := specio.ReadSource(input, c.maxSize)
	if err != nil {
		return ExitError, err
	}
	d, err := requireOAS3(src)
	if err != nil {
		return ExitError, err
	}
	cfg, err := c.config(o)
	if err != nil {
		return ExitError, err
	}
	findings := lint.Lint(d, cfg, specio.LineIndex(src.Text))
	var out string
	switch o.format {
	case "json":
		out = report.JSON(findings, input)
	case "sarif":
		out = report.SARIF(findings, input)
	case "", "text":
		out = report.Text(findings, input)
	default:
		return ExitError, fmt.Errorf("--format must be text, json or sarif")
	}
	if err := c.write(o.output, out); err != nil {
		return ExitError, err
	}
	return exitFor(findings, cfg), nil
}

func (c *cli) fix(input string, o options) (int, error) {
	src, err := specio.ReadSource(input, c.maxSize)
	if err != nil {
		return ExitError, err
	}
	d, err := requireOAS3(src)
	if err != nil {
		return ExitError, err
	}
	cfg, err := c.config(o)
	if err != nil {
		return ExitError, err
	}
	fixed, applied := fix.Apply(d, cfg)
	for _, a := range applied {
		fmt.Fprintf(c.stderr, "fixed [%s] %s\n", a.Rule, a.Message)
	}
	target := o.output
	if target == "" {
		target = input
	}
	text, _ := specio.Dump(fixed, specio.FormatForPath(target))
	return ExitOK, c.write(o.output, text)
}

func (c *cli) modernize(input string, o options) (int, error) {
	cfg, err := c.config(o)
	if err != nil {
		return ExitError, err
	}
	src, err := specio.ReadSource(input, c.maxSize)
	if err != nil {
		return ExitError, err
	}
	res, err := pipeline.Modernize(src, cfg, !o.noFix, specio.FormatForPath(o.output), !o.noLocalImports)
	if err != nil {
		return ExitError, err
	}
	if err := c.write(o.output, res.Text); err != nil {
		return ExitError, err
	}
	outName := "stdout"
	if o.output != "" {
		outName = filepath.Base(o.output)
	}
	if o.report != "" {
		md := report.Markdown(filepath.Base(input), res.SourceKind.Label(), outName, res.Warnings,
			res.Fixes, res.Findings)
		if err := c.write(o.report, md); err != nil {
			return ExitError, err
		}
	}
	if o.sarif != "" {
		name := input
		if o.output != "" {
			name = o.output
		}
		if err := c.write(o.sarif, report.SARIF(res.Findings, name)); err != nil {
			return ExitError, err
		}
	}
	for _, w := range res.Warnings {
		fmt.Fprintf(c.stderr, "note: %s\n", w)
	}
	fmt.Fprint(c.stderr, report.Text(res.Findings, outName))
	return exitFor(res.Findings, cfg), nil
}

// listen is replaceable in tests so no real server is started.
var listen = func(srv *http.Server) error { return srv.ListenAndServe() }

func (c *cli) serve(host string, port int) (int, error) {
	if port < 1 || port > 65535 {
		return ExitError, fmt.Errorf("--port must be between 1 and 65535")
	}
	settings, err := server.SettingsFromEnv()
	if err != nil {
		return ExitError, err
	}
	addr := net.JoinHostPort(host, strconv.Itoa(port))
	fmt.Fprintf(c.stderr, "spec-lint (go) listening on http://%s\n", addr)
	if err := listen(server.NewServer(addr, settings)); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return ExitError, err
	}
	return ExitOK, nil
}

func (c *cli) rules(o options) (int, error) {
	rules := append([]lint.Rule(nil), lint.Rules()...)
	sort.Slice(rules, func(i, j int) bool { return rules[i].ID < rules[j].ID })
	if o.format == "json" {
		type item struct {
			ID          string `json:"id"`
			Severity    string `json:"severity"`
			Fixable     bool   `json:"fixable"`
			Description string `json:"description"`
		}
		items := make([]item, 0, len(rules))
		for _, r := range rules {
			items = append(items, item{r.ID, string(r.Severity), r.Fixable, r.Description})
		}
		out, _ := json.MarshalIndent(items, "", "  ")
		fmt.Fprintln(c.stdout, string(out))
		return ExitOK, nil
	}
	for _, r := range rules {
		mark := "   "
		if r.Fixable {
			mark = "fix"
		}
		fmt.Fprintf(c.stdout, "%-26s %-7s %s  %s\n", r.ID, r.Severity, mark, r.Description)
	}
	return ExitOK, nil
}
