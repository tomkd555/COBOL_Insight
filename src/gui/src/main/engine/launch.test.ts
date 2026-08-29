import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { resolveEngineLaunch } from "./launch";

describe("resolveEngineLaunch", () => {
  it("配布時(win32)は同梱の jpackage app-image exe を直接起動する", () => {
    const launch = resolveEngineLaunch({
      isPackaged: true,
      platform: "win32",
      resourcesPath: "C:/app/resources",
      appRoot: "C:/app/resources/app.asar",
    });
    expect(launch.command).toBe(join("C:/app/resources", "engine", "COBOLInsight.exe"));
    expect(launch.prefixArgs).toEqual([]);
  });

  it("配布時(非 win32)は拡張子なしの app-image ランチャーを起動する", () => {
    const launch = resolveEngineLaunch({
      isPackaged: true,
      platform: "linux",
      resourcesPath: "/app/resources",
      appRoot: "/app/resources/app.asar",
    });
    expect(launch.command).toBe(join("/app/resources", "engine", "COBOLInsight"));
  });

  it("開発時(win32)は installDist の lib を classpath に java を起動する", () => {
    const launch = resolveEngineLaunch({
      isPackaged: false,
      platform: "win32",
      resourcesPath: "unused",
      appRoot: "C:/repo/gui",
      javaHome: "C:/tools/jdk-21",
    });
    expect(launch.command).toBe(join("C:/tools/jdk-21", "bin", "java.exe"));
    const installLib = join("C:/repo/gui", "..", "engine", "app", "build", "install", "app", "lib", "*");
    expect(launch.prefixArgs).toEqual(["-classpath", installLib, "jp.cobolinsight.app.cli.Main"]);
  });

  it("開発時に JAVA_HOME 未設定なら PATH 上の java を使う", () => {
    const launch = resolveEngineLaunch({
      isPackaged: false,
      platform: "win32",
      resourcesPath: "unused",
      appRoot: "C:/repo/gui",
    });
    expect(launch.command).toBe("java.exe");
  });

  it("開発時(非 win32)は拡張子なしの java を使う", () => {
    const launch = resolveEngineLaunch({
      isPackaged: false,
      platform: "darwin",
      resourcesPath: "unused",
      appRoot: "/repo/gui",
    });
    expect(launch.command).toBe("java");
    expect(launch.prefixArgs[2]).toBe("jp.cobolinsight.app.cli.Main");
  });
});
