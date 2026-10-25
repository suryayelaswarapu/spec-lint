"""OpenAPI 3.0 -> 3.1 upgrader (schema keyword changes only; structure is identical)."""

from __future__ import annotations

import copy
from typing import Any

from speclint.convert import ConversionResult
from speclint.convert.schema import upgrade_schema
from speclint.detect import SpecKind

HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


def _upgrade_content(content: Any) -> None:
    if isinstance(content, dict):
        for media in content.values():
            if isinstance(media, dict) and "schema" in media:
                media["schema"] = upgrade_schema(media["schema"])


def _upgrade_parameters(params: Any) -> None:
    if isinstance(params, list):
        for p in params:
            if isinstance(p, dict) and "schema" in p:
                p["schema"] = upgrade_schema(p["schema"])


def _upgrade_response(resp: Any) -> None:
    if isinstance(resp, dict):
        _upgrade_content(resp.get("content"))
        for header in (resp.get("headers") or {}).values():
            if isinstance(header, dict) and "schema" in header:
                header["schema"] = upgrade_schema(header["schema"])


def upgrade_openapi30(doc: dict[str, Any]) -> ConversionResult:
    out = copy.deepcopy(doc)
    out["openapi"] = "3.1.0"

    for item in (out.get("paths") or {}).values():
        if not isinstance(item, dict):
            continue
        _upgrade_parameters(item.get("parameters"))
        for method in HTTP_METHODS:
            op = item.get(method)
            if not isinstance(op, dict):
                continue
            _upgrade_parameters(op.get("parameters"))
            if isinstance(op.get("requestBody"), dict):
                _upgrade_content(op["requestBody"].get("content"))
            for resp in (op.get("responses") or {}).values():
                _upgrade_response(resp)

    comp = out.get("components") or {}
    if isinstance(comp.get("schemas"), dict):
        comp["schemas"] = {k: upgrade_schema(v) for k, v in comp["schemas"].items()}
    _upgrade_parameters(list((comp.get("parameters") or {}).values()))
    for body in (comp.get("requestBodies") or {}).values():
        if isinstance(body, dict):
            _upgrade_content(body.get("content"))
    for resp in (comp.get("responses") or {}).values():
        _upgrade_response(resp)

    return ConversionResult(document=out, source_kind=SpecKind.OPENAPI30)
