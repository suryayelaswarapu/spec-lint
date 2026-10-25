# Python implementation: CLI + FastAPI web service, non-root.
# Build from the repository root:  docker build -f python/Dockerfile -t speclint-python .
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY python/pyproject.toml python/README.md ./
COPY python/src ./src
RUN pip install '.[server]' && useradd --system --uid 10001 speclint
USER speclint
EXPOSE 8080
ENTRYPOINT ["speclint"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
