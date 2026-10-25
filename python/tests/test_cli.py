from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

from speclint.cli import main


def run(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str], *args: str
) -> tuple[int, str, str]:
    monkeypatch.setattr(sys, "argv", ["speclint", *args])
    with pytest.raises(SystemExit) as exc:
        main()
        raise SystemExit(0)
    out, err = capsys.readouterr()
    return int(exc.value.code or 0), out, err


@pytest.fixture
def cli(monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]):  # type: ignore[no-untyped-def]
    return lambda *a: run(monkeypatch, capsys, *a)


def test_version_and_rules(cli) -> None:  # type: ignore[no-untyped-def]
    code, out, _ = cli("--version")
    assert code == 0 and "speclint" in out
    code, out, _ = cli("rules")
    assert "operation-id-required" in out and "fix" in out
    code, out, _ = cli("rules", "--format", "json")
    assert any(r["id"] == "servers-defined" for r in json.loads(out))


def test_convert(cli, testdata: Path, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    code, out, err = cli("convert", str(testdata / "weather/legacy-forecast-rpc.wsdl"))
    assert code == 0 and out.startswith("openapi: 3.1.0") and "note:" in err
    target = tmp_path / "out.json"
    code, _, _ = cli("convert", str(testdata / "weather/weather-api-v2.yaml"), "-o", str(target))
    assert json.loads(target.read_text())["openapi"] == "3.1.0"


def test_lint_formats_and_exit_codes(cli, testdata: Path, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    spec = testdata / "openapi/weather-api-v3.0.yaml"
    code, out, _ = cli("lint", str(spec))
    assert code == 0 and "openapi-version-3-1" in out
    code, _, _ = cli("lint", str(spec), "--fail-on", "warning")
    assert code == 1
    code, out, _ = cli("lint", str(spec), "--format", "json")
    assert json.loads(out)["summary"]["warning"] >= 1
    sarif = tmp_path / "r.sarif"
    code, _, _ = cli("lint", str(spec), "--format", "sarif", "-o", str(sarif))
    assert json.loads(sarif.read_text())["version"] == "2.1.0"


def test_lint_rejects_legacy_inputs(cli, testdata: Path) -> None:  # type: ignore[no-untyped-def]
    code, _, err = cli("lint", str(testdata / "weather/weather-api-v2.yaml"))
    assert code == 2 and "modernize" in err
    code, _, err = cli("lint", str(testdata / "weather/weather-service.wsdl"))
    assert code == 2 and "WSDL" in err


def test_fix(cli, testdata: Path, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    out_file = tmp_path / "fixed.yaml"
    code, _, err = cli("fix", str(testdata / "openapi/weather-api-v3.0.yaml"), "-o", str(out_file))
    assert code == 0 and "fixed [no-legacy-nullable]" in err
    assert "nullable" not in out_file.read_text()


def test_modernize(cli, testdata: Path, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    out_file, report, sarif = tmp_path / "w.yaml", tmp_path / "r.md", tmp_path / "r.sarif"
    code, _, err = cli(
        "modernize",
        str(testdata / "weather/weather-api-v2.yaml"),
        "-o",
        str(out_file),
        "--report",
        str(report),
        "--sarif",
        str(sarif),
    )
    assert code == 0
    assert "migration report" in report.read_text()
    assert str(tmp_path) not in report.read_text()  # no local paths leak into reports
    assert json.loads(sarif.read_text())["runs"][0]["results"]
    assert "problems" in err
    code, out, _ = cli(
        "modernize",
        str(testdata / "weather/weather-service.wsdl"),
        "--no-fix",
        "--fail-on",
        "info",
        "--no-local-imports",
    )
    assert code == 1 and out.startswith("openapi: 3.1.0")


def test_errors(cli, testdata: Path, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    code, _, err = cli("lint", str(tmp_path / "missing.yaml"))
    assert code == 2 and "not found" in err
    code, _, err = cli("--max-size", "10", "lint", str(testdata / "openapi/weather-api-v3.0.yaml"))
    assert code == 2 and "limit" in err
    code, _, err = cli("lint", "--bogus")
    assert code == 2
    code, _, err = cli("convert", str(testdata / "security/xxe.wsdl"))
    assert code == 2 and "Unsafe XML" in err
    code, _, err = cli("convert", str(testdata / "weather"), "-o", str(tmp_path / "x"))
    assert code == 2
    code, _, err = cli(
        "convert",
        str(testdata / "weather/weather-api-v2.yaml"),
        "-o",
        str(tmp_path / "no/such/dir/x.yaml"),
    )
    assert code == 2 and "error:" in err
