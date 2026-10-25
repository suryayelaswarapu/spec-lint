from __future__ import annotations

from pathlib import Path

import pytest

from speclint.detect import SpecKind, detect_document, detect_xml
from speclint.errors import InputError, SecurityError, UnsupportedSpecError
from speclint.io import dump, format_for_path, line_index, parse_structured, read_source


def test_parse_yaml_keeps_dates_as_strings() -> None:
    doc = parse_structured("openapi: 3.1.0\ninfo: {version: 2024-01-01}\n")
    assert doc["info"]["version"] == "2024-01-01"


def test_parse_json() -> None:
    assert parse_structured('{"swagger": "2.0"}') == {"swagger": "2.0"}


@pytest.mark.parametrize("text", ["- a\n- b\n", "just a string", "{bad json"])
def test_parse_rejects_non_mapping_or_garbage(text: str) -> None:
    with pytest.raises(InputError):
        parse_structured(text)


def test_yaml_alias_bomb_rejected(testdata: Path) -> None:
    with pytest.raises(SecurityError, match="aliases"):
        parse_structured(read_source(testdata / "security/yaml-aliases.yaml").text)


def test_yaml_python_tags_rejected() -> None:
    with pytest.raises(InputError):
        parse_structured("a: !!python/object/apply:os.system ['echo hi']\n")


def test_read_source_size_limit(tmp_path: Path) -> None:
    big = tmp_path / "big.yaml"
    big.write_text("a: " + "x" * 100)
    with pytest.raises(SecurityError, match="limit"):
        read_source(big, max_bytes=10)


def test_read_source_missing_and_binary(tmp_path: Path) -> None:
    with pytest.raises(InputError, match="not found"):
        read_source(tmp_path / "nope.yaml")
    binary = tmp_path / "b.yaml"
    binary.write_bytes(b"\xff\xfe\x00bad")
    with pytest.raises(InputError, match="UTF-8"):
        read_source(binary)


def test_dump_roundtrip_yaml_and_json() -> None:
    doc = {"openapi": "3.1.0", "info": {"description": "line1\nline2"}, "list": [1, 2]}
    assert parse_structured(dump(doc, "yaml")) == doc
    assert parse_structured(dump(doc, "json")) == doc
    assert "|" in dump(doc, "yaml")
    with pytest.raises(ValueError):
        dump(doc, "xml")


def test_format_for_path() -> None:
    assert format_for_path("a.json") == "json"
    assert format_for_path("a.yml") == "yaml"
    assert format_for_path(None) == "yaml"


def test_line_index() -> None:
    index = line_index("openapi: 3.1.0\npaths:\n  /a:\n    get: {}\nlist:\n  - x\n")
    assert index[("paths", "/a", "get")] == 4
    assert index[("list", "0")] == 6
    assert line_index("") == {}


@pytest.mark.parametrize(
    ("doc", "kind"),
    [
        ({"swagger": "2.0"}, SpecKind.SWAGGER2),
        ({"openapi": "3.0.3"}, SpecKind.OPENAPI30),
        ({"openapi": "3.1.0"}, SpecKind.OPENAPI31),
    ],
)
def test_detect_document(doc: dict[str, str], kind: SpecKind) -> None:
    assert detect_document(doc) is kind
    assert kind.label


@pytest.mark.parametrize("doc", [{"swagger": "1.2"}, {"openapi": "4.0"}, {"title": "x"}])
def test_detect_document_unsupported(doc: dict[str, str]) -> None:
    with pytest.raises(UnsupportedSpecError):
        detect_document(doc)


def test_detect_xml() -> None:
    assert detect_xml('<wsdl:definitions xmlns:wsdl="x"/>') is SpecKind.WSDL11
    with pytest.raises(UnsupportedSpecError, match=r"2\.0"):
        detect_xml('<description xmlns="http://www.w3.org/ns/wsdl"/>')
    with pytest.raises(UnsupportedSpecError):
        detect_xml("<root/>")
