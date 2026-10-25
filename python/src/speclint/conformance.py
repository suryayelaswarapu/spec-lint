"""Builds the cross-language conformance records stored in ``testdata/conformance``.

Every implementation (Python, Java, Go) must produce, for each case:

* ``converted``  - the OpenAPI 3.1 document from ``convert`` (JSON-equal)
* ``modernized`` - the document from ``modernize`` (convert + fix), JSON-equal
* ``fixes``      - sorted ``rule|pointer`` of applied fixes
* ``findings``   - sorted ``rule|severity|pointer`` from linting the modernized document
  (``oas3-valid-schema`` is excluded: each language uses its own validator)
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from speclint.convert import convert_source
from speclint.fix import apply_fixes
from speclint.io import read_source
from speclint.lint import lint

CASES: dict[str, str] = {
    "weather-api-v2": "weather/weather-api-v2.yaml",
    "weather-service": "weather/weather-service.wsdl",
    "legacy-forecast-rpc": "weather/legacy-forecast-rpc.wsdl",
    "weather-api-v3.0": "openapi/weather-api-v3.0.yaml",
}

VALIDATOR_RULE = "oas3-valid-schema"


def build_case(testdata: Path, rel: str) -> dict[str, Any]:
    source = read_source(testdata / rel)
    converted = convert_source(source)
    modernized, fixes = apply_fixes(converted.document)
    findings = [f for f in lint(modernized) if f.rule != VALIDATOR_RULE]
    return {
        "input": rel,
        "sourceKind": converted.source_kind.value,
        "warnings": len(converted.warnings),
        "converted": converted.document,
        "modernized": modernized,
        "fixes": sorted(f"{f.rule}|{f.path}" for f in fixes),
        "findings": sorted(f"{f.rule}|{f.severity.value}|{f.pointer}" for f in findings),
    }
