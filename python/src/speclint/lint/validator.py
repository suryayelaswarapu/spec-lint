"""Structural validation (``oas3-valid-schema``) against the official OpenAPI JSON Schemas.

The schemas are vendored (``/schemas/openapi``, copied here by ``./gradlew syncWebAssets``) and
registered in an in-memory ``referencing`` registry, so validation never touches the network.
The Java and Go implementations validate against the same files.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from functools import cache
from importlib import resources
from typing import Any

from jsonschema import Draft4Validator, Draft202012Validator
from jsonschema.protocols import Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT4, DRAFT202012

MAX_ERRORS = 20


@cache
def _validator(version: str) -> Validator:
    name = "oas-3.0.json" if version == "3.0" else "oas-3.1.json"
    schema = json.loads((resources.files("speclint.lint") / "schemas" / name).read_text("utf-8"))
    spec = DRAFT4 if version == "3.0" else DRAFT202012
    # Crawl once up front: otherwise every $dynamicRef in the 3.1 schema re-crawls the whole
    # registry, which made validation of large documents take minutes.
    registry: Registry[Any] = (
        Registry()
        .with_resource(
            schema.get("$id") or schema["id"],
            Resource.from_contents(schema, default_specification=spec),
        )
        .crawl()
    )
    cls = Draft4Validator if version == "3.0" else Draft202012Validator
    return cls(schema, registry=registry)


def validate(doc: dict[str, Any]) -> Iterator[tuple[str, tuple[str, ...]]]:
    """Yield ``(message, path)`` for up to 20 schema violations, sorted for stable output."""
    version = "3.0" if str(doc.get("openapi", "")).startswith("3.0") else "3.1"
    errors = sorted(_validator(version).iter_errors(doc), key=lambda e: e.message)
    for err in errors[:MAX_ERRORS]:
        yield f"Invalid OpenAPI: {err.message}", tuple(str(p) for p in err.absolute_path)
