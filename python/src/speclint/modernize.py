"""The ``modernize`` pipeline: detect -> convert -> auto-fix -> serialise -> lint."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from speclint.convert import convert_source
from speclint.detect import SpecKind
from speclint.fix import AppliedFix, apply_fixes
from speclint.io import SourceFile, dump, line_index
from speclint.lint import Finding, LintConfig, lint


@dataclass
class ModernizeResult:
    document: dict[str, Any]
    text: str
    source_kind: SpecKind
    warnings: list[str] = field(default_factory=list)
    fixes: list[AppliedFix] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)


def modernize(
    source: SourceFile,
    config: LintConfig | None = None,
    *,
    fix: bool = True,
    fmt: str = "yaml",
    allow_local_imports: bool = True,
) -> ModernizeResult:
    config = config or LintConfig()
    converted = convert_source(source, allow_local_imports=allow_local_imports)
    doc = converted.document
    fixes: list[AppliedFix] = []
    if fix:
        doc, fixes = apply_fixes(doc, config)
    text = dump(doc, fmt)
    # Line numbers come from the text that was written, so they match the output file.
    findings = lint(doc, config, line_index(text))
    return ModernizeResult(doc, text, converted.source_kind, converted.warnings, fixes, findings)
