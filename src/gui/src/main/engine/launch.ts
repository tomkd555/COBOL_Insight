import { join } from "node:path";

/** What resolving the engine launch target needs from the runtime. */
export interface EngineLaunchInput {
  /** app.isPackaged: separates a distribution from a development run. */
  isPackaged: boolean;
  platform: NodeJS.Platform;
  /** process.resourcesPath: where the bundled app-image sits in a distribution. */
  resourcesPath: string;
  /** app.getAppPath(): points at src/gui, from which installDist is resolved in development. */
  appRoot: string;
  /** process.env.JAVA_HOME. When unset, java is taken from PATH. */
  javaHome?: string;
}

/** The spawn command plus the fixed arguments that precede the subcommand. */
export interface EngineLaunch {
  command: string;
  prefixArgs: string[];
}

/** The CLI entry point, needed when installDist is launched through java directly. */
const MAIN_CLASS = "jp.cobolinsight.app.cli.Main";

/**
 * The JVM options the jpackage image is built with (src/engine/app/build.gradle.kts). A development
 * launch repeats them, so the heap ceiling, the stack size and the encoding are the ones the shipped
 * app runs with: a large folder or a deeply nested statement must not crash in only one of the two.
 */
const JAVA_OPTIONS = ["-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=50", "-Xss4m"];

/**
 * Resolves how to start the engine CLI. Only a local child process is ever launched; there is no
 * network and no socket.
 *
 * - Packaged: the executable of the jpackage app-image bundled through extraResources, which carries
 *   its own JRE and needs none on the system.
 * - Development: the gradle `:engine:app:installDist` output, with lib/ on the classpath, run by the
 *   java from JAVA_HOME (or PATH). appRoot is src/gui, whose parent src holds engine/app/build/....
 *
 * The trailing `lib/*` on the classpath is expanded by java itself, not by a shell. spawn does not
 * go through a shell, so it must be passed in exactly this form.
 */
export function resolveEngineLaunch(input: EngineLaunchInput): EngineLaunch {
  const isWindows = input.platform === "win32";
  if (input.isPackaged) {
    const exe = isWindows ? "COBOLInsight.exe" : "COBOLInsight";
    return { command: join(input.resourcesPath, "engine", exe), prefixArgs: [] };
  }
  const installDir = join(input.appRoot, "..", "engine", "app", "build", "install", "app");
  const classpath = join(installDir, "lib", "*");
  const javaName = isWindows ? "java.exe" : "java";
  const command =
    input.javaHome !== undefined && input.javaHome !== ""
      ? join(input.javaHome, "bin", javaName)
      : javaName;
  return { command, prefixArgs: [...JAVA_OPTIONS, "-classpath", classpath, MAIN_CLASS] };
}
