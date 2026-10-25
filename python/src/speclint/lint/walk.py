"""Helpers for walking an OpenAPI 3.x document."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from speclint.lint.model import Path

HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
COMPONENT_SECTIONS = ("schemas", "parameters", "responses", "requestBodies", "headers", "examples")
MAX_DEPTH = 64


def as_dict(value: Any) -> dict[str, Any]:
    """``value`` if it is a mapping, else an empty dict (tolerates malformed specs)."""
    return value if isinstance(value, dict) else {}


def as_list(value: Any) -> list[Any]:
    """``value`` if it is a list, else an empty list (tolerates malformed specs)."""
    return value if isinstance(value, list) else []


def iter_operations(
    doc: dict[str, Any],
) -> Iterator[tuple[str, str, dict[str, Any], dict[str, Any]]]:
    """Yield ``(path, method, operation, path_item)``."""
    paths = doc.get("paths")
    if not isinstance(paths, dict):
        return
    for path, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method in HTTP_METHODS:
            op = item.get(method)
            if isinstance(op, dict):
                yield str(path), method, op, item


def iter_refs(node: Any, path: Path = ()) -> Iterator[tuple[str, Path]]:
    """Yield every ``$ref`` string with its location."""
    stack: list[tuple[Any, Path]] = [(node, path)]
    while stack:
        current, where = stack.pop()
        if isinstance(current, dict):
            for key, value in current.items():
                if key == "$ref" and isinstance(value, str):
                    yield value, (*where, "$ref")
                else:
                    stack.append((value, (*where, str(key))))
        elif isinstance(current, list):
            for i, value in enumerate(current):
                stack.append((value, (*where, str(i))))


def resolve_pointer(doc: Any, ref: str) -> Any | None:
    """Resolve a local ``#/a/b`` reference; ``None`` if it does not exist."""
    if not ref.startswith("#"):
        return None
    node = doc
    for raw in ref[1:].split("/")[1:]:
        key = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(node, dict) and key in node:
            node = node[key]
        elif isinstance(node, list) and key.isdigit() and int(key) < len(node):
            node = node[int(key)]
        else:
            return None
    return node


def deref(doc: dict[str, Any], node: Any) -> Any:
    """Follow ``$ref`` chains (max 10 hops) for parameter/response objects."""
    for _ in range(10):
        if isinstance(node, dict) and isinstance(node.get("$ref"), str):
            node = resolve_pointer(doc, node["$ref"])
        else:
            break
    return node


def iter_media_schemas(
    doc: dict[str, Any],
) -> Iterator[tuple[str, dict[str, Any], Path, str]]:
    """Yield ``(kind, schema, path, label)`` for request/response body schemas of operations.

    ``kind`` is ``"request"`` or ``"response:<code>"``.
    """
    for path, method, op, _ in iter_operations(doc):
        base: Path = ("paths", path, method)
        body = op.get("requestBody")
        if isinstance(body, dict) and isinstance(body.get("content"), dict):
            for media, obj in body["content"].items():
                if isinstance(obj, dict) and isinstance(obj.get("schema"), dict):
                    yield (
                        "request",
                        obj["schema"],
                        (*base, "requestBody", "content", media, "schema"),
                        f"{method.upper()} {path}",
                    )
        responses = op.get("responses")
        if not isinstance(responses, dict):
            continue
        for code, resp in responses.items():
            if not isinstance(resp, dict) or not isinstance(resp.get("content"), dict):
                continue
            for media, obj in resp["content"].items():
                if isinstance(obj, dict) and isinstance(obj.get("schema"), dict):
                    yield (
                        f"response:{code}",
                        obj["schema"],
                        (*base, "responses", str(code), "content", media, "schema"),
                        f"{method.upper()} {path}",
                    )


def iter_schema_properties(schema: Any, path: Path, depth: int = 0) -> Iterator[tuple[str, Path]]:
    """Yield ``(property_name, path)`` for a schema and its inline sub-schemas."""
    if depth > MAX_DEPTH or not isinstance(schema, dict):
        return
    props = schema.get("properties")
    if isinstance(props, dict):
        for name, sub in props.items():
            yield str(name), (*path, "properties", str(name))
            yield from iter_schema_properties(sub, (*path, "properties", str(name)), depth + 1)
    for key in ("items", "additionalProperties", "not"):
        if isinstance(schema.get(key), dict):
            yield from iter_schema_properties(schema[key], (*path, key), depth + 1)
    for key in ("allOf", "anyOf", "oneOf"):
        subs = schema.get(key)
        if isinstance(subs, list):
            for i, sub in enumerate(subs):
                yield from iter_schema_properties(sub, (*path, key, str(i)), depth + 1)
