"""Upgrade Swagger 2.0 / OpenAPI 3.0 schema objects to OpenAPI 3.1 (JSON Schema 2020-12)."""

from __future__ import annotations

from typing import Any

_SUBSCHEMA_LISTS = ("allOf", "anyOf", "oneOf", "prefixItems")
_SUBSCHEMA_SINGLE = ("items", "not", "additionalProperties", "if", "then", "else", "contains")
_SUBSCHEMA_MAPS = ("properties", "patternProperties", "$defs")

MAX_DEPTH = 64


def rewrite_refs(node: Any, mapping: dict[str, str]) -> Any:
    """Return a deep copy of ``node`` with ``$ref`` prefixes rewritten via ``mapping``."""
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                for old, new in mapping.items():
                    if value.startswith(old):
                        value = new + value[len(old) :]
                        break
                out[key] = value
            else:
                out[key] = rewrite_refs(value, mapping)
        return out
    if isinstance(node, list):
        return [rewrite_refs(item, mapping) for item in node]
    return node


def _add_null(schema: dict[str, Any]) -> dict[str, Any]:
    """Make a schema accept ``null`` the 3.1 way."""
    if "$ref" in schema or "type" not in schema:
        return {"anyOf": [schema, {"type": "null"}]}
    current = schema["type"]
    types = list(current) if isinstance(current, list) else [current]
    if "null" not in types:
        types.append("null")
    schema["type"] = types
    if "enum" in schema and None not in schema["enum"]:
        schema["enum"] = [*schema["enum"], None]
    return schema


def upgrade_schema(schema: Any, depth: int = 0) -> Any:
    """Convert one schema (recursively) to 3.1 semantics.

    * ``nullable`` / ``x-nullable`` -> ``type: [T, "null"]``
    * boolean ``exclusiveMinimum``/``exclusiveMaximum`` -> numeric form
    * ``type: file`` -> ``type: string, format: binary``
    * ``example`` -> ``examples: [...]``
    * string ``discriminator`` (Swagger 2) -> ``{propertyName: ...}``
    """
    if depth > MAX_DEPTH or not isinstance(schema, dict):
        return schema
    s = dict(schema)

    for key in _SUBSCHEMA_LISTS:
        if isinstance(s.get(key), list):
            s[key] = [upgrade_schema(x, depth + 1) for x in s[key]]
    for key in _SUBSCHEMA_SINGLE:
        if isinstance(s.get(key), dict):
            s[key] = upgrade_schema(s[key], depth + 1)
    for key in _SUBSCHEMA_MAPS:
        if isinstance(s.get(key), dict):
            s[key] = {k: upgrade_schema(v, depth + 1) for k, v in s[key].items()}

    if s.get("type") == "file":
        s["type"] = "string"
        s["format"] = "binary"

    for bound, excl in (("minimum", "exclusiveMinimum"), ("maximum", "exclusiveMaximum")):
        if isinstance(s.get(excl), bool):
            if s[excl] and bound in s:
                s[excl] = s.pop(bound)
            else:
                del s[excl]

    if isinstance(s.get("discriminator"), str):
        s["discriminator"] = {"propertyName": s["discriminator"]}

    if "example" in s:
        s["examples"] = [s.pop("example")]

    nullable = bool(s.pop("nullable", False)) | bool(s.pop("x-nullable", False))
    if nullable:
        s = _add_null(s)
    return s
