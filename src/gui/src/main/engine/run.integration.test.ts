import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { parseRuleCatalog } from "../../shared/ruleCatalog";
import { resolveEngineLaunch } from "./launch";
import { runEngine, type EngineProcess, type EngineSpawn } from "./run";

/**
 * Runs the real engine CLI once and parses its rule listing. This is the one test that proves the
 * launch resolution, the argument assembly, the summary extraction and the catalog parsing agree
 * with the engine as built.
 *
 * It self-skips when the development artefact or a JDK is missing, so a checkout that has not run
 * `gradlew :engine:app:installDist` still has a green test suite.
 */

/** app.getAppPath() equivalent: this file sits at src/gui/src/main/engine. */
const APP_ROOT = resolve(__dirname, "..", "..", "..");
const INSTALL_DIR = join(APP_ROOT, "..", "engine", "app", "build", "install", "app");
const JAVA_HOME = process.env["JAVA_HOME"];

const ready =
  existsSync(join(INSTALL_DIR, "lib")) &&
  JAVA_HOME !== undefined &&
  JAVA_HOME !== "" &&
  existsSync(join(JAVA_HOME, "bin"));

const engineSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, { env: options.env, cwd: options.cwd }) as unknown as EngineProcess;

describe.skipIf(!ready)("the engine CLI, run for real", () => {
  it("returns a rule catalog from `rules --json`", async () => {
    const launch = resolveEngineLaunch({
      isPackaged: false,
      platform: process.platform,
      resourcesPath: "",
      appRoot: APP_ROOT,
      javaHome: JAVA_HOME,
    });
    const result = await runEngine({ spawn: engineSpawn, env: process.env }, launch, {
      subcommand: "rules",
      request: {},
    });

    expect(result.exitCode, `engine stderr: ${result.stderr}`).toBe(0);
    const catalog = parseRuleCatalog(result.summary);
    expect(catalog.rules.length).toBeGreaterThan(0);
    // Every rule carries an id and the prose the screens display; the GUI authors none of it.
    for (const rule of catalog.rules) {
      expect(rule.id).toMatch(/^[RSU]\d+$/);
      expect(rule.name).not.toBe("");
      expect(rule.summary).not.toBe("");
    }
  }, 120_000);
});
