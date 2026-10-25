"""Lint configuration (``.speclint.yaml``).

```yaml
extends: recommended     # or: strict
fail-on: error           # error | warning | info | never
rules:
  property-camel-case: off
  info-contact: error
```
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from speclint.errors import ConfigError, SpecLintError
from speclint.io import parse_structured, read_source
from speclint.lint.model import RULES, Severity

CONFIG_FILENAMES = (".speclint.yaml", ".speclint.yml")
FAIL_ON = ("error", "warning", "info", "never")


@dataclass
class LintConfig:
    extends: str = "recommended"
    fail_on: str = "error"
    overrides: dict[str, Severity] = field(default_factory=dict)

    def severity(self, rule_id: str) -> Severity:
        if rule_id in self.overrides:
            return self.overrides[rule_id]
        base = RULES[rule_id].severity
        if self.extends == "strict":
            return {Severity.WARNING: Severity.ERROR, Severity.INFO: Severity.WARNING}.get(
                base, base
            )
        return base

    def enabled(self, rule_id: str) -> bool:
        return self.severity(rule_id) is not Severity.OFF

    def fails(self, severity: Severity) -> bool:
        if self.fail_on == "never":
            return False
        return severity.rank >= Severity(self.fail_on).rank


def load_config(path: str | Path | None = None, search_dir: Path | None = None) -> LintConfig:
    """Load config from ``path``, else ``.speclint.yaml`` in ``search_dir``, else defaults."""
    if path is None:
        for name in CONFIG_FILENAMES:
            candidate = (search_dir or Path.cwd()) / name
            if candidate.is_file():
                path = candidate
                break
    if path is None:
        return LintConfig()
    try:
        data = parse_structured(read_source(path, max_bytes=256 * 1024).text, str(path))
    except SpecLintError as exc:
        raise ConfigError(f"Cannot read config {path}: {exc}") from exc
    return config_from_mapping(data)


def config_from_mapping(data: Mapping[str, Any]) -> LintConfig:
    """Build a config from a parsed ``.speclint.yaml`` (or an API request's ``config``)."""
    extends = str(data.get("extends", "recommended"))
    if extends not in ("recommended", "strict"):
        raise ConfigError(f"extends must be 'recommended' or 'strict', not {extends!r}")
    fail_on = str(data.get("fail-on", "error"))
    if fail_on not in FAIL_ON:
        raise ConfigError(f"fail-on must be one of {', '.join(FAIL_ON)}")

    overrides: dict[str, Severity] = {}
    raw_rules = data.get("rules") or {}
    if not isinstance(raw_rules, dict):
        raise ConfigError("rules must be a mapping of rule-id: severity")
    for rule_id, value in raw_rules.items():
        if rule_id not in RULES:
            raise ConfigError(f"Unknown rule '{rule_id}' (run 'speclint rules' to list them)")
        # YAML 1.1 reads a bare `off` as False
        level = "off" if value is False else str(value)
        try:
            overrides[rule_id] = Severity(level)
        except ValueError as exc:
            raise ConfigError(f"Rule '{rule_id}': invalid severity {value!r}") from exc
    return LintConfig(extends=extends, fail_on=fail_on, overrides=overrides)
