import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import { RULES_FILE_VERSION } from "../../shared/rulesFile";
import { resolveEngineLaunch } from "./launch";
import { listRules } from "./rules";
import { runEngine, type EngineProcess, type EngineSpawn } from "./run";

/**
 * Runs the real engine against a rule configuration file the GUI wrote. This is what proves that
 * the file the rules screens produce is the file the engine reads: an override switches a built-in
 * rule off, and a custom rule appears in the catalogue as a user-defined one.
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

/** The rule switched off, chosen because docs/rules.md uses it as the worked example. */
const DISABLED_RULE = "R008";

const CUSTOM_RULE = {
  id: "U001",
  name: "GO TO の使用",
  severity: "LOW",
  targets: ["COBOL", "COPYBOOK"],
  commands: ["LINT", "REPORT"],
  message: "GO TO を使っている: ${match}",
  match: {
    kind: "line",
    regex: "GO\\s+TO\\b",
    ignoreCase: true,
    area: "programArea",
    excludeRegex: "DEPENDING\\s+ON",
  },
};

let directory: string | null = null;

afterAll(async () => {
  if (directory !== null) {
    await rm(directory, { recursive: true, force: true });
  }
});

describe.skipIf(!ready)("the engine CLI, run for real against a rule file", () => {
  it("applies an override and adds a custom rule", async () => {
    directory = await mkdtemp(join(tmpdir(), "cobol-insight-rules-"));
    const rulesPath = join(directory, "rules.json");
    await writeFile(
      rulesPath,
      JSON.stringify(
        {
          version: RULES_FILE_VERSION,
          rules: { [DISABLED_RULE]: { enabled: false } },
          custom: [CUSTOM_RULE],
        },
        null,
        2,
      ),
      "utf-8",
    );

    const launch = resolveEngineLaunch({
      isPackaged: false,
      platform: process.platform,
      resourcesPath: "",
      appRoot: APP_ROOT,
      javaHome: JAVA_HOME,
    });
    const catalog = await listRules(
      {
        fs: { writeText: async () => undefined, remove: async () => undefined },
        tempFile: () => join(directory ?? "", "unused.json"),
        run: (request) =>
          runEngine({ spawn: engineSpawn, env: process.env }, launch, {
            subcommand: "rules",
            request,
          }),
      },
      { rulesFile: rulesPath },
    );

    expect(catalog.ruleErrors, catalog.ruleErrors.join(" | ")).toEqual([]);
    const disabled = catalog.rules.find((rule) => rule.id === DISABLED_RULE);
    expect(disabled?.enabled).toBe(false);
    expect(disabled?.defaultEnabled).toBe(true);

    const custom = catalog.rules.find((rule) => rule.id === CUSTOM_RULE.id);
    expect(custom?.source).toBe("user");
    expect(custom?.name).toBe(CUSTOM_RULE.name);
    // `detection` is written by the engine from the match object; the GUI never supplies it.
    expect(custom?.detection).not.toBe("");
  }, 120_000);
});
