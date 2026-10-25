from __future__ import annotations

import copy
from pathlib import Path
from typing import Any

import pytest

from conftest import minimal_oas
from speclint.errors import ConfigError
from speclint.lint import RULES, LintConfig, Severity, lint, load_config, summarize


def rules_for(doc: dict[str, Any]) -> set[str]:
    return {f.rule for f in lint(doc)}


def test_minimal_document_is_clean() -> None:
    assert lint(minimal_oas()) == []


def test_every_rule_documented() -> None:
    catalog = (Path(__file__).resolve().parents[2] / "docs" / "rules.md").read_text()
    for rule_id in RULES:
        assert f"`{rule_id}`" in catalog, rule_id


def mutate(fn: Any) -> dict[str, Any]:
    doc = copy.deepcopy(minimal_oas())
    fn(doc)
    return doc


OP = ("paths", "/items/{itemId}", "get")


def op(doc: dict[str, Any]) -> dict[str, Any]:
    return doc["paths"]["/items/{itemId}"]["get"]  # type: ignore[no-any-return]


CASES: list[tuple[str, Any]] = [
    ("openapi-version-3-1", lambda d: d.update(openapi="3.0.3")),
    ("info-description", lambda d: d["info"].pop("description")),
    ("info-contact", lambda d: d["info"].pop("contact")),
    ("info-license", lambda d: d["info"].pop("license")),
    ("info-version-semver", lambda d: d["info"].update(version="v1")),
    ("servers-defined", lambda d: d.pop("servers")),
    ("servers-https", lambda d: d.update(servers=[{"url": "http://api.example.com"}])),
    ("security-defined", lambda d: d["components"].pop("securitySchemes")),
    ("security-defined", lambda d: d.pop("security")),
    ("operation-id-required", lambda d: op(d).pop("operationId")),
    ("operation-id-camel-case", lambda d: op(d).update(operationId="Get_Item")),
    ("operation-summary", lambda d: op(d).pop("summary")),
    ("operation-tags", lambda d: op(d).pop("tags")),
    ("operation-tag-defined", lambda d: d.pop("tags")),
    ("operation-success-response", lambda d: op(d)["responses"].pop("200")),
    ("operation-error-response", lambda d: op(d)["responses"].pop("404")),
    ("error-response-schema", lambda d: op(d)["responses"].update({"500": {"description": "x"}})),
    ("parameter-description", lambda d: op(d)["parameters"][0].pop("description")),
    (
        "parameter-description",
        lambda d: d["components"].update(parameters={"P": {"name": "p", "in": "query"}}),
    ),
    ("path-kebab-case", lambda d: d["paths"].update({"/Bad_Path": {}})),
    ("path-no-trailing-slash", lambda d: d["paths"].update({"/items/": {}})),
    ("path-params-defined", lambda d: op(d).update(parameters=[])),
    (
        "path-params-defined",
        lambda d: op(d)["parameters"].append(
            {"name": "extra", "in": "path", "required": True, "schema": {}, "description": "x"}
        ),
    ),
    ("schema-name-pascal-case", lambda d: d["components"]["schemas"].update(lower={})),
    (
        "property-camel-case",
        lambda d: d["components"]["schemas"]["Item"]["properties"].update(snake_case={}),
    ),
    (
        "no-inline-object-schemas",
        lambda d: op(d)["responses"]["200"]["content"]["application/json"].update(
            schema={"type": "object"}
        ),
    ),
    ("no-legacy-nullable", lambda d: d["components"]["schemas"]["Item"].update(nullable=True)),
    ("no-unused-components", lambda d: d["components"]["schemas"].update(Unused={})),
    ("no-remote-refs", lambda d: op(d)["responses"].update({"201": {"$ref": "other.yaml#/x"}})),
    ("ref-resolves", lambda d: op(d)["responses"].update({"201": {"$ref": "#/nope"}})),
    ("oas3-valid-schema", lambda d: d["info"].pop("title")),
]


@pytest.mark.parametrize(("rule_id", "change"), CASES)
def test_rule_fires(rule_id: str, change: Any) -> None:
    assert rule_id in rules_for(mutate(change))


def test_operation_id_unique() -> None:
    def dup(d: dict[str, Any]) -> None:
        d["paths"]["/other"] = {"get": copy.deepcopy(op(d))}
        d["paths"]["/other"]["get"]["parameters"] = []

    assert "operation-id-unique" in rules_for(mutate(dup))


def test_tolerates_junk_structures() -> None:
    doc = {"openapi": "3.1.0", "paths": {"/a": "junk", "/b": {"get": "junk"}}, "info": {}}
    assert "servers-defined" in rules_for(doc)
    assert "info-version-semver" in rules_for({"openapi": "3.1.0", "paths": []})


def test_validator_crash_is_reported(monkeypatch: pytest.MonkeyPatch) -> None:
    import speclint.lint.validator as validator

    def boom(doc: Any) -> Any:
        raise RuntimeError("kaboom")

    monkeypatch.setattr(validator, "validate", boom)
    findings = [f for f in lint(minimal_oas()) if f.rule == "oas3-valid-schema"]
    assert "kaboom" in findings[0].message


def test_vendored_schemas_match_shared_copies() -> None:
    root = Path(__file__).resolve().parents[2]
    for name in ("oas-3.0.json", "oas-3.1.json"):
        packaged = root / "python/src/speclint/lint/schemas" / name
        assert packaged.read_bytes() == (root / "schemas/openapi" / name).read_bytes()


def test_unused_components_handles_escaped_and_nested_refs() -> None:
    doc = minimal_oas()
    doc["components"]["schemas"]["a/b"] = {}
    doc["components"]["schemas"]["Item"]["properties"]["x"] = {
        "$ref": "#/components/schemas/a~1b/properties"
    }
    assert "no-unused-components" not in {f.rule for f in lint(doc)}
    doc["components"]["schemas"]["Orphan"] = {"$ref": "#/components"}
    assert [f.path for f in lint(doc) if f.rule == "no-unused-components"] == [
        ("components", "schemas", "Orphan")
    ]


def test_validator_caps_errors() -> None:
    doc = minimal_oas()
    doc["paths"] = {f"/p{i}": {"get": {"responses": "bad"}} for i in range(30)}
    found = [f for f in lint(doc) if f.rule == "oas3-valid-schema"]
    assert 0 < len(found) <= 20


def test_lines_and_summary() -> None:
    doc = mutate(lambda d: d["info"].pop("contact"))
    findings = lint(doc, lines={("info",): 3})
    assert findings[0].line == 3
    assert findings[0].to_dict()["line"] == 3
    assert summarize(findings) == {"error": 0, "warning": 1, "info": 0}


def test_config_overrides_and_strict(tmp_path: Path) -> None:
    cfg = tmp_path / ".speclint.yaml"
    cfg.write_text(
        "extends: strict\nfail-on: warning\nrules:\n  info-contact: off\n  info-license: error\n"
    )
    config = load_config(search_dir=tmp_path)
    assert config.severity("info-contact") is Severity.OFF
    assert config.severity("info-license") is Severity.ERROR
    assert config.severity("operation-summary") is Severity.ERROR
    assert config.severity("parameter-description") is Severity.WARNING
    assert config.fails(Severity.WARNING) and not config.fails(Severity.INFO)
    doc = mutate(lambda d: (d["info"].pop("contact"), d["info"].pop("license")))
    assert {f.rule for f in lint(doc, config)} == {"info-license"}
    assert not LintConfig(fail_on="never").fails(Severity.ERROR)


def test_default_config_when_missing(tmp_path: Path) -> None:
    assert load_config(search_dir=tmp_path) == LintConfig()


@pytest.mark.parametrize(
    "content",
    [
        "extends: loose\n",
        "fail-on: sometimes\n",
        "rules: [a]\n",
        "rules:\n  not-a-rule: off\n",
        "rules:\n  info-contact: loud\n",
        "- not a mapping\n",
    ],
)
def test_config_errors(tmp_path: Path, content: str) -> None:
    cfg = tmp_path / "c.yaml"
    cfg.write_text(content)
    with pytest.raises(ConfigError):
        load_config(cfg)
