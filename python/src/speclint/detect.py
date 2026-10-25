"""Detect what kind of API description a file contains."""

from __future__ import annotations

import re
from enum import Enum
from typing import Any

from speclint.errors import UnsupportedSpecError


class SpecKind(str, Enum):
    SWAGGER2 = "swagger-2.0"
    OPENAPI30 = "openapi-3.0"
    OPENAPI31 = "openapi-3.1"
    WSDL11 = "wsdl-1.1"

    @property
    def label(self) -> str:
        return {
            SpecKind.SWAGGER2: "Swagger 2.0",
            SpecKind.OPENAPI30: "OpenAPI 3.0",
            SpecKind.OPENAPI31: "OpenAPI 3.1",
            SpecKind.WSDL11: "WSDL 1.1",
        }[self]


WSDL11_NS = "http://schemas.xmlsoap.org/wsdl/"
WSDL20_NS = "http://www.w3.org/ns/wsdl"

_ROOT_TAG = re.compile(r"<(?:[\w.-]+:)?(definitions|description)\b[^>]*>", re.DOTALL)


def looks_like_xml(text: str) -> bool:
    return text.lstrip().startswith("<")


def detect_xml(text: str) -> SpecKind:
    """Cheap, parser-free check of the XML root element."""
    match = _ROOT_TAG.search(text)
    if match is None:
        raise UnsupportedSpecError("XML input is not a WSDL document (no <definitions> root)")
    if match.group(1) == "description" or WSDL20_NS in match.group(0):
        raise UnsupportedSpecError("WSDL 2.0 is not supported yet; only WSDL 1.1")
    return SpecKind.WSDL11


def detect_document(doc: dict[str, Any]) -> SpecKind:
    """Classify a parsed JSON/YAML document."""
    if str(doc.get("swagger", "")).startswith("2"):
        return SpecKind.SWAGGER2
    version = str(doc.get("openapi", ""))
    if version.startswith("3.0"):
        return SpecKind.OPENAPI30
    if version.startswith("3.1"):
        return SpecKind.OPENAPI31
    if "swagger" in doc or "openapi" in doc:
        raise UnsupportedSpecError(
            f"Unsupported spec version: swagger={doc.get('swagger')!r} openapi={version!r}"
        )
    raise UnsupportedSpecError("Not an OpenAPI/Swagger document (no 'openapi' or 'swagger' key)")
