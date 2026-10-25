"""Regenerate the shared conformance files in ``testdata/conformance``.

The Python implementation is the reference. After an intentional behaviour change, run::

    python scripts/update_golden.py

then make the Java and Go implementations pass the updated files.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python" / "src"))

from speclint.conformance import CASES, build_case  # noqa: E402


def main() -> None:
    out_dir = ROOT / "testdata" / "conformance"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rel in CASES.items():
        data = build_case(ROOT / "testdata", rel)
        path = out_dir / f"{name}.json"
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
