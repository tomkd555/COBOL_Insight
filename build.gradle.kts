// Root build: the one command that produces the distributable is `gradlew dist`.
// It builds the engine app-image first and then runs the GUI packaging, which consumes it.
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npm = if (isWindows) "npm.cmd" else "npm"
val guiDir = layout.projectDirectory.dir("src/gui")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "build"
    description = "Installs GUI dependencies from package-lock.json (npm ci)."
    workingDir = guiDir.asFile
    commandLine(npm, "ci")
    inputs.files(guiDir.file("package.json"), guiDir.file("package-lock.json"))
    outputs.dir(guiDir.dir("node_modules"))
    doFirst { requireNpm() }
}

val guiTest = tasks.register<Exec>("guiTest") {
    group = "verification"
    description = "Runs the GUI type check and unit tests."
    dependsOn(npmInstall)
    workingDir = guiDir.asFile
    commandLine(npm, "run", "check")
}

val dist = tasks.register<Exec>("dist") {
    group = "distribution"
    description = "Builds the engine app-image, then packages the GUI into release/ with electron-builder."
    dependsOn(":engine:app:jpackageAppImage", npmInstall)
    workingDir = guiDir.asFile
    commandLine(npm, "run", "dist")
}

fun requireNpm() {
    val found = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { File(it, npm).exists() }
    require(found) { "npm was not found on PATH. Install Node.js 20 or later." }
}
