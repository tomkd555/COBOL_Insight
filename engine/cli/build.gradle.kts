import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("jp.cobolinsight.java-conventions")
    application
}

application {
    mainClass.set("jp.cobolinsight.cli.Main")
}

// cobol-frontendが依存するorg.eclipse.lsp.cobol系はmavenLocalにのみ存在する。プロジェクト依存の
// 実行時解決はcli自身が宣言したリポジトリで行われるため、cli側にも同じ宣言が要る。
repositories {
    mavenLocal {
        content {
            includeGroup("org.eclipse.lsp.cobol")
        }
    }
}

// M1配布検証: installDist成果物(bin/lib)を入力に、内蔵JRE同梱のWindows app-imageをjpackageで生成する。
// 出力はbuild配下(git追跡外)。生成物のサイズ・起動時間はengine/distribution-notes.mdに記録する。
val jpackageAppImageDir = layout.buildDirectory.dir("jpackage/app-image")

tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "installDist成果物からjpackageでWindows app-imageを生成する(内蔵JRE同梱)。"
    dependsOn("installDist")

    val installDir = tasks.named<Sync>("installDist").map { it.destinationDir }
    val outDir = jpackageAppImageDir

    doFirst {
        delete(outDir)
    }

    executable = "jpackage"
    argumentProviders.add(CommandLineArgumentProvider {
        val install = installDir.get()
        listOf(
            "--type", "app-image",
            "--name", "COBOLInsight",
            "--app-version", "0.1.0",
            "--vendor", "COBOL Insight Project",
            "--input", install.resolve("lib").absolutePath,
            "--main-jar", "cli.jar",
            "--main-class", "jp.cobolinsight.cli.Main",
            "--dest", outDir.get().asFile.absolutePath,
            // CLIのためコンソール接続の起動ランチャーを生成する(既定はGUI用の非コンソール)。
            "--win-console"
        )
    })
}

dependencies {
    implementation(project(":engine:engine-api"))
    // persistenceは保存先DAOの駆動、bms-frontendはengine-apiにSPI契約が無いBMS解析の直接呼出のため、
    // コンパイル依存とする。
    implementation(project(":engine:persistence"))
    implementation(project(":engine:bms-frontend"))
    // linkerは呼出関係グラフ構築の直接呼出のため、コンパイル依存とする。
    implementation(project(":engine:linker"))
    // rulesはlintのSourceTextIndex受け渡しとSARIF整形の直接呼出のため、コンパイル依存とする。
    implementation(project(":engine:rules"))
    // dataflowはlintがCFGを直接構築する(CfgBuilder.build)ため、コンパイル依存とする。
    implementation(project(":engine:dataflow"))
    implementation("info.picocli:picocli:4.7.7")
    // callgraphのSVG/PNG生成。graphviz-javaのJVM内実行(viz.js)にJSエンジンとしてGraalJSを使う
    // (graphviz-javaのNashorn経路はJava 15以降で使えないため、JDK 21ではGraalJS経路が必須)。
    implementation("guru.nidi:graphviz-java:0.18.1")
    runtimeOnly("org.graalvm.js:js:24.2.1")

    // ServiceLoaderがパイプラインを成立させるため、実装各モジュールを実行時クラスパスへ同梱する。
    runtimeOnly(project(":engine:encoding"))
    runtimeOnly(project(":engine:cobol-frontend"))
    runtimeOnly(project(":engine:jcl-frontend"))
    runtimeOnly(project(":engine:sql-frontend"))
    runtimeOnly(project(":engine:transpile"))
    runtimeOnly(project(":engine:fix"))
}

// M1完了条件: Che4z(EPL-2.0)とMAPA(MIT)のライセンスファイルを配布物(cli.jar)へ同梱する。
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("vendor/che4z/LICENSE.md")) {
        into("licenses/che4z")
    }
    from(rootProject.layout.projectDirectory.file("vendor/mapa/LICENSE")) {
        into("licenses/mapa")
    }
}
