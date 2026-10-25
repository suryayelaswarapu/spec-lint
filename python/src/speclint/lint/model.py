"""Core lint types: severities, findings and the rule registry."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

Path = tuple[str, ...]


class Severity(str, Enum):
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"
    OFF = "off"

    @property
    def rank(self) -> int:
        return {"error": 3, "warning": 2, "info": 1, "off": 0}[self.value]


def pointer(path: Iterable[str]) -> str:
    """Encode a path as an RFC 6901 JSON Pointer (``('paths', '/a')`` -> ``/paths/~1a``)."""
    return "".join("/" + str(p).replace("~", "~0").replace("/", "~1") for p in path)


@dataclass(frozen=True)
class Finding:
    rule: str
    severity: Severity
    message: str
    path: Path = ()
    line: int | None = None

    @property
    def pointer(self) -> str:
        return pointer(self.path)

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "rule": self.rule,
            "severity": self.severity.value,
            "message": self.message,
            "path": self.pointer,
        }
        if self.line is not None:
            out["line"] = self.line
        return out


Check = Callable[[dict[str, Any]], Iterator[tuple[str, Path]]]


@dataclass(frozen=True)
class Rule:
    id: str
    severity: Severity
    description: str
    check: Check = field(repr=False, compare=False)
    fixable: bool = False


RULES: dict[str, Rule] = {}


def rule(
    rule_id: str, severity: Severity, description: str, *, fixable: bool = False
) -> Callable[[Check], Check]:
    """Register a check function as a lint rule."""

    def decorator(fn: Check) -> Check:
        if rule_id in RULES:  # pragma: no cover - programming error
            raise ValueError(f"duplicate rule id {rule_id}")
        RULES[rule_id] = Rule(rule_id, severity, description, fn, fixable)
        return fn

    return decorator
