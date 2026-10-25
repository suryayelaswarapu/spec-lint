"""Safe auto-fixes. Only changes that do not alter the wire contract are applied:
operationIds, tags, component names, schema extraction and documented error bodies.
Paths, parameter names and property names are never renamed.
"""

from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any

from speclint.convert.openapi30 import upgrade_openapi30
from speclint.lint.config import LintConfig
from speclint.lint.model import pointer
from speclint.lint.rules import _is_error_code, no_legacy_nullable
from speclint.lint.walk import as_dict, as_list, iter_operations
from speclint.naming import CAMEL_CASE, PASCAL_CASE, to_camel, to_kebab, to_pascal, unique

PROBLEM_SCHEMA: dict[str, Any] = {
    "type": "object",
    "description": "Error body following RFC 9457 (Problem Details for HTTP APIs).",
    "properties": {
        "type": {"type": "string", "format": "uri-reference"},
        "title": {"type": "string"},
        "status": {"type": "integer", "minimum": 100, "maximum": 599},
        "detail": {"type": "string"},
        "instance": {"type": "string", "format": "uri-reference"},
    },
}
PROBLEM_MEDIA = "application/problem+json"

TOP_LEVEL_ORDER = (
    "openapi",
    "info",
    "jsonSchemaDialect",
    "servers",
    "security",
    "tags",
    "externalDocs",
    "paths",
    "webhooks",
    "components",
)


@dataclass(frozen=True)
class AppliedFix:
    rule: str
    message: str
    path: str = ""


class _Fixer:
    def __init__(self, doc: dict[str, Any], config: LintConfig) -> None:
        self.doc = copy.deepcopy(doc)
        self.config = config
        self.applied: list[AppliedFix] = []

    def _on(self, rule_id: str) -> bool:
        return self.config.enabled(rule_id)

    def _log(self, rule_id: str, message: str, path: tuple[str, ...] = ()) -> None:
        self.applied.append(AppliedFix(rule_id, message, pointer(path)))

    def _components(self, section: str) -> dict[str, Any]:
        comps = self.doc.setdefault("components", {})
        return comps.setdefault(section, {})  # type: ignore[no-any-return]

    # ---------------------------------------------------------------- fixes
    def legacy_nullable(self) -> None:
        if self._on("no-legacy-nullable") and any(True for _ in no_legacy_nullable(self.doc)):
            version = self.doc.get("openapi")
            self.doc = upgrade_openapi30(self.doc).document
            self.doc["openapi"] = version
            self._log("no-legacy-nullable", "Rewrote nullable/x-nullable as type: [T, 'null']")

    def operation_ids(self) -> None:
        ops = list(iter_operations(self.doc))
        taken = {str(op["operationId"]) for _, _, op, _ in ops if op.get("operationId")}
        if self._on("operation-id-required"):
            for path, method, op, _ in ops:
                if not op.get("operationId"):
                    parts = [method]
                    for seg in path.split("/"):
                        if seg.startswith("{"):
                            parts += ["by", seg.strip("{}")]
                        elif seg:
                            parts.append(seg)
                    new = unique(to_camel("-".join(parts)), taken)
                    taken.add(new)
                    op["operationId"] = new
                    self._log(
                        "operation-id-required",
                        f"Added operationId '{new}' to {method.upper()} {path}",
                        ("paths", path, method),
                    )
        if self._on("operation-id-camel-case"):
            for path, method, op, _ in ops:
                old = str(op.get("operationId") or "")
                if old and not CAMEL_CASE.match(old):
                    taken.discard(old)
                    new = unique(to_camel(old), taken)
                    taken.add(new)
                    op["operationId"] = new
                    self._log(
                        "operation-id-camel-case",
                        f"Renamed operationId '{old}' -> '{new}'",
                        ("paths", path, method, "operationId"),
                    )

    def tags(self) -> None:
        if self._on("operation-tags"):
            for path, method, op, _ in iter_operations(self.doc):
                if not op.get("tags"):
                    segs = [s for s in path.split("/") if s and not s.startswith("{")]
                    segs = [s for s in segs if not (s[:1] == "v" and s[1:].isdigit())]
                    tag = to_kebab(segs[0]) if segs else "default"
                    op["tags"] = [tag]
                    self._log(
                        "operation-tags",
                        f"Tagged {method.upper()} {path} as '{tag}'",
                        ("paths", path, method),
                    )
        if self._on("operation-tag-defined"):
            declared = self.doc.setdefault("tags", [])
            names = {t.get("name") for t in declared if isinstance(t, dict)}
            for _, _, op, _ in iter_operations(self.doc):
                for tag in as_list(op.get("tags")):
                    if tag not in names:
                        names.add(tag)
                        declared.append({"name": tag})
                        self._log("operation-tag-defined", f"Declared tag '{tag}'", ("tags",))
            if not declared:
                del self.doc["tags"]

    def schema_names(self) -> None:
        if not self._on("schema-name-pascal-case"):
            return
        schemas = (self.doc.get("components") or {}).get("schemas") or {}
        renames: dict[str, str] = {}
        taken = set(schemas)
        for name in list(schemas):
            if not PASCAL_CASE.match(name):
                taken.discard(name)
                new = unique(to_pascal(name), taken)
                taken.add(new)
                renames[name] = new
        if not renames:
            return
        self.doc["components"]["schemas"] = {renames.get(k, k): v for k, v in schemas.items()}
        prefix = "#/components/schemas/"

        def rewrite(node: Any) -> None:
            if isinstance(node, dict):
                ref = node.get("$ref")
                if isinstance(ref, str) and ref.startswith(prefix):
                    head, sep, tail = ref[len(prefix) :].partition("/")
                    if head in renames:
                        node["$ref"] = prefix + renames[head] + sep + tail
                for value in node.values():
                    rewrite(value)
            elif isinstance(node, list):
                for value in node:
                    rewrite(value)

        rewrite(self.doc)
        for old, new in renames.items():
            self._log(
                "schema-name-pascal-case",
                f"Renamed schema '{old}' -> '{new}'",
                ("components", "schemas", new),
            )

    def inline_schemas(self) -> None:
        if not self._on("no-inline-object-schemas"):
            return
        for path, method, op, _ in iter_operations(self.doc):
            stem = to_pascal(str(op.get("operationId") or f"{method}-{path}"))
            bodies: list[tuple[str, dict[str, Any]]] = []
            if isinstance(op.get("requestBody"), dict):
                bodies.append(("Request", op["requestBody"]))
            for code, resp in as_dict(op.get("responses")).items():
                if isinstance(resp, dict):
                    suffix = "Response" if str(code) in ("200", "201") else f"{code}Response"
                    bodies.append((suffix, resp))
            for suffix, holder in bodies:
                extracted: dict[str, str] = {}
                for media, obj in (holder.get("content") or {}).items():
                    schema = obj.get("schema") if isinstance(obj, dict) else None
                    if not isinstance(schema, dict) or "$ref" in schema:
                        continue
                    if schema.get("type") != "object" and "properties" not in schema:
                        continue
                    key = repr(schema)
                    if key not in extracted:
                        schemas = self._components("schemas")
                        name = unique(f"{stem}{suffix}", schemas)
                        schemas[name] = schema
                        extracted[key] = name
                        self._log(
                            "no-inline-object-schemas",
                            f"Extracted {method.upper()} {path} {media} schema to '{name}'",
                            ("components", "schemas", name),
                        )
                    obj["schema"] = {"$ref": f"#/components/schemas/{extracted[key]}"}

    def _ensure_problem(self) -> None:
        schemas = self._components("schemas")
        schemas.setdefault("Problem", copy.deepcopy(PROBLEM_SCHEMA))
        responses = self._components("responses")
        responses.setdefault(
            "Problem",
            {
                "description": "Error (RFC 9457 Problem Details)",
                "content": {PROBLEM_MEDIA: {"schema": {"$ref": "#/components/schemas/Problem"}}},
            },
        )

    def error_responses(self) -> None:
        if self._on("error-response-schema"):
            for path, method, op, _ in iter_operations(self.doc):
                for code, resp in as_dict(op.get("responses")).items():
                    if not _is_error_code(str(code)) or not isinstance(resp, dict):
                        continue
                    content = as_dict(resp.get("content"))
                    if "$ref" in resp or any(
                        isinstance(m, dict) and "schema" in m for m in content.values()
                    ):
                        continue
                    self._ensure_problem()
                    content[PROBLEM_MEDIA] = {"schema": {"$ref": "#/components/schemas/Problem"}}
                    resp["content"] = content
                    self._log(
                        "error-response-schema",
                        f"Added Problem body to {method.upper()} {path} response {code}",
                        ("paths", path, method, "responses", str(code)),
                    )
        if self._on("operation-error-response"):
            for path, method, op, _ in iter_operations(self.doc):
                responses = op.setdefault("responses", {})
                if not any(_is_error_code(str(c)) for c in responses):
                    self._ensure_problem()
                    responses["default"] = {"$ref": "#/components/responses/Problem"}
                    self._log(
                        "operation-error-response",
                        f"Added default Problem response to {method.upper()} {path}",
                        ("paths", path, method, "responses", "default"),
                    )

    def order_keys(self) -> None:
        ordered = {k: self.doc[k] for k in TOP_LEVEL_ORDER if k in self.doc}
        ordered.update({k: v for k, v in self.doc.items() if k not in ordered})
        self.doc = ordered

    def run(self) -> tuple[dict[str, Any], list[AppliedFix]]:
        self.legacy_nullable()
        self.operation_ids()
        self.tags()
        self.schema_names()
        self.inline_schemas()
        self.error_responses()
        self.order_keys()
        return self.doc, self.applied


def apply_fixes(
    doc: dict[str, Any], config: LintConfig | None = None
) -> tuple[dict[str, Any], list[AppliedFix]]:
    """Return a fixed copy of ``doc`` and the list of fixes applied."""
    return _Fixer(doc, config or LintConfig()).run()
