from __future__ import annotations

import json
from typing import Any

from conftest import minimal_oas
from speclint.fix import apply_fixes
from speclint.lint import LintConfig, Severity, lint
from speclint.lint.model import Finding
from speclint.report import render_json, render_markdown, render_sarif, render_text


def messy() -> dict[str, Any]:
    return {
        "openapi": "3.1.0",
        "components": {
            "schemas": {
                "old_name": {"type": "object", "nullable": True},
                "Keep": {"$ref": "#/components/schemas/old_name/properties"},
            }
        },
        "info": {"title": "x", "version": "1.0.0"},
        "paths": {
            "/v1/Stations/{id}": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": {
                                "application/json": {"schema": {"type": "object"}},
                                "application/xml": {"schema": {"type": "object"}},
                            },
                        },
                        "400": {"description": "bad"},
                        "401": {"$ref": "#/components/responses/X"},
                    }
                },
                "post": {
                    "operationId": "Create_Station",
                    "requestBody": {
                        "content": {
                            "application/json": {"schema": {"properties": {"a": {}}}},
                            "text/plain": {"schema": {"type": "string"}},
                            "application/x": {"schema": {"$ref": "#/components/schemas/old_name"}},
                            "bad": "x",
                        }
                    },
                    "responses": {"201": {"description": "c"}},
                },
                "put": {"operationId": "create_station", "responses": {}},
            },
            "/{only}": {
                "delete": {"responses": {"404": {"description": "nf", "content": {"a/b": {}}}}}
            },
        },
    }


def test_apply_fixes_end_to_end() -> None:
    fixed, applied = apply_fixes(messy())
    rules = {a.rule for a in applied}
    assert rules == {
        "no-legacy-nullable",
        "operation-id-required",
        "operation-id-camel-case",
        "operation-tags",
        "operation-tag-defined",
        "schema-name-pascal-case",
        "no-inline-object-schemas",
        "error-response-schema",
        "operation-error-response",
    }
    assert list(fixed)[:4] == ["openapi", "info", "tags", "paths"]
    item = fixed["paths"]["/v1/Stations/{id}"]
    assert item["get"]["operationId"] == "getV1StationsById"
    assert item["get"]["tags"] == ["stations"]
    # Operations are visited in a fixed method order (get, put, post, ...).
    assert item["put"]["operationId"] == "createStation"
    assert item["post"]["operationId"] == "createStation2"
    assert fixed["paths"]["/{only}"]["delete"]["tags"] == ["default"]
    schemas = fixed["components"]["schemas"]
    assert "OldName" in schemas and "old_name" not in schemas
    assert schemas["Keep"] == {"$ref": "#/components/schemas/OldName/properties"}
    assert schemas["OldName"]["type"] == ["object", "null"]
    content = item["get"]["responses"]["200"]["content"]
    assert content["application/json"]["schema"] == content["application/xml"]["schema"]
    assert item["get"]["responses"]["400"]["content"]["application/problem+json"]
    assert item["put"]["responses"]["default"] == {"$ref": "#/components/responses/Problem"}
    assert {"name": "stations"} in fixed["tags"]
    assert fixed["paths"]["/{only}"]["delete"]["responses"]["404"]["content"].keys() == {
        "a/b",
        "application/problem+json",
    }
    leftovers = [f for f in lint(fixed) if f.rule in rules]
    # Only the response whose $ref is broken cannot be fixed automatically.
    assert [(f.rule, f.path[-1]) for f in leftovers] == [("error-response-schema", "401")]


def test_fixes_respect_disabled_rules() -> None:
    from speclint.lint.model import RULES

    config = LintConfig(overrides=dict.fromkeys(RULES, Severity.OFF))
    fixed, applied = apply_fixes(messy(), config)
    assert applied == []
    assert "old_name" in fixed["components"]["schemas"]


def test_clean_document_unchanged() -> None:
    fixed, applied = apply_fixes(minimal_oas())
    assert applied == [] and fixed == minimal_oas()


def test_tag_defined_without_any_tags_removes_empty_list() -> None:
    doc = {"openapi": "3.1.0", "paths": {}}
    fixed, _ = apply_fixes(doc)
    assert "tags" not in fixed


FINDINGS = [
    Finding("info-contact", Severity.WARNING, "info.contact is missing", ("info",), 2),
    Finding("ref-resolves", Severity.ERROR, "bad | ref", ("paths", "/a"), None),
    Finding("info-license", Severity.INFO, "no license", (), None),
]


def test_render_text() -> None:
    text = render_text(FINDINGS, "api.yaml")
    assert "api.yaml:2  warning" in text
    assert "3 problems (1 errors, 1 warnings, 1 info)" in text
    assert "no problems found" in render_text([], "api.yaml")


def test_render_json() -> None:
    data = json.loads(render_json(FINDINGS, "api.yaml"))
    assert data["summary"] == {"error": 1, "warning": 1, "info": 1}
    assert data["findings"][1]["path"] == "/paths/~1a"


def test_render_sarif() -> None:
    sarif = json.loads(render_sarif(FINDINGS, "api.yaml"))
    run = sarif["runs"][0]
    assert sarif["version"] == "2.1.0"
    assert [r["id"] for r in run["tool"]["driver"]["rules"]] == [
        "info-contact",
        "info-license",
        "ref-resolves",
    ]
    assert run["results"][0]["locations"][0]["physicalLocation"]["region"]["startLine"] == 2
    assert run["results"][2]["level"] == "note"


def test_render_markdown() -> None:
    md = render_markdown(
        source="in.wsdl",
        source_kind="WSDL 1.1",
        output="out.yaml",
        warnings=["w|1"],
        fixes=[],
        findings=FINDINGS,
    )
    assert "bad \\| ref" in md and "w\\|1" in md and "_None._" in md
    clean = render_markdown(
        source="a", source_kind="b", output="c", warnings=[], fixes=[], findings=[]
    )
    assert "meets the standard" in clean
