import { describe, expect, it } from "vitest";
import { resolveEngineLaunch } from "./launch";

const base = {
  platform: "win32" as NodeJS.Platform,
  resourcesPath: "C:/app/resources",
  appRoot: "C:/repo/src/gui",
};

describe("resolveEngineLaunch", () => {
  it("runs the bundled app-image executable in a distribution", () => {
    const launch = resolveEngineLaunch({ ...base, isPackaged: true });
    expect(launch.command).toBe("C:\\app\\resources\\engine\\COBOLInsight.exe");
    expect(launch.prefixArgs).toEqual([]);
  });

  it("uses the platform executable name outside Windows", () => {
    const launch = resolveEngineLaunch({ ...base, isPackaged: true, platform: "linux" });
    // path.join keeps the host's separator, so only the file name is asserted here.
    expect(launch.command.endsWith("COBOLInsight")).toBe(true);
    expect(launch.command).not.toContain(".exe");
  });

  it("runs the installDist classpath through JAVA_HOME in development", () => {
    const launch = resolveEngineLaunch({
      ...base,
      isPackaged: false,
      javaHome: "C:/tools/jdk-21",
    });
    expect(launch.command).toBe("C:\\tools\\jdk-21\\bin\\java.exe");
    // The same JVM options the jpackage image is built with, so dev and the shipped app agree.
    expect(launch.prefixArgs.slice(0, 3)).toEqual([
      "-Dfile.encoding=UTF-8",
      "-XX:MaxRAMPercentage=50",
      "-Xss4m",
    ]);
    expect(launch.prefixArgs[3]).toBe("-classpath");
    // The trailing lib/* is expanded by java itself, so it must survive verbatim.
    expect(launch.prefixArgs[4]).toBe("C:\\repo\\src\\engine\\app\\build\\install\\app\\lib\\*");
    expect(launch.prefixArgs[5]).toBe("jp.cobolinsight.app.cli.Main");
  });

  it("falls back to the java on PATH when JAVA_HOME is unset or empty", () => {
    expect(resolveEngineLaunch({ ...base, isPackaged: false }).command).toBe("java.exe");
    expect(resolveEngineLaunch({ ...base, isPackaged: false, javaHome: "" }).command).toBe("java.exe");
  });
});
