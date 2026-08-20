import { describe, it, expect } from "vitest";
import { emptyUserRules, normalizeUserRule } from "../../shared/userRules";
import type { UserRuleFileSystem } from "./userRules";
import { readUserRules, writeUserRules } from "./userRules";

const MINIMAL = normalizeUserRule({
  id: "U001",
  name: "試験",
  pattern: "GOBACK",
  message: "検出した",
});

function fakeFs(
  files: Record<string, string>,
): UserRuleFileSystem & { files: Record<string, string> } {
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

describe("readUserRules", () => {
  it("未作成のファイルは空の定義を返す", async () => {
    await expect(readUserRules(fakeFs({}), "user-rules.json")).resolves.toEqual(emptyUserRules());
  });

  it("空のファイルも空の定義として扱う", async () => {
    const fs = fakeFs({ "user-rules.json": "   " });
    await expect(readUserRules(fs, "user-rules.json")).resolves.toEqual(emptyUserRules());
  });

  it("書かれた定義を読み戻す", async () => {
    const fs = fakeFs({ "user-rules.json": JSON.stringify({ version: 1, rules: [MINIMAL] }) });
    const file = await readUserRules(fs, "user-rules.json");
    expect(file.rules[0].id).toBe("U001");
  });

  /**
   * 壊れた定義ファイルで IPC を落とさない。中身の誤りは、engine が同じファイルを読んだときの
   * 注意としてルールの一覧に出る。
   */
  it("壊れた JSON は空の定義として扱い、例外にしない", async () => {
    const fs = fakeFs({ "user-rules.json": "{" });
    await expect(readUserRules(fs, "user-rules.json")).resolves.toEqual(emptyUserRules());
  });

  it("読み取りに失敗しても空の定義を返す", async () => {
    const fs: UserRuleFileSystem = {
      exists: () => Promise.resolve(true),
      readText: () => Promise.reject(new Error("EACCES")),
      writeText: () => Promise.resolve(),
    };
    await expect(readUserRules(fs, "user-rules.json")).resolves.toEqual(emptyUserRules());
  });
});

describe("writeUserRules", () => {
  it("字下げした JSON を書き、読み戻せる", async () => {
    const fs = fakeFs({});
    await writeUserRules(fs, "user-rules.json", { version: 1, rules: [MINIMAL] });
    const text = fs.files["user-rules.json"];
    expect(text).toContain('"id": "U001"');
    expect(text.endsWith("\n")).toBe(true);
    await expect(readUserRules(fs, "user-rules.json")).resolves.toEqual({
      version: 1,
      rules: [MINIMAL],
    });
  });

  it("読めない定義は書き出さない", async () => {
    const fs = fakeFs({});
    await writeUserRules(fs, "user-rules.json", {
      version: 1,
      rules: [MINIMAL, { id: "U002" } as never],
    });
    expect(JSON.parse(fs.files["user-rules.json"]).rules).toHaveLength(1);
  });
});
