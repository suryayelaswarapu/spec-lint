"""HTTP API tests. The same scenarios are covered in the Java and Go implementations."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from speclint.errors import SpecLintError
from speclint.server import service
from speclint.server.app import SECURITY_HEADERS, STATIC, Settings, create_app

ROOT = Path(__file__).resolve().parents[2]
TESTDATA = ROOT / "testdata"


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app(Settings()))


def read(rel: str) -> str:
    return (TESTDATA / rel).read_text(encoding="utf-8")


def test_health_rules_and_headers(client: TestClient) -> None:
    res = client.get("/health")
    assert res.json() == {"status": "UP", "version": "0.1.0", "implementation": "python"}
    assert res.headers["x-frame-options"] == "DENY"
    assert "default-src 'self'" in res.headers["content-security-policy"]
    rules = client.get("/api/v1/rules").json()["rules"]
    assert len(rules) == 29
    assert rules == sorted(rules, key=lambda r: r["id"])


@pytest.mark.parametrize("route", sorted(STATIC))
def test_static_assets(client: TestClient, route: str) -> None:
    res = client.get(route)
    assert res.status_code == 200
    assert res.content


def test_static_assets_match_shared_sources() -> None:
    static = ROOT / "python/src/speclint/server/static"
    for name in ("index.html", "app.js", "styles.css", "docs.html", "docs.js"):
        assert (static / name).read_bytes() == (ROOT / "web" / name).read_bytes(), name
    assert (static / "openapi.yaml").read_bytes() == (ROOT / "docs/api/openapi.yaml").read_bytes()


@pytest.mark.parametrize(
    "name",
    ["weather-api-v2", "weather-service", "legacy-forecast-rpc", "weather-api-v3.0"],
)
def test_modernize_matches_conformance(client: TestClient, name: str) -> None:
    golden = json.loads((TESTDATA / "conformance" / f"{name}.json").read_text())
    res = client.post("/api/v1/modernize", json={"spec": read(golden["input"]), "filename": name})
    assert res.status_code == 200
    body = res.json()
    assert body["openapi"] == golden["modernized"]
    assert body["sourceKind"] == golden["sourceKind"]
    assert len(body["warnings"]) == golden["warnings"]
    assert body["yaml"].startswith("openapi: 3.1.0")
    assert "# spec-lint migration report" in body["report"]
    assert body["summary"]["error"] == 0


def test_convert_lint_fix(client: TestClient) -> None:
    res = client.post("/api/v1/convert", json={"spec": read("weather/weather-api-v2.yaml")})
    assert res.status_code == 200
    assert res.json()["sourceKind"] == "swagger-2.0"

    spec = read("openapi/weather-api-v3.0.yaml")
    lint = client.post("/api/v1/lint", json={"spec": spec}).json()
    assert {f["rule"] for f in lint["findings"]} == {"openapi-version-3-1", "no-legacy-nullable"}
    assert all("line" in f for f in lint["findings"])
    strict = client.post("/api/v1/lint", json={"spec": spec, "config": {"extends": "strict"}})
    assert strict.json()["summary"]["error"] == 2

    fixed = client.post("/api/v1/fix", json={"spec": spec}).json()
    assert [f["rule"] for f in fixed["fixes"]] == ["no-legacy-nullable"]
    assert "nullable" not in fixed["yaml"]

    no_fix = client.post("/api/v1/modernize", json={"spec": spec, "fix": False}).json()
    assert no_fix["fixes"] == []


@pytest.mark.parametrize(
    ("route", "payload", "status", "fragment"),
    [
        ("/api/v1/lint", {"spec": "<definitions/>"}, 400, "WSDL"),
        ("/api/v1/fix", {"spec": "swagger: '2.0'\npaths: {}\n"}, 400, "Swagger 2.0"),
        ("/api/v1/convert", {"spec": ""}, 400, "spec"),
        ("/api/v1/convert", {"spec": 5}, 400, "spec"),
        ("/api/v1/convert", {"spec": "title: x"}, 400, "Not an OpenAPI"),
        ("/api/v1/lint", {"spec": "openapi: 3.1.0", "config": "x"}, 400, "config"),
        (
            "/api/v1/lint",
            {"spec": "openapi: 3.1.0", "config": {"extends": "loose"}},
            400,
            "extends",
        ),
        ("/api/v1/modernize", {"spec": "openapi: 3.1.0", "fix": "yes"}, 400, "fix"),
    ],
)
def test_bad_input(
    client: TestClient, route: str, payload: object, status: int, fragment: str
) -> None:
    res = client.post(route, json=payload)
    assert res.status_code == status
    assert res.headers["content-type"].startswith("application/problem+json")
    assert fragment in res.json()["detail"]


def test_malformed_json_and_routes(client: TestClient) -> None:
    res = client.post(
        "/api/v1/convert", content=b"{not json", headers={"content-type": "application/json"}
    )
    assert res.status_code == 400
    assert client.post("/api/v1/convert", json=["a"]).status_code == 400
    assert client.get("/nope").json()["title"] == "Not found"
    assert client.get("/api/v1/convert").status_code == 405


@pytest.mark.parametrize("name", ["xxe.wsdl", "billion-laughs.wsdl", "yaml-aliases.yaml"])
def test_unsafe_input_rejected(client: TestClient, name: str) -> None:
    res = client.post("/api/v1/convert", json={"spec": read(f"security/{name}")})
    assert res.status_code == 422
    assert res.json()["title"] == "Unsafe input rejected"


def test_server_never_reads_local_imports(client: TestClient) -> None:
    res = client.post("/api/v1/convert", json={"spec": read("security/path-traversal.wsdl")})
    # Imports are disabled entirely, so traversal is impossible rather than merely blocked.
    assert res.status_code == 400  # the fixture has no operations once imports are skipped
    assert "portType" in res.json()["detail"]


def test_body_limit() -> None:
    client = TestClient(create_app(Settings(max_bytes=100)))
    res = client.post("/api/v1/convert", json={"spec": "x" * 200})
    assert res.status_code == 413
    assert res.headers["x-content-type-options"] == "nosniff"

    def chunks():  # no Content-Length: exercises the streaming check
        yield b'{"spec": "'
        yield b"x" * 200
        yield b'"}'

    res = client.post(
        "/api/v1/convert", content=chunks(), headers={"content-type": "application/json"}
    )
    assert res.status_code == 413


def test_bearer_token() -> None:
    client = TestClient(create_app(Settings(api_token="s3cret")))
    body = {"spec": read("openapi/weather-api-v3.0.yaml")}
    denied = client.post("/api/v1/lint", json=body)
    assert denied.status_code == 401
    assert denied.headers["www-authenticate"] == "Bearer"
    wrong = client.post("/api/v1/lint", json=body, headers={"Authorization": "Bearer nope"})
    assert wrong.status_code == 401
    ok = client.post("/api/v1/lint", json=body, headers={"Authorization": "Bearer s3cret"})
    assert ok.status_code == 200
    assert client.get("/api/v1/rules").status_code == 200  # read-only metadata stays open
    assert client.get("/health").status_code == 200


def test_settings_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SPECLINT_MAX_BYTES", "123")
    monkeypatch.setenv("SPECLINT_API_TOKEN", "t")
    assert Settings.from_env() == Settings(max_bytes=123, api_token="t")
    monkeypatch.setenv("SPECLINT_API_TOKEN", "")
    assert Settings.from_env().api_token is None


@pytest.mark.parametrize("raw", ["2MB", "-1", "0", "1.5"])
def test_settings_reject_bad_max_bytes(monkeypatch: pytest.MonkeyPatch, raw: str) -> None:
    monkeypatch.setenv("SPECLINT_MAX_BYTES", raw)
    with pytest.raises(ValueError, match="SPECLINT_MAX_BYTES"):
        Settings.from_env()


def test_auth_rejects_before_reading_body() -> None:
    """An unauthenticated oversized upload gets 401, proving the body was never buffered."""
    client = TestClient(create_app(Settings(max_bytes=10, api_token="s3cret")))
    res = client.post(
        "/api/v1/lint", content=b"x" * 1000, headers={"content-type": "application/json"}
    )
    assert res.status_code == 401
    assert res.headers["x-frame-options"] == "DENY"


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, "spec"),
        ("../../etc/passwd", "passwd"),
        ("C:\\x\\api.wsdl", "api.wsdl"),
        ("a<b>.yaml", "a_b_.yaml"),
        ("x" * 300, "x" * 200),
        ("   ", "spec"),
    ],
)
def test_safe_filename(raw: object, expected: str) -> None:
    assert service.safe_filename(raw) == expected


def test_serve_command(monkeypatch: pytest.MonkeyPatch) -> None:
    import uvicorn
    from click.testing import CliRunner

    from speclint.cli import cli

    calls: list[dict[str, object]] = []
    monkeypatch.setattr(uvicorn, "run", lambda app, **kw: calls.append(kw))
    ok = CliRunner().invoke(cli, ["serve", "--port", "9999"])
    assert ok.exit_code == 0 and calls[0]["port"] == 9999 and calls[0]["host"] == "127.0.0.1"
    monkeypatch.setenv("SPECLINT_MAX_BYTES", "2MB")
    bad = CliRunner().invoke(cli, ["serve"])
    assert isinstance(bad.exception, SpecLintError) and "SPECLINT_MAX_BYTES" in str(bad.exception)


def test_external_assets_are_pinned_with_sri() -> None:
    """Every third-party asset on the docs page has an integrity hash and is allowed by CSP."""
    import re

    html = (ROOT / "web/docs.html").read_text("utf-8")
    tags = re.findall(r"<(?:script|link)[^>]*https://[^>]*>", html, flags=re.S)
    assert tags
    csp = SECURITY_HEADERS["Content-Security-Policy"]
    for tag in tags:
        assert 'integrity="sha384-' in tag and 'crossorigin="anonymous"' in tag, tag
        url = re.search(r'(?:src|href)="(https://[^"]+)"', tag)
        assert url and any(url.group(1).startswith(src) for src in re.findall(r"https://\S+/", csp))
