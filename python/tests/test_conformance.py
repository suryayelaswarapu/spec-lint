"""The shared conformance files are the contract all three implementations must meet."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from speclint import __version__
from speclint.conformance import CASES, build_case

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "testdata"


@pytest.mark.parametrize("name", sorted(CASES))
def test_matches_golden(name: str) -> None:
    golden = json.loads((TESTDATA / "conformance" / f"{name}.json").read_text(encoding="utf-8"))
    assert build_case(TESTDATA, CASES[name]) == golden


@pytest.mark.parametrize("name", sorted(CASES))
def test_outputs_are_valid_openapi(name: str) -> None:
    from speclint.lint.validator import validate

    golden = json.loads((TESTDATA / "conformance" / f"{name}.json").read_text(encoding="utf-8"))
    for key in ("converted", "modernized"):
        assert list(validate(golden[key])) == []


def test_version_is_consistent_across_implementations() -> None:
    """The release version is declared once per toolchain; they must never drift apart."""
    sources = {
        "build.gradle.kts": r'version = "([^"]+)"',
        "java/src/main/java/io/github/suryayelaswarapu/speclint/Version.java": r'VERSION = "([^"]+)"',
        "go/internal/report/report.go": r'const Version = "([^"]+)"',
        "docs/api/openapi.yaml": r"\n  version: (\S+)",
        "CHANGELOG.md": r"## \[(\d[^\]]*)\]",
    }
    for rel, pattern in sources.items():
        match = re.search(pattern, (ROOT / rel).read_text("utf-8"))
        assert match, rel
        assert match.group(1) == __version__, f"{rel} says {match.group(1)}, expected {__version__}"
