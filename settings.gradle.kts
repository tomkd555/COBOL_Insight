pluginManagement {
    // The convention plugin jp.cobolinsight.java-conventions is built from this repository.
    includeBuild("src/gradle/build-logic")
}

dependencyResolutionManagement {
    // Every repository is declared here. A repositories {} block in a module is a build failure,
    // which is what keeps mavenLocal() and machine-specific resolution out of the tree.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // In-tree Maven repository for the patched Che4z engine jar (see docs/vendor-che4z.md).
        maven {
            name = "vendor"
            url = uri("${rootDir}/src/engine/libs/m2")
            content { includeGroup("jp.cobolinsight.vendor") }
        }
    }
}

rootProject.name = "cobol-insight"

val engineModules = listOf(
    "engine-api",
    "encoding",
    "cobol-frontend",
    "jcl-frontend",
    "sql-frontend",
    "bms-frontend",
    "dataflow",
    "linker",
    "rules",
    "transpile",
    "fix",
    "persistence",
    "cli"
)

// The intermediate project :engine lives under src/engine, not rootDir/engine.
include("engine")
project(":engine").projectDir = file("src/engine")

engineModules.forEach { moduleName ->
    include("engine:$moduleName")
    project(":engine:$moduleName").projectDir = file("src/engine/$moduleName")
}

// Keep the repository root free of generated output: the root project's build directory goes under src/.
gradle.rootProject {
    layout.buildDirectory.set(layout.projectDirectory.dir("src/build"))
}
