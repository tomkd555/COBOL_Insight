plugins {
    `kotlin-dsl`
}

// build-logic is a standalone build, so the root repository declarations do not reach it.
// Specify here where to fetch the Kotlin dependencies and Gradle plugins that kotlin-dsl uses.
repositories {
    mavenCentral()
    gradlePluginPortal()
}
