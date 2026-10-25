"""FastAPI application. Run with ``speclint serve`` (or ``uvicorn speclint.server.app:app``).

Security: request bodies are size-limited, an optional bearer token protects the API,
responses carry strict security headers (CSP, no framing, no sniffing), errors are RFC 9457
Problem Details without stack traces, and only a fixed list of static files is served.
"""

from __future__ import annotations

import hmac
import logging
import os
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from importlib import resources
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from speclint import __version__
from speclint.errors import SecurityError, SpecLintError
from speclint.server import service

log = logging.getLogger("speclint.server")

DEFAULT_MAX_BYTES = 2 * 1024 * 1024
PROBLEM = "application/problem+json"
SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self' https://unpkg.com/swagger-ui-dist@5.17.14/; "
        "style-src 'self' 'unsafe-inline' https://unpkg.com/swagger-ui-dist@5.17.14/; "
        "img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    ),
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}
STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/styles.css": ("styles.css", "text/css; charset=utf-8"),
    "/docs.html": ("docs.html", "text/html; charset=utf-8"),
    "/docs.js": ("docs.js", "text/javascript; charset=utf-8"),
    "/api/v1/openapi.yaml": ("openapi.yaml", "application/yaml; charset=utf-8"),
}


@dataclass(frozen=True)
class Settings:
    max_bytes: int = DEFAULT_MAX_BYTES
    api_token: str | None = None

    @classmethod
    def from_env(cls) -> Settings:
        """Read settings from the environment, failing fast on a malformed value."""
        raw = os.environ.get("SPECLINT_MAX_BYTES", "").strip()
        max_bytes = DEFAULT_MAX_BYTES
        if raw:
            if not raw.isdigit() or int(raw) <= 0:
                raise ValueError(
                    f"SPECLINT_MAX_BYTES must be a positive whole number of bytes, got {raw!r}"
                )
            max_bytes = int(raw)
        return cls(max_bytes=max_bytes, api_token=os.environ.get("SPECLINT_API_TOKEN") or None)


def problem(status: int, title: str, detail: str | None = None, **headers: str) -> JSONResponse:
    body: dict[str, Any] = {"type": "about:blank", "title": title, "status": status}
    if detail:
        body["detail"] = detail
    return JSONResponse(body, status_code=status, media_type=PROBLEM, headers=headers or None)


class BodyLimit:
    """Pure ASGI middleware that buffers the body and rejects it above ``max_bytes``.

    Works for chunked uploads too (no Content-Length), and never hands a partial body to the
    application.
    """

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        declared = dict(scope.get("headers") or []).get(b"content-length")
        if declared is not None and declared.isdigit() and int(declared) > self.max_bytes:
            await self._too_large(scope, receive, send)
            return
        chunks: list[bytes] = []
        size = 0
        while True:
            message = await receive()
            if message["type"] != "http.request":  # client went away
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > self.max_bytes:
                await self._too_large(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        replayed = False

        async def replay() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)

    async def _too_large(self, scope: Scope, receive: Receive, send: Send) -> None:
        response = problem(413, "Payload too large", f"Request body exceeds {self.max_bytes} bytes")
        response.headers.update(SECURITY_HEADERS)
        await response(scope, receive, send)


class BearerAuth:
    """Pure ASGI middleware that checks the bearer token on ``POST /api/v1/*``.

    It runs outside :class:`BodyLimit`, so an unauthenticated client is rejected before any
    of its request body is read or buffered.
    """

    def __init__(self, app: ASGIApp, token: str | None) -> None:
        self.app = app
        self.expected = f"Bearer {token}".encode() if token else None

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if (
            self.expected is not None
            and scope["type"] == "http"
            and scope["method"] == "POST"
            and scope["path"].startswith("/api/v1/")
        ):
            supplied = dict(scope.get("headers") or []).get(b"authorization", b"")
            if not hmac.compare_digest(supplied, self.expected):
                response = problem(
                    401, "Unauthorized", "A valid bearer token is required",
                    **{"WWW-Authenticate": "Bearer"},
                )  # fmt: skip
                response.headers.update(SECURITY_HEADERS)
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(
        title="spec-lint API",
        version=__version__,
        docs_url=None,  # the shared contract and docs live at /api/v1/openapi.yaml and /docs.html
        redoc_url=None,
        openapi_url=None,
    )

    @app.middleware("http")
    async def guard(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        response = await call_next(request)
        for key, value in SECURITY_HEADERS.items():
            response.headers[key] = value
        return response

    @app.exception_handler(SpecLintError)
    async def spec_error(_: Request, exc: SpecLintError) -> JSONResponse:
        if isinstance(exc, SecurityError):
            return problem(422, "Unsafe input rejected", str(exc))
        return problem(400, "Invalid input", str(exc))

    @app.exception_handler(RequestValidationError)
    async def bad_request(_: Request, exc: RequestValidationError) -> JSONResponse:
        return problem(400, "Invalid request", "Request body must be a JSON object with 'spec'")

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        title = {404: "Not found", 405: "Method not allowed"}.get(exc.status_code, "Error")
        return problem(exc.status_code, title)

    @app.exception_handler(Exception)
    async def unexpected(_: Request, exc: Exception) -> JSONResponse:  # pragma: no cover
        log.exception("Unhandled error")  # never echo the submitted contract
        return problem(500, "Internal error", "Unexpected error")

    async def json_body(request: Request) -> dict[str, Any]:
        try:
            body = await request.json()
        except ValueError as exc:
            raise RequestValidationError([]) from exc
        if not isinstance(body, dict):
            raise RequestValidationError([])
        return body

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return service.health()

    @app.get("/api/v1/rules")
    async def rules() -> dict[str, Any]:
        return service.rules()

    @app.post("/api/v1/convert")
    async def convert(request: Request) -> dict[str, Any]:
        return await run_in_threadpool(service.convert, await json_body(request))

    @app.post("/api/v1/lint")
    async def lint(request: Request) -> dict[str, Any]:
        return await run_in_threadpool(service.lint_spec, await json_body(request))

    @app.post("/api/v1/fix")
    async def fix(request: Request) -> dict[str, Any]:
        return await run_in_threadpool(service.fix_spec, await json_body(request))

    @app.post("/api/v1/modernize")
    async def modernize(request: Request) -> dict[str, Any]:
        return await run_in_threadpool(service.modernize_spec, await json_body(request))

    static_dir = resources.files("speclint.server") / "static"
    for route, (filename, media_type) in STATIC.items():

        def make(name: str = filename, media: str = media_type) -> Callable[[], Response]:
            content = (static_dir / name).read_bytes()

            async def serve() -> Response:
                return Response(content, media_type=media)

            return serve  # type: ignore[return-value]

        app.add_api_route(route, make(), methods=["GET", "HEAD"], include_in_schema=False)

    # Starlette runs the last-added middleware first: auth, then the body limit, then the app.
    app.add_middleware(BodyLimit, max_bytes=settings.max_bytes)
    app.add_middleware(BearerAuth, token=settings.api_token)
    return app


def app_from_env() -> FastAPI:  # pragma: no cover - used by `uvicorn --factory`
    return create_app(Settings.from_env())
