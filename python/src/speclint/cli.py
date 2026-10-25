"""Command-line interface.

Exit codes: ``0`` success, ``1`` findings at or above ``--fail-on``, ``2`` usage/input error.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import click

from speclint import __version__
from speclint.convert import convert_source
from speclint.detect import SpecKind, detect_document, looks_like_xml
from speclint.errors import SpecLintError
from speclint.fix import apply_fixes
from speclint.io import (
    DEFAULT_MAX_BYTES,
    SourceFile,
    dump,
    format_for_path,
    line_index,
    parse_structured,
    read_source,
)
from speclint.lint import RULES, Finding, LintConfig, lint, load_config
from speclint.modernize import modernize
from speclint.report import render_json, render_markdown, render_sarif, render_text

EXIT_OK, EXIT_FINDINGS, EXIT_ERROR = 0, 1, 2

_input = click.argument("input_file", metavar="INPUT", type=click.Path(dir_okay=False))
_output = click.option(
    "-o", "--output", type=click.Path(dir_okay=False), help="Write result here (default: stdout)."
)
_config = click.option(
    "-c",
    "--config",
    "config_path",
    type=click.Path(dir_okay=False),
    help="Config file (default: ./.speclint.yaml if present).",
)
_fail_on = click.option(
    "--fail-on",
    type=click.Choice(["error", "warning", "info", "never"]),
    help="Lowest severity that makes the command exit 1 (overrides config).",
)


class _Ctx:
    def __init__(self, max_size: int) -> None:
        self.max_size = max_size


def _read(ctx: _Ctx, path: str) -> SourceFile:
    return read_source(path, ctx.max_size)


def _write(path: str | None, text: str) -> None:
    if path is None:
        click.echo(text, nl=False)
    else:
        Path(path).write_text(text, encoding="utf-8")


def _config_for(config_path: str | None, fail_on: str | None) -> LintConfig:
    config = load_config(config_path)
    if fail_on:
        config.fail_on = fail_on
    return config


def _exit_for(findings: list[Finding], config: LintConfig) -> int:
    return EXIT_FINDINGS if any(config.fails(f.severity) for f in findings) else EXIT_OK


def _render(findings: list[Finding], name: str, fmt: str) -> str:
    if fmt == "json":
        return render_json(findings, name)
    if fmt == "sarif":
        return render_sarif(findings, name)
    return render_text(findings, name)


def _require_oas3(source: SourceFile) -> dict[str, Any]:
    if looks_like_xml(source.text):
        raise SpecLintError(f"{source.name} is WSDL; run 'speclint modernize' to convert it first")
    doc = parse_structured(source.text, source.name)
    if detect_document(doc) is SpecKind.SWAGGER2:
        raise SpecLintError(
            f"{source.name} is Swagger 2.0; run 'speclint modernize' (or 'convert') first"
        )
    return doc


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.version_option(__version__, prog_name="speclint")
@click.option(
    "--max-size",
    default=DEFAULT_MAX_BYTES,
    show_default=True,
    type=click.IntRange(min=1),
    help="Maximum input file size in bytes.",
)
@click.pass_context
def cli(ctx: click.Context, max_size: int) -> None:
    """Modernize legacy API contracts (Swagger 2.0, WSDL 1.1) into linted OpenAPI 3.1."""
    ctx.obj = _Ctx(max_size)


@cli.command()
@_input
@_output
@click.option(
    "-f",
    "--format",
    "fmt",
    type=click.Choice(["yaml", "json"]),
    default=None,
    help="Output format (default: from -o extension, else yaml).",
)
@click.option("--no-local-imports", is_flag=True, help="Do not read local XSD imports.")
@click.pass_obj
def convert(
    ctx: _Ctx, input_file: str, output: str | None, fmt: str | None, no_local_imports: bool
) -> None:
    """Convert Swagger 2.0 / OpenAPI 3.0 / WSDL 1.1 to OpenAPI 3.1 (no fixes, no lint)."""
    result = convert_source(_read(ctx, input_file), allow_local_imports=not no_local_imports)
    for warning in result.warnings:
        click.echo(f"note: {warning}", err=True)
    _write(output, dump(result.document, fmt or format_for_path(output)))


@cli.command("lint")
@_input
@_config
@_fail_on
@click.option(
    "--format",
    "fmt",
    type=click.Choice(["text", "json", "sarif"]),
    default="text",
    show_default=True,
)
@_output
@click.pass_obj
def lint_cmd(
    ctx: _Ctx,
    input_file: str,
    config_path: str | None,
    fail_on: str | None,
    fmt: str,
    output: str | None,
) -> None:
    """Lint an OpenAPI 3.x document against the standard ruleset."""
    source = _read(ctx, input_file)
    doc = _require_oas3(source)
    config = _config_for(config_path, fail_on)
    findings = lint(doc, config, line_index(source.text))
    _write(output, _render(findings, input_file, fmt))
    sys.exit(_exit_for(findings, config))


@cli.command()
@_input
@_output
@_config
@click.pass_obj
def fix(ctx: _Ctx, input_file: str, output: str | None, config_path: str | None) -> None:
    """Apply safe auto-fixes to an OpenAPI 3.x document."""
    source = _read(ctx, input_file)
    doc = _require_oas3(source)
    fixed, applied = apply_fixes(doc, _config_for(config_path, None))
    for f in applied:
        click.echo(f"fixed [{f.rule}] {f.message}", err=True)
    _write(output, dump(fixed, format_for_path(output or input_file)))


@cli.command("modernize")
@_input
@_output
@_config
@_fail_on
@click.option("--report", type=click.Path(dir_okay=False), help="Write a Markdown report.")
@click.option("--sarif", type=click.Path(dir_okay=False), help="Write findings as SARIF.")
@click.option("--no-fix", is_flag=True, help="Convert and lint only; skip auto-fixes.")
@click.option("--no-local-imports", is_flag=True, help="Do not read local XSD imports.")
@click.pass_obj
def modernize_cmd(
    ctx: _Ctx,
    input_file: str,
    output: str | None,
    config_path: str | None,
    fail_on: str | None,
    report: str | None,
    sarif: str | None,
    no_fix: bool,
    no_local_imports: bool,
) -> None:
    """Convert, auto-fix and lint in one step; the main command for legacy specs."""
    config = _config_for(config_path, fail_on)
    result = modernize(
        _read(ctx, input_file),
        config,
        fix=not no_fix,
        fmt=format_for_path(output),
        allow_local_imports=not no_local_imports,
    )
    _write(output, result.text)
    out_name = Path(output).name if output else "stdout"
    if report:
        Path(report).write_text(
            render_markdown(
                source=Path(input_file).name,
                source_kind=result.source_kind.label,
                output=out_name,
                warnings=result.warnings,
                fixes=result.fixes,
                findings=result.findings,
            ),
            encoding="utf-8",
        )
    if sarif:
        Path(sarif).write_text(render_sarif(result.findings, output or input_file), "utf-8")
    for warning in result.warnings:
        click.echo(f"note: {warning}", err=True)
    click.echo(render_text(result.findings, out_name), err=True, nl=False)
    sys.exit(_exit_for(result.findings, config))


@cli.command("rules")
@click.option("--format", "fmt", type=click.Choice(["text", "json"]), default="text")
def rules_cmd(fmt: str) -> None:
    """List the built-in lint rules."""
    if fmt == "json":
        payload = [
            {
                "id": r.id,
                "severity": r.severity.value,
                "fixable": r.fixable,
                "description": r.description,
            }
            for r in sorted(RULES.values(), key=lambda r: r.id)
        ]
        click.echo(json.dumps(payload, indent=2))
        return
    for r in sorted(RULES.values(), key=lambda r: r.id):
        mark = "fix" if r.fixable else "   "
        click.echo(f"{r.id:<26} {r.severity.value:<7} {mark}  {r.description}")


@cli.command("serve")
@click.option("--host", default="127.0.0.1", show_default=True, help="Interface to bind.")
@click.option("--port", default=8080, show_default=True, type=click.IntRange(1, 65535))
def serve_cmd(host: str, port: int) -> None:
    """Start the REST API and web UI (needs the 'server' extra)."""
    try:
        import uvicorn

        from speclint.server.app import Settings, create_app
    except ImportError as exc:  # pragma: no cover - depends on the environment
        raise SpecLintError("The server needs: pip install 'spec-lint[server]'") from exc
    try:
        settings = Settings.from_env()
    except ValueError as exc:
        raise SpecLintError(str(exc)) from exc
    click.echo(f"spec-lint (python) listening on http://{host}:{port}", err=True)
    uvicorn.run(create_app(settings), host=host, port=port, log_level="info", server_header=False)


def main() -> None:
    try:
        cli.main(standalone_mode=False)
    except SpecLintError as exc:
        click.echo(f"error: {exc}", err=True)
        sys.exit(EXIT_ERROR)
    except click.exceptions.Abort:  # pragma: no cover
        sys.exit(EXIT_ERROR)
    except click.ClickException as exc:
        exc.show()
        sys.exit(EXIT_ERROR)
    except OSError as exc:
        click.echo(f"error: {exc.strerror or exc}: {exc.filename or ''}".rstrip(": "), err=True)
        sys.exit(EXIT_ERROR)
