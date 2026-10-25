"""Linting: run the registered rules over an OpenAPI 3.x document."""

from __future__ import annotations

from typing import Any

from speclint.lint import rules as _rules  # noqa: F401  (registers the rules)
from speclint.lint.config import LintConfig, load_config
from speclint.lint.model import RULES, Finding, Rule, Severity, pointer


def lint(
    doc: dict[str, Any],
    config: LintConfig | None = None,
    lines: dict[tuple[str, ...], int] | None = None,
) -> list[Finding]:
    """Run every enabled rule and return findings sorted by location."""
    config = config or LintConfig()
    findings: list[Finding] = []
    for rule_id, r in RULES.items():
        severity = config.severity(rule_id)
        if severity is Severity.OFF:
            continue
        for message, path in r.check(doc):
            line = None
            if lines:
                # Nearest ancestor that has a known line.
                for depth in range(len(path), -1, -1):
                    if path[:depth] in lines:
                        line = lines[path[:depth]]
                        break
            findings.append(Finding(rule_id, severity, message, tuple(path), line))
    findings.sort(key=lambda f: (f.line or 0, f.rule, f.pointer))
    return findings


def summarize(findings: list[Finding]) -> dict[str, int]:
    counts = {"error": 0, "warning": 0, "info": 0}
    for f in findings:
        counts[f.severity.value] += 1
    return counts


__all__ = [
    "RULES",
    "Finding",
    "LintConfig",
    "Rule",
    "Severity",
    "lint",
    "load_config",
    "pointer",
    "summarize",
]
