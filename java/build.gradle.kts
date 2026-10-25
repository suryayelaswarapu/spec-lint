import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    application
    jacoco
    id("org.springframework.boot") version "3.3.5"
}

description = "spec-lint: Java implementation"

// Any JDK 21+ can build this; bytecode targets Java 21 (see options.release below).

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.networknt:json-schema-validator:3.0.8")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Two entry points:
//   * Main                 - the CLI (./gradlew :java:installDist -> build/install/speclint/bin/speclint)
//   * SpecLintApplication  - the Spring Boot web service (./gradlew :java:bootRun, or java -jar)
application {
    mainClass = "io.github.suryayelaswarapu.speclint.Main"
    applicationName = "speclint"
}

springBoot {
    mainClass = "io.github.suryayelaswarapu.speclint.SpecLintApplication"
}

tasks.bootJar {
    archiveFileName = "speclint.jar"
    mainClass = "io.github.suryayelaswarapu.speclint.SpecLintApplication"
}

tasks.bootRun {
    mainClass = "io.github.suryayelaswarapu.speclint.SpecLintApplication"
    // Same switch as :python:serve and :go:serve, e.g. ./gradlew :java:bootRun -Pport=9090
    providers.gradleProperty("port").orNull?.let { args("--server.port=$it") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
}

// Shared assets: official OpenAPI JSON Schemas, the web UI and the API contract.
tasks.processResources {
    from(rootProject.file("schemas/openapi")) {
        include("*.json")
        into("io/github/suryayelaswarapu/speclint/schemas")
    }
    from(rootProject.file("web")) {
        into("static")
    }
    from(rootProject.file("docs/api/openapi.yaml")) {
        into("static/api/v1")
    }
}

val testdata = rootProject.file("testdata")

tasks.test {
    useJUnitPlatform()
    systemProperty("speclint.testdata", testdata.absolutePath)
    inputs.dir(testdata).withPropertyName("testdata").withPathSensitivity(PathSensitivity.RELATIVE)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("conformanceTest") {
    description = "Runs the cross-language conformance suite."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("speclint.testdata", testdata.absolutePath)
    filter { includeTestsMatching("*ConformanceTest") }
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
