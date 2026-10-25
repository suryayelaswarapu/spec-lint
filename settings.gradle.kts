pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "spec-lint"

// One build, three implementations of the same tool. `./gradlew check` runs them all.
include("java", "python", "go")
