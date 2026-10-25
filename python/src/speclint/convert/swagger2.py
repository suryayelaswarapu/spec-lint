"""Swagger 2.0 -> OpenAPI 3.1 converter.

The mapping follows the official "Swagger 2.0 to OpenAPI 3.0" migration notes, then applies the
3.0 -> 3.1 schema changes (see :mod:`speclint.convert.schema`). Anything that has no lossless
equivalent is reported as a warning rather than silently dropped.
"""

from __future__ import annotations

import copy
from typing import Any, cast

from speclint.convert import ConversionResult
from speclint.convert.schema import rewrite_refs, upgrade_schema
from speclint.detect import SpecKind

HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch")
DEFAULT_MEDIA = "application/json"

REF_MAP = {
    "#/definitions/": "#/components/schemas/",
    "#/responses/": "#/components/responses/",
    "#/securityDefinitions/": "#/components/securitySchemes/",
    # '#/parameters/' is handled explicitly: body params become requestBodies.
}

# collectionFormat -> (style, explode) for query parameters
_QUERY_STYLES = {
    "csv": ("form", False),
    "ssv": ("spaceDelimited", False),
    "pipes": ("pipeDelimited", False),
    "multi": ("form", True),
}

_SCHEMA_KEYS = (
    "type",
    "format",
    "items",
    "enum",
    "default",
    "maximum",
    "exclusiveMaximum",
    "minimum",
    "exclusiveMinimum",
    "maxLength",
    "minLength",
    "pattern",
    "maxItems",
    "minItems",
    "uniqueItems",
    "multipleOf",
)


class _Swagger2Converter:
    def __init__(self, src: dict[str, Any]) -> None:
        self.src = copy.deepcopy(src)
        self.warnings: list[str] = []
        self.global_consumes: list[str] = self.src.get("consumes") or [DEFAULT_MEDIA]
        self.global_produces: list[str] = self.src.get("produces") or [DEFAULT_MEDIA]
        # Names of top-level parameters that are body/formData (-> components.requestBodies)
        self.body_params: set[str] = {
            name
            for name, p in (self.src.get("parameters") or {}).items()
            if isinstance(p, dict) and p.get("in") in ("body", "formData")
        }

    # ------------------------------------------------------------------ helpers
    def _warn(self, msg: str) -> None:
        self.warnings.append(msg)

    def _refs(self, node: Any) -> Any:
        return rewrite_refs(node, REF_MAP)

    def _schema(self, schema: Any) -> Any:
        return upgrade_schema(self._refs(schema))

    def _param_ref(self, ref: str) -> tuple[str, str]:
        """Return ('parameter'|'requestBody', new_ref) for a ``#/parameters/X`` ref."""
        name = ref.rsplit("/", 1)[-1]
        if name in self.body_params:
            return "requestBody", f"#/components/requestBodies/{name}"
        return "parameter", f"#/components/parameters/{name}"

    # ---------------------------------------------------------------- top level
    def convert(self) -> ConversionResult:
        s = self.src
        out: dict[str, Any] = {"openapi": "3.1.0", "info": copy.deepcopy(s.get("info") or {})}
        out["info"].setdefault("title", "Untitled API")
        out["info"].setdefault("version", "0.0.0")
        out["servers"] = self._servers()
        for key in ("security", "tags", "externalDocs"):
            if key in s:
                out[key] = copy.deepcopy(s[key])
        out["paths"] = self._paths()
        components = self._components()
        if components:
            out["components"] = components
        for key, value in s.items():
            if key.startswith("x-"):
                out[key] = copy.deepcopy(value)
        return ConversionResult(document=out, source_kind=SpecKind.SWAGGER2, warnings=self.warnings)

    def _servers(self) -> list[dict[str, Any]]:
        host = self.src.get("host")
        base = self.src.get("basePath") or "/"
        if not host:
            self._warn(f"No 'host' in Swagger 2.0 spec; server URL is relative ('{base}').")
            return [{"url": base}]
        schemes = self.src.get("schemes") or ["https"]
        if "schemes" not in self.src:
            self._warn("No 'schemes' declared; assumed https.")
        return [{"url": f"{scheme}://{host}{base}".rstrip("/") or "/"} for scheme in schemes]

    def _components(self) -> dict[str, Any]:
        s = self.src
        comp: dict[str, Any] = {}
        if s.get("definitions"):
            comp["schemas"] = {k: self._schema(v) for k, v in s["definitions"].items()}
        params: dict[str, Any] = {}
        bodies: dict[str, Any] = {}
        for name, p in (s.get("parameters") or {}).items():
            if name in self.body_params:
                bodies[name] = self._request_body([p], self.global_consumes)
            else:
                params[name] = self._parameter(p)
        if params:
            comp["parameters"] = params
        if bodies:
            comp["requestBodies"] = bodies
        if s.get("responses"):
            comp["responses"] = {
                k: self._response(v, self.global_produces) for k, v in s["responses"].items()
            }
        if s.get("securityDefinitions"):
            comp["securitySchemes"] = {
                k: self._security_scheme(k, v) for k, v in s["securityDefinitions"].items()
            }
        return comp

    def _security_scheme(self, name: str, d: dict[str, Any]) -> dict[str, Any]:
        kind = d.get("type")
        out: dict[str, Any] = {}
        if kind == "basic":
            out = {"type": "http", "scheme": "basic"}
        elif kind == "apiKey":
            out = {"type": "apiKey", "name": d.get("name"), "in": d.get("in")}
        elif kind == "oauth2":
            flow_name = {
                "implicit": "implicit",
                "password": "password",
                "application": "clientCredentials",
                "accessCode": "authorizationCode",
            }.get(d.get("flow", ""), "")
            if not flow_name:
                self._warn(f"securityDefinitions.{name}: unknown oauth2 flow {d.get('flow')!r}")
                flow_name = "implicit"
            flow: dict[str, Any] = {"scopes": d.get("scopes") or {}}
            if "authorizationUrl" in d:
                flow["authorizationUrl"] = d["authorizationUrl"]
            if "tokenUrl" in d:
                flow["tokenUrl"] = d["tokenUrl"]
            out = {"type": "oauth2", "flows": {flow_name: flow}}
        else:
            self._warn(f"securityDefinitions.{name}: unknown type {kind!r}; copied as-is")
            out = dict(d)
        if "description" in d:
            out["description"] = d["description"]
        return out

    # -------------------------------------------------------------------- paths
    def _paths(self) -> dict[str, Any]:
        paths: dict[str, Any] = {}
        for path, item in (self.src.get("paths") or {}).items():
            if not isinstance(item, dict):
                continue
            new_item: dict[str, Any] = {}
            if "$ref" in item:
                self._warn(f"paths.{path}: external path item $ref kept as-is")
                new_item["$ref"] = item["$ref"]
            path_params = item.get("parameters") or []
            plain, _ = self._split_params(path_params, f"paths.{path}")
            if plain:
                new_item["parameters"] = plain
            for method in HTTP_METHODS:
                if method in item:
                    new_item[method] = self._operation(item[method], path_params, path, method)
            for key, value in item.items():
                if key.startswith("x-"):
                    new_item[key] = copy.deepcopy(value)
            paths[path] = new_item
        return paths

    def _split_params(
        self, params: list[Any], where: str
    ) -> tuple[list[Any], list[dict[str, Any]]]:
        """Split into (non-body parameters converted, body/formData parameter dicts)."""
        plain: list[Any] = []
        body: list[dict[str, Any]] = []
        for p in params:
            if not isinstance(p, dict):
                continue
            if "$ref" in p:
                kind, ref = self._param_ref(p["$ref"])
                if kind == "parameter":
                    plain.append({"$ref": ref})
                else:
                    body.append({"$ref": ref})
            elif p.get("in") in ("body", "formData"):
                body.append(p)
            else:
                plain.append(self._parameter(p))
        return plain, body

    def _operation(
        self, op: dict[str, Any], path_params: list[Any], path: str, method: str
    ) -> dict[str, Any]:
        where = f"{method.upper()} {path}"
        out: dict[str, Any] = {}
        for key in (
            "tags",
            "summary",
            "description",
            "externalDocs",
            "operationId",
            "deprecated",
            "security",
        ):
            if key in op:
                out[key] = copy.deepcopy(op[key])
        consumes = op.get("consumes") or self.global_consumes
        produces = op.get("produces") or self.global_produces

        plain, body = self._split_params(op.get("parameters") or [], where)
        if plain:
            out["parameters"] = plain
        # Path-level body params apply to every operation that does not override them.
        if not body:
            _, body = self._split_params(path_params, where)
        if body:
            refs = [b for b in body if "$ref" in b]
            if refs:
                out["requestBody"] = refs[0]
                if len(body) > 1:
                    self._warn(f"{where}: multiple body parameters; kept {refs[0]['$ref']}")
            else:
                out["requestBody"] = self._request_body(body, consumes)

        responses = op.get("responses") or {}
        out["responses"] = {
            str(code): self._response(resp, produces)
            for code, resp in responses.items()
            if isinstance(resp, dict)
        }
        if "schemes" in op:
            self._warn(f"{where}: per-operation 'schemes' dropped (use servers instead)")
        for key, value in op.items():
            if key.startswith("x-"):
                out[key] = copy.deepcopy(value)
        return out

    # -------------------------------------------------------- params / bodies
    def _param_schema(self, p: dict[str, Any]) -> dict[str, Any]:
        schema = {k: copy.deepcopy(p[k]) for k in _SCHEMA_KEYS if k in p}
        if "items" in schema:
            schema["items"] = self._param_schema(schema["items"])
        if p.get("x-nullable"):
            schema["x-nullable"] = True
        return cast(dict[str, Any], upgrade_schema(schema))

    def _parameter(self, p: dict[str, Any]) -> dict[str, Any]:
        out: dict[str, Any] = {"name": p.get("name"), "in": p.get("in")}
        for key in ("description", "required", "deprecated", "allowEmptyValue"):
            if key in p:
                out[key] = p[key]
        if out["in"] == "path":
            out["required"] = True
        out["schema"] = self._param_schema(p)
        fmt = p.get("collectionFormat")
        if p.get("type") == "array" and fmt:
            if out["in"] == "query" and fmt in _QUERY_STYLES:
                style, explode = _QUERY_STYLES[fmt]
                out["style"], out["explode"] = style, explode
            elif fmt == "csv":
                out["style"], out["explode"] = "simple", False
            else:
                self._warn(
                    f"parameter {p.get('name')!r}: collectionFormat {fmt!r} has no "
                    f"OpenAPI 3 equivalent for in={out['in']}; defaulted"
                )
        for key, value in p.items():
            if key.startswith("x-") and key != "x-nullable":
                out[key] = copy.deepcopy(value)
        return out

    def _request_body(self, params: list[dict[str, Any]], consumes: list[str]) -> dict[str, Any]:
        body = next((p for p in params if p.get("in") == "body"), None)
        if body is not None:
            out: dict[str, Any] = {
                "content": {ct: {"schema": self._schema(body.get("schema", {}))} for ct in consumes}
            }
            if body.get("description"):
                out["description"] = body["description"]
            if body.get("required"):
                out["required"] = True
            return out

        # formData parameters -> one object schema
        props: dict[str, Any] = {}
        required: list[str] = []
        has_file = False
        for p in params:
            has_file |= p.get("type") == "file"
            prop = self._param_schema(p)
            if p.get("description"):
                prop["description"] = p["description"]
            props[p["name"]] = prop
            if p.get("required"):
                required.append(p["name"])
        schema: dict[str, Any] = {"type": "object", "properties": props}
        if required:
            schema["required"] = required
        form_types = [ct for ct in consumes if "form" in ct]
        if has_file or not form_types:
            form_types = (
                ["multipart/form-data"] if has_file else ["application/x-www-form-urlencoded"]
            )
        result: dict[str, Any] = {"content": {ct: {"schema": schema} for ct in form_types}}
        if required:
            result["required"] = True
        return result

    def _response(self, resp: dict[str, Any], produces: list[str]) -> dict[str, Any]:
        if "$ref" in resp:
            return cast(dict[str, Any], self._refs(resp))
        out: dict[str, Any] = {"description": resp.get("description", "")}
        if "schema" in resp:
            schema = self._schema(resp["schema"])
            examples = resp.get("examples") or {}
            content: dict[str, Any] = {}
            for ct in produces:
                media: dict[str, Any] = {"schema": schema}
                if ct in examples:
                    media["example"] = examples[ct]
                content[ct] = media
            out["content"] = content
        if resp.get("headers"):
            out["headers"] = {
                name: {
                    **({"description": h["description"]} if "description" in h else {}),
                    "schema": self._param_schema(h),
                }
                for name, h in resp["headers"].items()
            }
        return out


def convert_swagger2(doc: dict[str, Any]) -> ConversionResult:
    """Convert a parsed Swagger 2.0 document to OpenAPI 3.1."""
    return _Swagger2Converter(doc).convert()
