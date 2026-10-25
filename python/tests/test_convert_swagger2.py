from __future__ import annotations

from typing import Any

from speclint.convert.openapi30 import upgrade_openapi30
from speclint.convert.schema import rewrite_refs, upgrade_schema
from speclint.convert.swagger2 import convert_swagger2


def _swagger(**extra: Any) -> dict[str, Any]:
    doc: dict[str, Any] = {"swagger": "2.0", "info": {"title": "T", "version": "1"}, "paths": {}}
    doc.update(extra)
    return doc


def test_servers_from_host_schemes_and_base_path() -> None:
    out = convert_swagger2(_swagger(host="h.example.com", basePath="/v1", schemes=["https"]))
    assert out.document["servers"] == [{"url": "https://h.example.com/v1"}]
    assert out.warnings == []


def test_servers_without_host_or_schemes_warn() -> None:
    assert convert_swagger2(_swagger()).document["servers"] == [{"url": "/"}]
    result = convert_swagger2(_swagger(host="h.example.com"))
    assert result.document["servers"] == [{"url": "https://h.example.com"}]
    assert any("schemes" in w for w in result.warnings)


def test_security_definitions() -> None:
    out = convert_swagger2(
        _swagger(
            securityDefinitions={
                "basic": {"type": "basic", "description": "d"},
                "key": {"type": "apiKey", "in": "query", "name": "k"},
                "pw": {"type": "oauth2", "flow": "password", "tokenUrl": "https://t", "scopes": {}},
                "app": {"type": "oauth2", "flow": "application", "tokenUrl": "https://t"},
                "bad": {"type": "oauth2", "flow": "weird"},
                "odd": {"type": "mystery"},
            }
        )
    )
    schemes = out.document["components"]["securitySchemes"]
    assert schemes["basic"] == {"type": "http", "scheme": "basic", "description": "d"}
    assert schemes["key"] == {"type": "apiKey", "name": "k", "in": "query"}
    assert "password" in schemes["pw"]["flows"]
    assert "clientCredentials" in schemes["app"]["flows"]
    assert "implicit" in schemes["bad"]["flows"]
    assert schemes["odd"]["type"] == "mystery"
    assert len(out.warnings) == 3  # no schemes + bad flow + unknown type


def test_operation_body_params_and_refs() -> None:
    doc = _swagger(
        host="h",
        schemes=["https"],
        consumes=["application/json", "application/xml"],
        parameters={
            "Limit": {"name": "limit", "in": "query", "type": "integer"},
            "Body": {"name": "body", "in": "body", "schema": {"$ref": "#/definitions/Item"}},
        },
        definitions={"Item": {"type": "object", "x-nullable": True}},
        paths={
            "/items": {
                "parameters": [{"$ref": "#/parameters/Limit"}],
                "post": {
                    "operationId": "createItem",
                    "parameters": [{"$ref": "#/parameters/Body"}, {"$ref": "#/parameters/Body"}],
                    "responses": {
                        "201": {"description": "ok", "schema": {"$ref": "#/definitions/Item"}}
                    },
                    "schemes": ["http"],
                    "x-internal": True,
                },
                "put": {"responses": {"204": {"description": "ok"}}},
                "x-path-ext": 1,
            },
            "/bad": "not-a-dict",
        },
    )
    out = convert_swagger2(doc).document
    item = out["paths"]["/items"]
    assert item["parameters"] == [{"$ref": "#/components/parameters/Limit"}]
    assert item["post"]["requestBody"] == {"$ref": "#/components/requestBodies/Body"}
    assert item["post"]["x-internal"] is True
    assert item["x-path-ext"] == 1
    assert out["components"]["requestBodies"]["Body"]["content"].keys() == {
        "application/json",
        "application/xml",
    }
    assert out["components"]["schemas"]["Item"] == {
        "anyOf": [{"type": "object"}, {"type": "null"}]
    } or (out["components"]["schemas"]["Item"]["type"] == ["object", "null"])
    assert "/bad" not in out["paths"]


def test_inline_body_formdata_and_file() -> None:
    doc = _swagger(
        paths={
            "/a": {
                "post": {
                    "parameters": [
                        {
                            "name": "b",
                            "in": "body",
                            "required": True,
                            "description": "d",
                            "schema": {"type": "object"},
                        },
                    ],
                    "responses": {"200": {"description": "ok"}},
                }
            },
            "/f": {
                "post": {
                    "consumes": ["application/x-www-form-urlencoded"],
                    "parameters": [
                        {
                            "name": "n",
                            "in": "formData",
                            "type": "string",
                            "required": True,
                            "description": "name",
                        },
                    ],
                    "responses": {"200": {"description": "ok"}},
                }
            },
            "/u": {
                "post": {
                    "parameters": [{"name": "file", "in": "formData", "type": "file"}],
                    "responses": {"200": {"description": "ok"}},
                }
            },
        }
    )
    paths = convert_swagger2(doc).document["paths"]
    body = paths["/a"]["post"]["requestBody"]
    assert body["required"] is True and body["description"] == "d"
    form = paths["/f"]["post"]["requestBody"]
    schema = form["content"]["application/x-www-form-urlencoded"]["schema"]
    assert schema["required"] == ["n"] and schema["properties"]["n"]["description"] == "name"
    upload = paths["/u"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"]
    assert upload["properties"]["file"] == {"type": "string", "format": "binary"}


def test_parameters_collection_formats() -> None:
    params = [
        {
            "name": "q",
            "in": "query",
            "type": "array",
            "items": {"type": "string"},
            "collectionFormat": "multi",
            "x-ext": 1,
        },
        {
            "name": "h",
            "in": "header",
            "type": "array",
            "items": {"type": "string"},
            "collectionFormat": "csv",
        },
        {
            "name": "t",
            "in": "header",
            "type": "array",
            "items": {"type": "string"},
            "collectionFormat": "tsv",
        },
        {"name": "id", "in": "path", "type": "string"},
    ]
    result = convert_swagger2(
        _swagger(paths={"/x/{id}": {"get": {"parameters": params, "responses": {}}}})
    )
    out = result.document["paths"]["/x/{id}"]["get"]["parameters"]
    assert (out[0]["style"], out[0]["explode"], out[0]["x-ext"]) == ("form", True, 1)
    assert (out[1]["style"], out[1]["explode"]) == ("simple", False)
    assert "style" not in out[2]
    assert out[3]["required"] is True
    assert any("tsv" in w for w in result.warnings)


def test_responses_headers_examples_and_refs() -> None:
    doc = _swagger(
        produces=["application/json"],
        responses={"NotFound": {"description": "nf"}},
        paths={
            "/a": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "schema": {"type": "string"},
                            "examples": {"application/json": "hi"},
                            "headers": {"X-Rate": {"type": "integer", "description": "r"}},
                        },
                        "404": {"$ref": "#/responses/NotFound"},
                    }
                }
            }
        },
        **{"x-top": True},
    )
    out = convert_swagger2(doc).document
    resp = out["paths"]["/a"]["get"]["responses"]
    assert resp["200"]["content"]["application/json"]["example"] == "hi"
    assert resp["200"]["headers"]["X-Rate"] == {"description": "r", "schema": {"type": "integer"}}
    assert resp["404"] == {"$ref": "#/components/responses/NotFound"}
    assert out["components"]["responses"]["NotFound"] == {"description": "nf"}
    assert out["x-top"] is True


def test_upgrade_schema_rules() -> None:
    schema = {
        "type": "object",
        "discriminator": "kind",
        "properties": {
            "a": {"type": "integer", "minimum": 1, "exclusiveMinimum": True},
            "b": {"type": "integer", "maximum": 5, "exclusiveMaximum": False},
            "c": {"type": "file"},
            "d": {"$ref": "#/x", "nullable": True},
            "e": {"type": "string", "enum": ["x"], "nullable": True},
            "f": {"type": ["string", "null"], "x-nullable": True},
        },
        "allOf": [{"type": "object"}],
        "items": {"type": "string", "example": "z"},
    }
    out = upgrade_schema(schema)
    props = out["properties"]
    assert props["a"] == {"type": "integer", "exclusiveMinimum": 1}
    assert props["b"] == {"type": "integer", "maximum": 5}
    assert props["c"] == {"type": "string", "format": "binary"}
    assert props["d"] == {"anyOf": [{"$ref": "#/x"}, {"type": "null"}]}
    assert props["e"] == {"type": ["string", "null"], "enum": ["x", None]}
    assert props["f"]["type"] == ["string", "null"]
    assert out["discriminator"] == {"propertyName": "kind"}
    assert out["items"]["examples"] == ["z"]
    assert upgrade_schema("not a dict") == "not a dict"


def test_rewrite_refs() -> None:
    node = [{"$ref": "#/definitions/A"}, {"$ref": "#/other/B"}, {"$ref": 3}]
    assert rewrite_refs(node, {"#/definitions/": "#/components/schemas/"}) == [
        {"$ref": "#/components/schemas/A"},
        {"$ref": "#/other/B"},
        {"$ref": 3},
    ]


def test_upgrade_openapi30_walks_everything() -> None:
    nullable = {"type": "string", "nullable": True}
    doc = {
        "openapi": "3.0.3",
        "paths": {
            "/a": {
                "parameters": [{"name": "p", "in": "query", "schema": dict(nullable)}],
                "get": {
                    "parameters": [{"name": "q", "in": "query", "schema": dict(nullable)}],
                    "requestBody": {"content": {"application/json": {"schema": dict(nullable)}}},
                    "responses": {
                        "200": {
                            "content": {"application/json": {"schema": dict(nullable)}},
                            "headers": {"X": {"schema": dict(nullable)}},
                        }
                    },
                },
            },
            "/skip": None,
        },
        "components": {
            "schemas": {"S": dict(nullable)},
            "parameters": {"P": {"schema": dict(nullable)}},
            "requestBodies": {"B": {"content": {"a/b": {"schema": dict(nullable)}}}},
            "responses": {"R": {"content": {"a/b": {"schema": dict(nullable)}}}},
        },
    }
    out = upgrade_openapi30(doc).document
    assert out["openapi"] == "3.1.0"
    assert "nullable" not in str(out)
