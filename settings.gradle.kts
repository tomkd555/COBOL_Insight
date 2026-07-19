pluginManagement {
    includeBuild("gradle/build-logic")
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

engineModules.forEach { moduleName ->
    include("engine:$moduleName")
    project(":engine:$moduleName").projectDir = file("engine/$moduleName")
}
