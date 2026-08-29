import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("jp.cobolinsight.java-conventions")
    application
}

application {
    mainClass.set("jp.cobolinsight.cli.Main")
}

// installDist成果物(bin/lib)を入力に、内蔵JRE同梱のWindows app-imageをjpackageで生成する。
// 出力はbuild配下(git追跡外)。
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
            "--app-version", project.version.toString(),
            "--vendor", "COBOL Insight Project",
            "--input", install.resolve("lib").absolutePath,
            "--main-jar", "cli-${project.version}.jar",
            "--main-class", "jp.cobolinsight.cli.Main",
            "--dest", outDir.get().asFile.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            // jdk.charsets carries x-IBM930/x-IBM939 (EBCDIC); without it the packaged image cannot
            // decode Japanese EBCDIC sources. java.sql is sqlite-jdbc, java.desktop is graphviz-java.
            "--add-modules", "java.base,java.logging,java.sql,java.xml,java.naming,java.desktop,java.scripting,jdk.charsets,jdk.unsupported",
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
    // transpileはTranspileRunnerが逐語対訳器を直接呼ぶ(SPIではない)ため、コンパイル依存とする。
    implementation(project(":engine:transpile"))
    implementation("info.picocli:picocli:4.7.7")
    // callgraphのSVG/PNG生成。graphviz-javaのJVM内実行(viz.js)にJSエンジンとしてGraalJSを使う
    // (graphviz-javaのNashorn経路はJava 15以降で使えないため、JDK 21ではGraalJS経路が必須)。
    implementation("guru.nidi:graphviz-java:0.18.1")
    runtimeOnly("org.graalvm.js:js:24.2.1")

    // fixはFixRunnerがバイトスプライス適用器・ノーマライザ・diffを直接呼ぶため、コンパイル依存とする。
    implementation(project(":engine:fix"))
    // encodingはFixRunnerが原本を再復号してByteOffsetTable付きDecodedSourceを得るため、コンパイル依存とする。
    implementation(project(":engine:encoding"))

    // ServiceLoaderがパイプラインを成立させるため、実装各モジュールを実行時クラスパスへ同梱する。
    runtimeOnly(project(":engine:cobol-frontend"))
    runtimeOnly(project(":engine:jcl-frontend"))
    runtimeOnly(project(":engine:sql-frontend"))
}

// Fails the build when the GUI package.json version drifts from gradle.properties (the single source).
val checkVersion = tasks.register("checkVersion") {
    group = "verification"
    val packageJson = rootProject.layout.projectDirectory.file("src/gui/package.json")
    val expected = project.version.toString()
    inputs.file(packageJson)
    doLast {
        val actual = Regex("\"version\"[ 	]*:[ 	]*\"([^\"]+)\"")
            .find(packageJson.asFile.readText())?.groupValues?.get(1)
        require(actual == expected) { "src/gui/package.json version is $actual but gradle.properties says $expected" }
    }
}
tasks.named("check") { dependsOn(checkVersion) }
