"""Hostile inputs must be rejected or neutralised, never acted on."""

from __future__ import annotations

from pathlib import Path

import pytest

from speclint.convert import convert_source
from speclint.errors import SecurityError
from speclint.io import parse_structured, read_source
from speclint.lint import lint


@pytest.mark.parametrize("name", ["xxe.wsdl", "billion-laughs.wsdl"])
def test_dtd_and_entities_rejected(testdata: Path, name: str) -> None:
    with pytest.raises(SecurityError, match="Unsafe XML"):
        convert_source(read_source(testdata / "security" / name))


def test_path_traversal_import_rejected(testdata: Path) -> None:
    with pytest.raises(SecurityError, match="escapes"):
        convert_source(read_source(testdata / "security/path-traversal.wsdl"))


def test_remote_import_never_fetched(testdata: Path) -> None:
    result = convert_source(read_source(testdata / "security/remote-import.wsdl"))
    assert any("not fetched" in w for w in result.warnings)


def test_remote_ref_flagged_and_validator_skipped(testdata: Path) -> None:
    doc = parse_structured(read_source(testdata / "security/remote-ref.yaml").text)
    rules = {f.rule for f in lint(doc)}
    assert "no-remote-refs" in rules
    assert "oas3-valid-schema" not in rules  # validator never runs on unvetted refs
