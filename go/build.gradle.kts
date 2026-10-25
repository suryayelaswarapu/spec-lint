// Gradle wrapper around the Go toolchain: vet, test with coverage, an 80% gate, and a binary.

plugins {
    base
}

description = "spec-lint: Go implementation"

val goExe = providers.gradleProperty("go").getOrElse("go")
val coverageFile = layout.buildDirectory.file("coverage.out")
val minCoverage = 80.0

val goVet by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs go vet."
    commandLine(goExe, "vet", "./...")
}

val goTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs go test with a coverage profile."
    inputs.files(fileTree(".") { include("**/*.go", "go.mod", "go.sum", "internal/lint/schemas/*.json") })
    inputs.dir(rootProject.file("testdata"))
    outputs.file(coverageFile)
    doFirst { coverageFile.get().asFile.parentFile.mkdirs() }
    commandLine(goExe, "test", "-race", "-covermode=atomic", "-coverpkg=./...",
        "-coverprofile=${coverageFile.get().asFile.absolutePath}", "./...")
}

val goCoverage by tasks.registering {
    group = "verification"
    description = "Fails the build when total Go coverage is below 80%."
    dependsOn(goTest)
    val report = providers.exec {
        workingDir = projectDir
        commandLine(goExe, "tool", "cover", "-func=${coverageFile.get().asFile.absolutePath}")
    }.standardOutput.asText
    doLast {
        val total = report.get().lines().last { it.startsWith("total:") }
            .split(Regex("\\s+")).last().removeSuffix("%").toDouble()
        logger.lifecycle("Go coverage: $total% (minimum $minCoverage%)")
        if (total < minCoverage) {
            throw GradleException("Go coverage $total% is below $minCoverage%")
        }
    }
}

val goBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the speclint binary into build/."
    commandLine(goExe, "build", "-trimpath", "-o", layout.buildDirectory.file("speclint").get().asFile.absolutePath,
        "./cmd/speclint")
}

tasks.register<Exec>("conformanceTest") {
    group = "verification"
    description = "Runs the cross-language conformance suite."
    commandLine(goExe, "test", "-run", "TestConformance", "./internal/app/")
}

tasks.named("check") { dependsOn(goVet, goCoverage) }
tasks.named("assemble") { dependsOn(goBuild) }

tasks.register<Exec>("serve") {
    group = "application"
    description = "Starts the net/http web service (override the port with -Pport=8080)."
    commandLine(goExe, "run", "./cmd/speclint", "serve", "--port", providers.gradleProperty("port").getOrElse("8080"))
}
