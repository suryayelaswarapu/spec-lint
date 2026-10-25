// Gradle wrapper around the Python toolchain: a local virtualenv, ruff, mypy and pytest.
// Coverage is enforced by pytest-cov (fail_under = 80 in pyproject.toml).

plugins {
    base
}

description = "spec-lint: Python implementation"

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val pythonExe = providers.gradleProperty("python").getOrElse(if (isWindows) "python" else "python3")
val venvDir = layout.projectDirectory.dir(".venv")
val venvBin = venvDir.dir(if (isWindows) "Scripts" else "bin")
fun tool(name: String) = venvBin.file(if (isWindows) "$name.exe" else name).asFile.absolutePath

val venv by tasks.registering(Exec::class) {
    description = "Creates the local virtualenv."
    onlyIf { !venvDir.asFile.exists() }
    commandLine(pythonExe, "-m", "venv", venvDir.asFile.absolutePath)
}

val install by tasks.registering(Exec::class) {
    description = "Installs spec-lint (editable) with dev dependencies."
    dependsOn(venv)
    inputs.file("pyproject.toml")
    // The installed entry point is the output, so deleting .venv makes Gradle reinstall.
    outputs.file(tool("speclint"))
    commandLine(tool("python"), "-m", "pip", "install", "--quiet", "-e", ".[dev]")
}

val ruff by tasks.registering(Exec::class) {
    group = "verification"
    description = "Lints with ruff."
    dependsOn(install)
    commandLine(tool("ruff"), "check", "src", "tests", "scripts")
}

val ruffFormat by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks formatting with ruff."
    dependsOn(install)
    commandLine(tool("ruff"), "format", "--check", "src", "tests", "scripts")
}

val mypy by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-checks with mypy (strict)."
    dependsOn(install)
    commandLine(tool("mypy"))
}

val pytest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs pytest with coverage (fails under 80%)."
    dependsOn(install)
    inputs.dir("src")
    inputs.dir("tests")
    inputs.dir(rootProject.file("testdata"))
    commandLine(tool("pytest"), "--cov=speclint", "--cov-report=term-missing",
        "--cov-report=xml:build/coverage.xml")
}

tasks.register<Exec>("conformanceTest") {
    group = "verification"
    description = "Runs the cross-language conformance suite."
    dependsOn(install)
    commandLine(tool("pytest"), "-q", "tests/test_conformance.py")
}

tasks.named("check") {
    dependsOn(ruff, ruffFormat, mypy, pytest)
}

tasks.register<Exec>("serve") {
    group = "application"
    description = "Starts the FastAPI web service (override the port with -Pport=8080)."
    dependsOn(install)
    commandLine(tool("speclint"), "serve", "--port", providers.gradleProperty("port").getOrElse("8080"))
}
