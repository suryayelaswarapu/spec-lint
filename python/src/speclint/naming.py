"""Identifier case helpers shared by converters, rules and fixes."""

from __future__ import annotations

import re

_WORD_SPLIT = re.compile(r"[^A-Za-z0-9]+")
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

CAMEL_CASE = re.compile(r"^[a-z][a-zA-Z0-9]*$")
PASCAL_CASE = re.compile(r"^[A-Z][a-zA-Z0-9]*$")
KEBAB_CASE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def words(name: str) -> list[str]:
    """Split ``GetForecast_byDay-v2`` into ``['Get', 'Forecast', 'by', 'Day', 'v2']``."""
    out: list[str] = []
    for chunk in _WORD_SPLIT.split(name):
        if chunk:
            out.extend(p for p in _CAMEL_BOUNDARY.split(chunk) if p)
    return out


def to_camel(name: str) -> str:
    parts = words(name)
    if not parts:
        return name
    return parts[0].lower() + "".join(p[:1].upper() + p[1:].lower() for p in parts[1:])


def to_pascal(name: str) -> str:
    """``forecast_day`` -> ``ForecastDay``; existing capitals are kept (``NDFDgen`` stays)."""
    return "".join(p[:1].upper() + p[1:] for p in words(name)) or name


def to_kebab(name: str) -> str:
    return "-".join(p.lower() for p in words(name)) or name


def unique(name: str, taken: set[str] | dict[str, object]) -> str:
    """Return ``name`` or ``name2``, ``name3``... whichever is not in ``taken``."""
    if name not in taken:
        return name
    i = 2
    while f"{name}{i}" in taken:
        i += 1
    return f"{name}{i}"
