from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from speclint.io import SourceFile

TESTDATA = Path(__file__).resolve().parents[2] / "testdata"


@pytest.fixture
def testdata() -> Path:
    return TESTDATA


def source(text: str, name: str = "spec.yaml", directory: Path | None = None) -> SourceFile:
    return SourceFile(path=(directory or Path.cwd()) / name, text=text)


def minimal_oas(**overrides: Any) -> dict[str, Any]:
    """A small OpenAPI 3.1 document that passes every rule."""
    doc: dict[str, Any] = {
        "openapi": "3.1.0",
        "info": {
            "title": "Demo",
            "version": "1.0.0",
            "description": "Demo API",
            "contact": {"name": "Team"},
            "license": {"name": "MIT", "identifier": "MIT"},
        },
        "servers": [{"url": "https://api.example.com"}],
        "security": [{"key": []}],
        "tags": [{"name": "items"}],
        "paths": {
            "/items/{itemId}": {
                "get": {
                    "tags": ["items"],
                    "summary": "Get item",
                    "operationId": "getItem",
                    "parameters": [
                        {
                            "name": "itemId",
                            "in": "path",
                            "required": True,
                            "description": "Item id",
                            "schema": {"type": "string"},
                        }
                    ],
                    "responses": {
                        "200": {
                            "description": "OK",
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Item"}
                                }
                            },
                        },
                        "404": {"$ref": "#/components/responses/NotFound"},
                    },
                }
            }
        },
        "components": {
            "schemas": {
                "Item": {"type": "object", "properties": {"itemId": {"type": "string"}}},
                "Problem": {"type": "object", "properties": {"title": {"type": "string"}}},
            },
            "responses": {
                "NotFound": {
                    "description": "Not found",
                    "content": {
                        "application/problem+json": {
                            "schema": {"$ref": "#/components/schemas/Problem"}
                        }
                    },
                }
            },
            "securitySchemes": {"key": {"type": "apiKey", "in": "header", "name": "X-Key"}},
        },
    }
    doc.update(overrides)
    return doc
