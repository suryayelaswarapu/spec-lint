// Root build: orchestration only. Each language lives in its own subproject.

plugins {
    base
}

allprojects {
    group = "io.github.suryayelaswarapu"
    version = "0.1.0"
}

tasks.named("check") {
    description = "Runs tests, coverage gates and static checks for Java, Python and Go."
    dependsOn(":java:check", ":python:check", ":go:check")
}

tasks.register("conformance") {
    group = "verification"
    description = "Runs only the shared cross-language conformance suite."
    dependsOn(":java:conformanceTest", ":python:conformanceTest", ":go:conformanceTest")
}

// The web UI (/web) and API contract (/docs/api/openapi.yaml) are shared by all three servers.
// Java packages them at build time; Python and Go keep committed copies (embedding needs files
// inside their source trees). Tests in each language fail if a copy drifts from the source.
tasks.register<Copy>("syncWebAssets") {
    group = "build"
    description = "Copies /web and the API contract into the Python and Go source trees."
    val targets = listOf("python/src/speclint/server/static", "go/internal/server/static")
    from("web")
    from("docs/api/openapi.yaml")
    into(layout.projectDirectory.dir(targets[0]))
    doLast {
        copy {
            from("web")
            from("docs/api/openapi.yaml")
            into(layout.projectDirectory.dir(targets[1]))
        }
    }
}
