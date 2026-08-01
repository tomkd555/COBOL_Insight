pluginManagement {
    // 規約プラグイン jp.cobolinsight.java-conventions を、同一リポジトリ内のビルドから解決する。
    includeBuild("src/gradle/build-logic")
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

// 中間プロジェクト :engine の位置も明示する。既定では rootDir/engine を指し、存在しない
// ディレクトリとして設定が失敗する。
include("engine")
project(":engine").projectDir = file("src/engine")

engineModules.forEach { moduleName ->
    include("engine:$moduleName")
    project(":engine:$moduleName").projectDir = file("src/engine/$moduleName")
}

// ルート直下を release・graphify-out・samples・src の 4 つに保つため、ルートプロジェクトの
// build ディレクトリ(問題レポートの出力先)も src 配下へ寄せる。
gradle.rootProject {
    layout.buildDirectory.set(layout.projectDirectory.dir("src/build"))
}
