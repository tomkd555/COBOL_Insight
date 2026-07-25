import { describe, it, expect } from "vitest";
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { runEngine, type EngineProcess, type EngineSpawn } from "./run";
import { resolveEngineLaunch } from "./launch";

/**
 * 実 engine CLI(installDist)を spawn する統合テスト。純ロジックのユニットとは層を分ける。
 * 前提: gradle :engine:cli:installDist が済み、かつ JAVA_HOME が設定されていること。
 * 前提が欠ける環境では skip する(npm test は緑のまま)。
 */

const guiRoot = resolve(__dirname, "..", "..", "..");
const repoRoot = resolve(guiRoot, "..");
const installLibDir = resolve(
  guiRoot,
  "..",
  "engine",
  "cli",
  "build",
  "install",
  "cli",
  "lib",
);

function javaExecutable(): string | null {
  const javaHome = process.env["JAVA_HOME"];
  if (javaHome === undefined || javaHome === "") {
    return null;
  }
  const exe = join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  return existsSync(exe) ? exe : null;
}

const prerequisitesMet = existsSync(installLibDir) && javaExecutable() !== null;

const nodeSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, { env: options.env, cwd: options.cwd }) as unknown as EngineProcess;

describe.runIf(prerequisitesMet)("runEngine (実 CLI 統合)", () => {
  it(
    "installDist の CLI で samples を scan し、サマリ JSON と exit 0 を受ける",
    async () => {
      const launch = resolveEngineLaunch({
        isPackaged: false,
        platform: process.platform,
        resourcesPath: "unused",
        appRoot: guiRoot,
        javaHome: process.env["JAVA_HOME"],
      });
      const workspace = mkdtempSync(join(tmpdir(), "cobol-insight-it-"));
      const result = await runEngine(
        { spawn: nodeSpawn, env: process.env },
        launch,
        {
          subcommand: "scan",
          request: {
            inputDir: resolve(repoRoot, "samples"),
            db: join(workspace, "project.db"),
          },
        },
      );

      expect(result.exitCode).toBe(0);
      expect(result.summary).not.toBeNull();
      const analyzed = result.summary?.["analyzed"];
      expect(Array.isArray(analyzed)).toBe(true);
      expect(analyzed as string[]).toContain("cobol/SYK001.cbl");
      expect(result.outputs.db).toBe(join(workspace, "project.db"));
    },
    120_000,
  );
});
