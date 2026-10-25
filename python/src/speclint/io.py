"""Safe reading, parsing and serialising of spec documents.

Security notes
--------------
* Files larger than ``max_bytes`` are rejected before they are read into memory.
* YAML is parsed with a restricted ``SafeLoader``: no Python object tags, a hard cap on
  alias (``*anchor``) usage to defeat "billion laughs" style expansion, and timestamps are
  kept as strings so ``version: 2024-01-01`` round-trips unchanged.
* XML is never parsed here; see :mod:`speclint.convert.xmlsafe`.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from speclint.errors import InputError, SecurityError

DEFAULT_MAX_BYTES = 10 * 1024 * 1024  # 10 MiB
MAX_YAML_ALIASES = 100


class _StrictSafeLoader(yaml.SafeLoader):
    """SafeLoader that caps alias usage and does not coerce timestamps."""

    def __init__(self, stream: str) -> None:
        super().__init__(stream)
        self._alias_count = 0

    def compose_node(self, parent: Any, index: Any) -> Any:
        if self.check_event(yaml.events.AliasEvent):
            self._alias_count += 1
            if self._alias_count > MAX_YAML_ALIASES:
                raise SecurityError(
                    f"YAML document uses more than {MAX_YAML_ALIASES} aliases; refusing to expand"
                )
        return super().compose_node(parent, index)


# Drop the implicit timestamp resolver so dates stay strings (OpenAPI is JSON-compatible).
_StrictSafeLoader.yaml_implicit_resolvers = {
    key: [(tag, regexp) for tag, regexp in resolvers if tag != "tag:yaml.org,2002:timestamp"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


class _Dumper(yaml.SafeDumper):
    """Dumper that never emits anchors/aliases and indents lists under their key."""

    def ignore_aliases(self, data: Any) -> bool:
        return True

    def increase_indent(self, flow: bool = False, indentless: bool = False) -> None:
        super().increase_indent(flow, False)


def _str_presenter(dumper: yaml.SafeDumper, data: str) -> yaml.ScalarNode:
    style = "|" if "\n" in data else None
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style=style)


_Dumper.add_representer(str, _str_presenter)


@dataclass(frozen=True)
class SourceFile:
    """A file read from disk, with its text and location."""

    path: Path
    text: str

    @property
    def name(self) -> str:
        return self.path.name


def read_source(path: str | Path, max_bytes: int = DEFAULT_MAX_BYTES) -> SourceFile:
    """Read a UTF-8 text file, enforcing a size limit."""
    p = Path(path)
    if not p.is_file():
        raise InputError(f"File not found: {p}")
    size = p.stat().st_size
    if size > max_bytes:
        raise SecurityError(f"{p.name} is {size} bytes; limit is {max_bytes} (use --max-size)")
    try:
        text = p.read_text(encoding="utf-8-sig")
    except UnicodeDecodeError as exc:
        raise InputError(f"{p.name} is not valid UTF-8: {exc}") from exc
    return SourceFile(path=p, text=text)


def parse_structured(text: str, name: str = "<input>") -> dict[str, Any]:
    """Parse JSON or YAML text into a mapping."""
    stripped = text.lstrip()
    try:
        if stripped.startswith("{"):
            data = json.loads(text)
        else:
            data = yaml.load(text, Loader=_StrictSafeLoader)  # noqa: S506 - restricted SafeLoader
    except (json.JSONDecodeError, yaml.YAMLError) as exc:
        raise InputError(f"{name}: could not parse as JSON/YAML: {exc}") from exc
    if not isinstance(data, dict):
        raise InputError(f"{name}: top level must be a mapping/object")
    return data


def dump(data: dict[str, Any], fmt: str = "yaml") -> str:
    """Serialise a document as ``yaml`` or ``json`` preserving key order."""
    if fmt == "json":
        return json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    if fmt == "yaml":
        return yaml.dump(data, Dumper=_Dumper, sort_keys=False, allow_unicode=True, width=100)
    raise ValueError(f"Unknown output format: {fmt}")


def format_for_path(path: str | Path | None, default: str = "yaml") -> str:
    """Pick ``json`` or ``yaml`` from a file extension."""
    if path is None:
        return default
    return "json" if str(path).lower().endswith(".json") else "yaml"


def line_index(text: str) -> dict[tuple[str, ...], int]:
    """Map JSON-pointer-like paths to 1-based line numbers.

    Used to give lint findings real line numbers (text output and SARIF). Works for YAML and
    JSON (JSON is parsed by the YAML composer too). Returns an empty map on any failure:
    line numbers are a convenience, never a reason to fail.
    """
    index: dict[tuple[str, ...], int] = {}
    try:
        root = yaml.compose(text, Loader=_StrictSafeLoader)
    except Exception:  # pragma: no cover - best effort only
        return index
    if root is None:
        return index

    stack: list[tuple[yaml.Node, tuple[str, ...]]] = [(root, ())]
    while stack:
        node, path = stack.pop()
        index.setdefault(path, node.start_mark.line + 1)
        if isinstance(node, yaml.MappingNode):
            for key_node, value_node in node.value:
                key = str(key_node.value)
                child = (*path, key)
                index[child] = key_node.start_mark.line + 1
                stack.append((value_node, child))
        elif isinstance(node, yaml.SequenceNode):
            for i, item in enumerate(node.value):
                stack.append((item, (*path, str(i))))
    return index
