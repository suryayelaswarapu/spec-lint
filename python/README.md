# spec-lint (Python)

The reference implementation of [spec-lint](../README.md). It requires Python 3.10+.

```bash
pip install -e '.[dev]'
speclint serve                 # web service on http://localhost:8080 (FastAPI)
speclint modernize ../testdata/weather/weather-service.wsdl -o weather.yaml --report report.md
pytest                      # 162 tests, coverage gate 80% (currently ~98%)
ruff check . && mypy        # lint + strict typing
```

| Module | Responsibility |
|---|---|
| `io.py`, `detect.py` | Safe reading/parsing (size limit, alias cap, no timestamps), format detection |
| `convert/` | `swagger2.py`, `openapi30.py`, `wsdl.py` + `xsd.py` (+ hardened `xmlsafe.py`) |
| `lint/` | Rule registry (`rules.py`), config, document walkers |
| `fix.py` | Wire-safe auto-fixes |
| `report.py` | Text / JSON / SARIF / Markdown |
| `cli.py` | `click` CLI |
| `server/` | FastAPI app (`app.py`), framework-free handlers (`service.py`), static UI |
| `conformance.py` | Builds the cross-language golden records (`scripts/update_golden.py`) |
