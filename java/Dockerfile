# Java implementation: Spring Boot web service (the jar also contains the CLI classes).
# Build from the repository root:  docker build -f java/Dockerfile -t speclint-java .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY python/build.gradle.kts ./python/
COPY go/build.gradle.kts ./go/
COPY schemas ./schemas
COPY web ./web
COPY docs/api ./docs/api
COPY java ./java
RUN ./gradlew :java:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 speclint
COPY --from=build /src/java/build/libs/speclint.jar /app/speclint.jar
USER speclint
EXPOSE 8080
ENV SPECLINT_HOST=0.0.0.0
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/speclint.jar"]
