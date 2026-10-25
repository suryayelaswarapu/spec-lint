"""Converters from legacy formats to OpenAPI 3.1.

Each converter returns a :class:`ConversionResult`; :func:`convert_source` picks the right one.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from speclint.detect import SpecKind, detect_document, detect_xml, looks_like_xml
from speclint.io import SourceFile, parse_structured


@dataclass
class ConversionResult:
    """Output of a conversion: the new document plus anything the user should know."""

    document: dict[str, Any]
    source_kind: SpecKind
    warnings: list[str] = field(default_factory=list)


def convert_source(
    source: SourceFile, *, base_dir: Path | None = None, allow_local_imports: bool = True
) -> ConversionResult:
    """Detect the input format and convert it to OpenAPI 3.1."""
    # Local imports keep start-up fast and avoid import cycles.
    from speclint.convert.openapi30 import upgrade_openapi30
    from speclint.convert.swagger2 import convert_swagger2
    from speclint.convert.wsdl import convert_wsdl

    if looks_like_xml(source.text):
        detect_xml(source.text)
        return convert_wsdl(
            source.text,
            base_dir=base_dir if base_dir is not None else source.path.parent,
            allow_local_imports=allow_local_imports,
        )

    doc = parse_structured(source.text, source.name)
    kind = detect_document(doc)
    if kind is SpecKind.SWAGGER2:
        return convert_swagger2(doc)
    if kind is SpecKind.OPENAPI30:
        return upgrade_openapi30(doc)
    return ConversionResult(document=doc, source_kind=kind)


__all__ = ["ConversionResult", "convert_source"]
