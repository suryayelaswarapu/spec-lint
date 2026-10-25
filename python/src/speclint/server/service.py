"""Framework-free request handling, so the HTTP layer stays thin and easy to test.

Every function takes the parsed JSON body and returns the JSON response body. The service
never reads the server's file system (local XSD imports are disabled) and never fetches
remote references.
"""

from __future__ import annotations

import re
from pathlib import Path, PurePath
from typing import Any

from speclint import __version__
from speclint.convert import convert_source
from speclint.detect import SpecKind, detect_document, looks_like_xml
from speclint.errors import InputError, SpecLintError
from speclint.fix import apply_fixes
from speclint.io import SourceFile, dump, line_index, parse_structured
from speclint.lint import RULES, LintConfig, lint, summarize
from speclint.lint.config import config_from_mapping
from speclint.modernize import modernize
from speclint.report import render_markdown

IMPLEMENTATION = "python"
_UNSAFE_NAME = re.compile(r"[^A-Za-z0-9._ -]")


def safe_filename(name: Any) -> str:
    """Keep only a short, plain base name; it is used in messages and the report only."""
    base = str(name or "").replace("\\", "/").rsplit("/", 1)[-1].strip()
    base = _UNSAFE_NAME.sub("_", base)[:200]
    return base or "spec"


def _source(body: dict[str, Any]) -> SourceFile:
    spec = body.get("spec")
    if not isinstance(spec, str) or not spec.strip():
        raise InputError("'spec' must be a non-empty string")
    # A virtual path: nothing is ever read from it (local imports are disabled).
    return SourceFile(path=Path(safe_filename(body.get("filename"))), text=spec)


def _config(body: dict[str, Any]) -> LintConfig:
    raw = body.get("config")
    if raw is None:
        return LintConfig()
    if not isinstance(raw, dict):
        raise InputError("'config' must be an object")
    return config_from_mapping({k: v for k, v in raw.items() if k in ("extends", "rules")})


def _require_oas3(source: SourceFile) -> dict[str, Any]:
    if looks_like_xml(source.text):
        raise InputError("Input is WSDL; use /api/v1/modernize or /api/v1/convert")
    doc = parse_structured(source.text, source.name)
    if detect_document(doc) is SpecKind.SWAGGER2:
        raise InputError("Input is Swagger 2.0; use /api/v1/modernize or /api/v1/convert")
    return doc


def health() -> dict[str, Any]:
    return {"status": "UP", "version": __version__, "implementation": IMPLEMENTATION}


def rules() -> dict[str, Any]:
    return {
        "rules": [
            {
                "id": r.id,
                "severity": r.severity.value,
                "fixable": r.fixable,
                "description": r.description,
            }
            for r in sorted(RULES.values(), key=lambda r: r.id)
        ]
    }


def convert(body: dict[str, Any]) -> dict[str, Any]:
    result = convert_source(_source(body), allow_local_imports=False)
    return {
        "sourceKind": result.source_kind.value,
        "openapi": result.document,
        "yaml": dump(result.document, "yaml"),
        "warnings": result.warnings,
    }


def lint_spec(body: dict[str, Any]) -> dict[str, Any]:
    source = _source(body)
    findings = lint(_require_oas3(source), _config(body), line_index(source.text))
    return {"summary": summarize(findings), "findings": [f.to_dict() for f in findings]}


def fix_spec(body: dict[str, Any]) -> dict[str, Any]:
    source = _source(body)
    fixed, applied = apply_fixes(_require_oas3(source), _config(body))
    return {
        "openapi": fixed,
        "yaml": dump(fixed, "yaml"),
        "fixes": [{"rule": f.rule, "message": f.message, "path": f.path} for f in applied],
    }


def modernize_spec(body: dict[str, Any]) -> dict[str, Any]:
    source = _source(body)
    do_fix = body.get("fix", True)
    if not isinstance(do_fix, bool):
        raise InputError("'fix' must be a boolean")
    result = modernize(source, _config(body), fix=do_fix, allow_local_imports=False)
    output = PurePath(source.name).stem + ".openapi.yaml"
    return {
        "sourceKind": result.source_kind.value,
        "openapi": result.document,
        "yaml": result.text,
        "warnings": result.warnings,
        "fixes": [{"rule": f.rule, "message": f.message, "path": f.path} for f in result.fixes],
        "summary": summarize(result.findings),
        "findings": [f.to_dict() for f in result.findings],
        "report": render_markdown(
            source=source.name,
            source_kind=result.source_kind.label,
            output=output,
            warnings=result.warnings,
            fixes=result.fixes,
            findings=result.findings,
        ),
    }


__all__ = [
    "IMPLEMENTATION",
    "SpecLintError",
    "convert",
    "fix_spec",
    "health",
    "lint_spec",
    "modernize_spec",
    "rules",
    "safe_filename",
]
