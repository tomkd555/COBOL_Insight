import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("jp.cobolinsight.java-conventions")
    application
}

application {
    mainClass.set("jp.cobolinsight.app.cli.Main")
}

dependencies {
    implementation(project(":engine:core"))
    implementation(project(":engine:analysis"))
    implementation(project(":engine:rules"))
    implementation(project(":engine:transpile"))
    // Frontends are wired explicitly in app (no ServiceLoader); their parser libraries stay on
    // their own compile classpaths because each declares them as implementation.
    implementation(project(":engine:cobol-frontend"))
    implementation(project(":engine:jcl-frontend"))
    implementation(project(":engine:sql-frontend"))
    implementation(project(":engine:bms-frontend"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    // Call-graph SVG/PNG rendering runs viz.js on GraalJS (Nashorn is gone since Java 15).
    implementation("guru.nidi:graphviz-java:0.18.1")
    runtimeOnly("org.graalvm.js:js:24.2.1")
}

// Windows app-image with an embedded runtime, built from the installDist output. Output is under build/.
val jpackageAppImageDir = layout.buildDirectory.dir("jpackage/app-image")

tasks.register<Exec>("jpackageAppImage") {
    group = "distribution"
    description = "Builds the Windows app-image (embedded runtime) with jpackage from installDist."
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
            "--main-jar", "app-${project.version}.jar",
            "--main-class", "jp.cobolinsight.app.cli.Main",
            "--dest", outDir.get().asFile.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            // jdk.charsets carries x-IBM930/x-IBM939 (EBCDIC); without it the packaged image cannot
            // decode Japanese EBCDIC sources. java.sql is sqlite-jdbc, java.desktop is graphviz-java.
            "--add-modules", "java.base,java.logging,java.sql,java.xml,java.naming,java.desktop,java.scripting,jdk.charsets,jdk.unsupported",
            // Console launcher: this is a CLI, not a windowed application.
            "--win-console"
        )
    })
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
