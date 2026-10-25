# Go implementation: CLI + web service in a distroless, non-root image.
# Build from the repository root:  docker build -f go/Dockerfile -t speclint-go .
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go/go.mod go/go.sum ./
RUN go mod download
COPY go/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /speclint ./cmd/speclint

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /speclint /usr/local/bin/speclint
USER nonroot
EXPOSE 8080
ENTRYPOINT ["speclint"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
