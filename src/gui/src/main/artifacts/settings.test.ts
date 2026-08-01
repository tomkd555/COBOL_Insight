import { describe, it, expect } from "vitest";
import { emptyAppSettings, type AppSettings } from "../../shared/appSettings";
import type { SettingsFileSystem } from "./settings";
import { readSettings, writeSettings } from "./settings";

const SETTINGS: AppSettings = {
  disabledRules: ["R004"],
  severityThreshold: "medium",
  defaultEncoding: "手動: Shift_JIS",
  copybookPaths: ["C:\\資産\\copybook"],
};

function fakeFs(
  files: Record<string, string>,
): SettingsFileSystem & { files: Record<string, string> } {
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

describe("readSettings", () => {
  it("未作成のファイルは空の設定を返す", async () => {
    await expect(readSettings(fakeFs({}), "settings.json")).resolves.toEqual(emptyAppSettings());
  });

  it("空のファイルも空の設定として扱う", async () => {
    const fs = fakeFs({ "settings.json": "  " });
    await expect(readSettings(fs, "settings.json")).resolves.toEqual(emptyAppSettings());
  });

  /** 設定は解析の結果を左右しない。壊れていても起動を止めない。 */
  it("壊れた JSON は空の設定として扱い、例外にしない", async () => {
    const fs = fakeFs({ "settings.json": "{" });
    await expect(readSettings(fs, "settings.json")).resolves.toEqual(emptyAppSettings());
  });

  it("読み取りに失敗しても空の設定を返す", async () => {
    const fs: SettingsFileSystem = {
      exists: () => Promise.resolve(true),
      readText: () => Promise.reject(new Error("EACCES")),
      writeText: () => Promise.resolve(),
    };
    await expect(readSettings(fs, "settings.json")).resolves.toEqual(emptyAppSettings());
  });
});

describe("writeSettings", () => {
  it("版数を添えて書き、読み戻せる", async () => {
    const fs = fakeFs({});
    await writeSettings(fs, "settings.json", SETTINGS);
    const text = fs.files["settings.json"];
    expect(JSON.parse(text).version).toBe(1);
    expect(text.endsWith("\n")).toBe(true);
    await expect(readSettings(fs, "settings.json")).resolves.toEqual(SETTINGS);
  });

  it("型の合わない値を落として書く", async () => {
    const fs = fakeFs({});
    await writeSettings(fs, "settings.json", {
      ...SETTINGS,
      disabledRules: ["R004", 7 as never],
    });
    expect(JSON.parse(fs.files["settings.json"]).settings.disabledRules).toEqual(["R004"]);
  });
});
