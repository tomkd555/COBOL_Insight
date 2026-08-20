import { describe, it, expect } from "vitest";
import {
  emptyRuleConfig,
  migrateDisabledRules,
  readRuleConfig,
  writeRuleConfig,
  type RuleConfigFileSystem,
} from "./ruleConfig";

const SETTINGS_PATH = "settings.json";
const CONFIG_PATH = "rules-config.json";

function fakeFs(
  files: Record<string, string>,
): RuleConfigFileSystem & { files: Record<string, string> } {
  return {
    files,
    exists: (path) => Promise.resolve(path in files),
    readText: (path) => Promise.resolve(files[path]),
    writeText: (path, text) => {
      files[path] = text;
      return Promise.resolve();
    },
  };
}

describe("readRuleConfig", () => {
  it("未作成のファイルは空の設定を返す", async () => {
    await expect(readRuleConfig(fakeFs({}), CONFIG_PATH)).resolves.toEqual(emptyRuleConfig());
  });

  it("壊れた JSON は空の設定として扱い、例外にしない", async () => {
    const fs = fakeFs({ [CONFIG_PATH]: "{" });
    await expect(readRuleConfig(fs, CONFIG_PATH)).resolves.toEqual(emptyRuleConfig());
  });

  it("文字列でない ID を落として読む", async () => {
    const fs = fakeFs({
      [CONFIG_PATH]: JSON.stringify({ version: 1, disabledRules: ["R004", 7, "S001"] }),
    });
    await expect(readRuleConfig(fs, CONFIG_PATH)).resolves.toEqual({
      version: 1,
      disabledRules: ["R004", "S001"],
    });
  });
});

describe("writeRuleConfig", () => {
  /** engine は知らない版数を誤りとして扱う。書く側が必ず受理される版数を入れる。 */
  it("版数を engine が受理する値に固定して書く", async () => {
    const fs = fakeFs({});
    await writeRuleConfig(fs, CONFIG_PATH, { version: 99, disabledRules: ["R009"] });
    expect(JSON.parse(fs.files[CONFIG_PATH])).toEqual({ version: 1, disabledRules: ["R009"] });
  });
});

describe("migrateDisabledRules", () => {
  it("旧 settings.json の disabledRules を設定ファイルへ移す", async () => {
    const fs = fakeFs({
      [SETTINGS_PATH]: JSON.stringify({
        version: 1,
        settings: { disabledRules: ["R004", "S001"], severityThreshold: "medium" },
      }),
    });
    await migrateDisabledRules(fs, SETTINGS_PATH, CONFIG_PATH);
    expect(JSON.parse(fs.files[CONFIG_PATH])).toEqual({
      version: 1,
      disabledRules: ["R004", "S001"],
    });
  });

  /** 設定ファイルが正である。古い settings.json の値で上書きしない。 */
  it("設定ファイルが既にあれば触れない", async () => {
    const existing = `${JSON.stringify({ version: 1, disabledRules: ["R010"] }, null, 2)}\n`;
    const fs = fakeFs({
      [SETTINGS_PATH]: JSON.stringify({ settings: { disabledRules: ["R004"] } }),
      [CONFIG_PATH]: existing,
    });
    await migrateDisabledRules(fs, SETTINGS_PATH, CONFIG_PATH);
    expect(fs.files[CONFIG_PATH]).toBe(existing);
  });

  it("無効にしたルールが1件も無ければ設定ファイルを作らない", async () => {
    const fs = fakeFs({ [SETTINGS_PATH]: JSON.stringify({ settings: { disabledRules: [] } }) });
    await migrateDisabledRules(fs, SETTINGS_PATH, CONFIG_PATH);
    expect(CONFIG_PATH in fs.files).toBe(false);
  });

  it("settings.json が無ければ何もしない", async () => {
    const fs = fakeFs({});
    await migrateDisabledRules(fs, SETTINGS_PATH, CONFIG_PATH);
    expect(fs.files).toEqual({});
  });

  it("壊れた settings.json は移さず、例外にしない", async () => {
    const fs = fakeFs({ [SETTINGS_PATH]: "{" });
    await migrateDisabledRules(fs, SETTINGS_PATH, CONFIG_PATH);
    expect(CONFIG_PATH in fs.files).toBe(false);
  });
});
