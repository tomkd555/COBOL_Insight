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
}

tasks.test {
    // -Dgolden.regenerate=true rewrites the snapshots the tests of this module hold.
    System.getProperty("golden.regenerate")?.let { systemProperty("golden.regenerate", it) }
    // The benchmark and acceptance tests read the asset folders from the repository root — the
    // fixtures, the committed snapshots and the expected-findings and baseline tables alike. None
    // of it is on the test classpath, so without these an edit confined to ground truth leaves the
    // task UP-TO-DATE and the measurement that has to read it never runs.
    for (folder in listOf("samples", "samples-field", "corpus", "corpus-public",
            "corpus-constructs")) {
        inputs.dir(rootProject.layout.projectDirectory.dir(folder))
            .withPropertyName(folder)
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// The zip and tar the application plugin would add to `build` are not shipped: release/ is the one
// place a deliverable goes, and installDist is what the GUI's development launch and jpackage read.
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

// Windows app-image with an embedded runtime, built from the installDist output. It goes under
// release/ beside the GUI package that bundles it (see src/gui/electron-builder.yml).
val jpackageAppImageDir = rootProject.layout.projectDirectory.dir("release/engine")

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
            "--dest", outDir.asFile.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            // jpackage takes one --java-options per JVM option. Without these the packaged engine
            // runs on the defaults: a heap capped at 25% of the machine's memory, which a large
            // asset folder outgrows, and a 1 MB main stack, which the recursive descent over a
            // deeply nested COBOL or SQL statement overflows.
            "--java-options", "-XX:MaxRAMPercentage=50",
            "--java-options", "-Xss4m",
            // jdk.charsets carries x-IBM930/x-IBM939 (EBCDIC); without it the packaged image cannot
            // decode Japanese EBCDIC sources. java.sql is sqlite-jdbc.
            "--add-modules", "java.base,java.logging,java.sql,java.xml,java.naming,jdk.charsets,jdk.unsupported",
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
