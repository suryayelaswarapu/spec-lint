"""The built-in ruleset. The catalogue (ids, severities, intent) lives in ``docs/rules.md``
and is shared by the Python, Java and Go implementations."""

from __future__ import annotations

import re
from collections.abc import Iterator
from typing import Any

from speclint.lint.model import Path, Severity, rule
from speclint.lint.walk import (
    COMPONENT_SECTIONS,
    as_dict,
    as_list,
    deref,
    iter_media_schemas,
    iter_operations,
    iter_refs,
    iter_schema_properties,
    resolve_pointer,
)
from speclint.naming import CAMEL_CASE, PASCAL_CASE

E, W, I = Severity.ERROR, Severity.WARNING, Severity.INFO  # noqa: E741

Result = Iterator[tuple[str, Path]]

SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")
PATH_SEGMENT = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*(?:\.[a-z0-9]+)?$")
PATH_TEMPLATE = re.compile(r"\{([^}/]+)\}")
LOCAL_HOSTS = ("localhost", "127.0.0.1", "[::1]")


def _is_error_code(code: str) -> bool:
    return code == "default" or code[:1] in ("4", "5")


# ------------------------------------------------------------------ references
@rule("no-remote-refs", E, "$ref must point inside the document; remote refs are never fetched.")
def no_remote_refs(doc: dict[str, Any]) -> Result:
    for ref, path in iter_refs(doc):
        if not ref.startswith("#"):
            kind = "remote URL" if "://" in ref else "external file"
            yield f"$ref to {kind} '{ref}' is not allowed; bundle it into this document", path


@rule("ref-resolves", E, "Every local $ref must resolve to an existing node.")
def ref_resolves(doc: dict[str, Any]) -> Result:
    for ref, path in iter_refs(doc):
        if ref.startswith("#") and resolve_pointer(doc, ref) is None:
            yield f"$ref '{ref}' does not resolve", path


@rule("oas3-valid-schema", E, "Document must be valid against the OpenAPI 3.x specification.")
def oas3_valid_schema(doc: dict[str, Any]) -> Result:
    # Only validate documents whose references are all local and resolvable.
    for ref, _ in iter_refs(doc):
        if not ref.startswith("#") or resolve_pointer(doc, ref) is None:
            return
    from speclint.lint.validator import validate

    try:
        yield from validate(doc)
    except Exception as exc:  # validator internals can raise on malformed input
        yield f"Invalid OpenAPI: {exc.__class__.__name__}: {exc}", ()


# ------------------------------------------------------------------ info/servers
@rule("openapi-version-3-1", W, "Target OpenAPI 3.1 (full JSON Schema 2020-12 support).")
def openapi_version(doc: dict[str, Any]) -> Result:
    if not str(doc.get("openapi", "")).startswith("3.1"):
        yield f"openapi is {doc.get('openapi')!r}; upgrade to 3.1.x", ("openapi",)


@rule("info-description", W, "info.description explains what the API is for.")
def info_description(doc: dict[str, Any]) -> Result:
    if not (doc.get("info") or {}).get("description"):
        yield "info.description is missing", ("info",)


@rule("info-contact", W, "info.contact tells consumers who owns the API.")
def info_contact(doc: dict[str, Any]) -> Result:
    if not (doc.get("info") or {}).get("contact"):
        yield "info.contact is missing", ("info",)


@rule("info-license", I, "info.license states the terms of use.")
def info_license(doc: dict[str, Any]) -> Result:
    if not (doc.get("info") or {}).get("license"):
        yield "info.license is missing", ("info",)


@rule("info-version-semver", W, "info.version should be semantic (MAJOR.MINOR.PATCH).")
def info_version(doc: dict[str, Any]) -> Result:
    version = str((doc.get("info") or {}).get("version", ""))
    if not SEMVER.match(version):
        yield f"info.version {version!r} is not semantic versioning", ("info", "version")


@rule("servers-defined", E, "At least one server URL must be declared.")
def servers_defined(doc: dict[str, Any]) -> Result:
    servers = doc.get("servers")
    if not isinstance(servers, list) or not servers:
        yield "No servers defined", ()


@rule("servers-https", W, "Absolute server URLs must use https (localhost excepted).")
def servers_https(doc: dict[str, Any]) -> Result:
    for i, server in enumerate(doc.get("servers") or []):
        url = str((server or {}).get("url", ""))
        if url.startswith("http://") and not url[7:].startswith(LOCAL_HOSTS):
            yield f"Server URL '{url}' is not https", ("servers", str(i), "url")


@rule("security-defined", W, "Operations should be protected by a declared security scheme.")
def security_defined(doc: dict[str, Any]) -> Result:
    schemes = (doc.get("components") or {}).get("securitySchemes")
    if not schemes:
        yield "No components.securitySchemes defined", ()
        return
    if doc.get("security"):
        return
    for path, method, op, _ in iter_operations(doc):
        if "security" not in op:
            yield f"{method.upper()} {path} has no security requirement", ("paths", path, method)


# ------------------------------------------------------------------ operations
@rule("operation-id-required", E, "Every operation needs an operationId.", fixable=True)
def operation_id_required(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        if not op.get("operationId"):
            yield f"{method.upper()} {path} has no operationId", ("paths", path, method)


@rule("operation-id-unique", E, "operationId values must be unique.")
def operation_id_unique(doc: dict[str, Any]) -> Result:
    seen: set[str] = set()
    for path, method, op, _ in iter_operations(doc):
        op_id = op.get("operationId")
        if op_id and op_id in seen:
            yield f"Duplicate operationId '{op_id}'", ("paths", path, method, "operationId")
        if op_id:
            seen.add(op_id)


@rule("operation-id-camel-case", W, "operationId should be camelCase.", fixable=True)
def operation_id_camel(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        op_id = op.get("operationId")
        if op_id and not CAMEL_CASE.match(str(op_id)):
            yield f"operationId '{op_id}' is not camelCase", ("paths", path, method, "operationId")


@rule("operation-summary", W, "Every operation needs a short summary.")
def operation_summary(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        if not op.get("summary"):
            yield f"{method.upper()} {path} has no summary", ("paths", path, method)


@rule("operation-tags", W, "Every operation needs at least one tag.", fixable=True)
def operation_tags(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        if not op.get("tags"):
            yield f"{method.upper()} {path} has no tags", ("paths", path, method)


@rule(
    "operation-tag-defined",
    W,
    "Tags used by operations are declared at the top level.",
    fixable=True,
)
def operation_tag_defined(doc: dict[str, Any]) -> Result:
    declared = {t.get("name") for t in doc.get("tags") or [] if isinstance(t, dict)}
    for path, method, op, _ in iter_operations(doc):
        for i, tag in enumerate(as_list(op.get("tags"))):
            if tag not in declared:
                yield (
                    f"Tag '{tag}' is not declared in top-level tags",
                    (
                        "paths",
                        path,
                        method,
                        "tags",
                        str(i),
                    ),
                )


@rule("operation-success-response", E, "Every operation defines a 2xx or 3xx response.")
def operation_success(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        codes = [str(c) for c in as_dict(op.get("responses"))]
        if not any(c[:1] in ("2", "3") for c in codes):
            yield (
                f"{method.upper()} {path} has no success response",
                (
                    "paths",
                    path,
                    method,
                    "responses",
                ),
            )


@rule(
    "operation-error-response",
    W,
    "Every operation documents its errors (4xx/5xx/default).",
    fixable=True,
)
def operation_error(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        codes = [str(c) for c in as_dict(op.get("responses"))]
        if not any(_is_error_code(c) for c in codes):
            yield (
                f"{method.upper()} {path} has no error response",
                (
                    "paths",
                    path,
                    method,
                    "responses",
                ),
            )


@rule(
    "error-response-schema",
    W,
    "Error responses must describe their body with a schema.",
    fixable=True,
)
def error_response_schema(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        for code, resp in as_dict(op.get("responses")).items():
            if not _is_error_code(str(code)):
                continue
            resolved = deref(doc, resp)
            content = resolved.get("content") if isinstance(resolved, dict) else None
            if not isinstance(content, dict) or not any(
                isinstance(m, dict) and "schema" in m for m in content.values()
            ):
                yield (
                    f"{method.upper()} {path} response {code} has no schema",
                    (
                        "paths",
                        path,
                        method,
                        "responses",
                        str(code),
                    ),
                )


@rule("parameter-description", I, "Parameters should be described.")
def parameter_description(doc: dict[str, Any]) -> Result:
    for path, method, op, _ in iter_operations(doc):
        for i, p in enumerate(as_list(op.get("parameters"))):
            if isinstance(p, dict) and "$ref" not in p and not p.get("description"):
                yield (
                    f"Parameter '{p.get('name')}' has no description",
                    (
                        "paths",
                        path,
                        method,
                        "parameters",
                        str(i),
                    ),
                )
    for name, p in ((doc.get("components") or {}).get("parameters") or {}).items():
        if isinstance(p, dict) and not p.get("description"):
            yield (
                f"Parameter '{p.get('name')}' has no description",
                (
                    "components",
                    "parameters",
                    str(name),
                ),
            )


# ------------------------------------------------------------------ paths
@rule("path-kebab-case", W, "Static path segments are lower kebab-case.")
def path_kebab_case(doc: dict[str, Any]) -> Result:
    for path in doc.get("paths") or {}:
        for segment in str(path).split("/"):
            if segment and not segment.startswith("{") and not PATH_SEGMENT.match(segment):
                yield f"Path segment '{segment}' in '{path}' is not kebab-case", ("paths", path)
                break


@rule("path-no-trailing-slash", W, "Paths must not end with a slash.")
def path_trailing_slash(doc: dict[str, Any]) -> Result:
    for path in doc.get("paths") or {}:
        if len(str(path)) > 1 and str(path).endswith("/"):
            yield f"Path '{path}' ends with a slash", ("paths", path)


@rule("path-params-defined", E, "Path templates and path parameters must match.")
def path_params_defined(doc: dict[str, Any]) -> Result:
    for path, method, op, item in iter_operations(doc):
        template = set(PATH_TEMPLATE.findall(path))
        declared: set[str] = set()
        for p in [*as_list(item.get("parameters")), *as_list(op.get("parameters"))]:
            p = deref(doc, p)
            if isinstance(p, dict) and p.get("in") == "path":
                declared.add(str(p.get("name")))
        where: Path = ("paths", path, method)
        for name in sorted(template - declared):
            yield f"{method.upper()} {path}: path parameter '{name}' is not declared", where
        for name in sorted(declared - template):
            yield f"{method.upper()} {path}: parameter '{name}' is not in the path", where


# ------------------------------------------------------------------ schemas
@rule("schema-name-pascal-case", W, "components.schemas names are PascalCase.", fixable=True)
def schema_name_pascal(doc: dict[str, Any]) -> Result:
    for name in (doc.get("components") or {}).get("schemas") or {}:
        if not PASCAL_CASE.match(str(name)):
            yield f"Schema name '{name}' is not PascalCase", ("components", "schemas", str(name))


@rule("property-camel-case", W, "Schema property names are camelCase.")
def property_camel_case(doc: dict[str, Any]) -> Result:
    seen: set[Path] = set()
    roots: list[tuple[Any, Path]] = [
        (schema, ("components", "schemas", str(name)))
        for name, schema in ((doc.get("components") or {}).get("schemas") or {}).items()
    ]
    roots += [(schema, path) for _, schema, path, _ in iter_media_schemas(doc)]
    for schema, root in roots:
        for name, path in iter_schema_properties(schema, root):
            if path not in seen and not CAMEL_CASE.match(name):
                seen.add(path)
                yield f"Property '{name}' is not camelCase", path


@rule(
    "no-inline-object-schemas",
    W,
    "Request/response bodies reference named schemas instead of inline objects.",
    fixable=True,
)
def no_inline_objects(doc: dict[str, Any]) -> Result:
    for kind, schema, path, label in iter_media_schemas(doc):
        if "$ref" not in schema and (schema.get("type") == "object" or "properties" in schema):
            where = "request body" if kind == "request" else f"response {kind.split(':', 1)[1]}"
            yield f"{label} {where} uses an inline object schema", path


@rule(
    "no-legacy-nullable", W, "Use type: [T, 'null'] instead of nullable / x-nullable.", fixable=True
)
def no_legacy_nullable(doc: dict[str, Any]) -> Result:
    stack: list[tuple[Any, Path]] = [(doc, ())]
    while stack:
        node, path = stack.pop()
        if isinstance(node, dict):
            for key in ("nullable", "x-nullable"):
                if isinstance(node.get(key), bool):
                    yield f"'{key}' is legacy syntax; use type: [T, 'null']", (*path, key)
            stack.extend((v, (*path, str(k))) for k, v in node.items() if k != "example")
        elif isinstance(node, list):
            stack.extend((v, (*path, str(i))) for i, v in enumerate(node))


@rule("no-unused-components", W, "Every component is referenced somewhere.")
def no_unused_components(doc: dict[str, Any]) -> Result:
    # One pass over the refs (O(refs + components)); a nested scan is quadratic and was a
    # denial-of-service risk for the web service on large documents.
    used: set[tuple[str, str]] = set()
    prefix = "#/components/"
    for ref, _ in iter_refs(doc):
        if ref.startswith(prefix):
            parts = ref[len(prefix) :].split("/")
            if len(parts) >= 2:
                used.add((parts[0], parts[1]))
    comps = as_dict(doc.get("components"))
    for section in COMPONENT_SECTIONS:
        for name in as_dict(comps.get(section)):
            escaped = str(name).replace("~", "~0").replace("/", "~1")
            if (section, escaped) not in used:
                yield (
                    f"components.{section}.{name} is never referenced",
                    ("components", section, str(name)),
                )
