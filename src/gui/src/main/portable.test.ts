import { describe, it, expect, afterEach, vi } from "vitest";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ensureWritable, resolvePortableUserData } from "./portable";

/** 書込権限の不足(Windows の ACL 拒否)を、OS の設定を変えずに再現するための切替である。 */
const writeControl = vi.hoisted(() => ({ denied: false }));

// このファイルが import する writeFileSync もモックの対象になる。テストの準備で書く分は
// denied を立てる前に済ませる。
vi.mock("node:fs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("node:fs")>();
  return {
    ...actual,
    writeFileSync: (path: string, data: string): void => {
      if (writeControl.denied) {
        throw Object.assign(new Error("EACCES: permission denied"), { code: "EACCES" });
      }
      actual.writeFileSync(path, data);
    },
  };
});

describe("resolvePortableUserData", () => {
  it("配布時は実行ファイルと同じ階層の data を返す", () => {
    const dir = resolvePortableUserData({
      isPackaged: true,
      exePath: "D:/tools/COBOLInsight/COBOL Insight.exe",
      ensureWritable: () => true,
    });
    expect(dir).toBe(join("D:/tools/COBOLInsight", "data"));
  });

  it("配布時でも data を作れなければ null を返す(既定の保存先へ委ねる)", () => {
    const dir = resolvePortableUserData({
      isPackaged: true,
      exePath: "//share/ro/COBOLInsight/COBOL Insight.exe",
      ensureWritable: () => false,
    });
    expect(dir).toBeNull();
  });

  it("書込可否の判定には data ディレクトリのパスを渡す", () => {
    const asked: string[] = [];
    resolvePortableUserData({
      isPackaged: true,
      exePath: "D:/tools/COBOLInsight/COBOL Insight.exe",
      ensureWritable: (target) => {
        asked.push(target);
        return true;
      },
    });
    expect(asked).toEqual([join("D:/tools/COBOLInsight", "data")]);
  });

  it("開発実行では null を返し、リポジトリ配下へ書かない", () => {
    const dir = resolvePortableUserData({
      isPackaged: false,
      exePath: "C:/repo/gui/node_modules/electron/dist/electron.exe",
      ensureWritable: () => true,
    });
    expect(dir).toBeNull();
  });

  it("空白を含むパスへ展開しても実行ファイルと同じ階層を指す", () => {
    const dir = resolvePortableUserData({
      isPackaged: true,
      exePath: "C:/Program Files/COBOL Insight/COBOL Insight.exe",
      ensureWritable: () => true,
    });
    expect(dir).toBe(join("C:/Program Files/COBOL Insight", "data"));
  });
});

describe("ensureWritable", () => {
  const workDirs: string[] = [];

  const makeWorkDir = (): string => {
    const dir = mkdtempSync(join(tmpdir(), "cobol-insight-portable-"));
    workDirs.push(dir);
    return dir;
  };

  afterEach(() => {
    writeControl.denied = false;
    for (const dir of workDirs) {
      rmSync(dir, { recursive: true, force: true });
    }
    workDirs.length = 0;
  });

  it("書込可能な場所では true を返し、試し書きの痕跡を残さない", () => {
    const dir = join(makeWorkDir(), "data");
    expect(ensureWritable(dir)).toBe(true);
    expect(readdirSync(dir)).toEqual([]);
  });

  it("同名のファイルがあって作成できない場合は false を返し、そのファイルを保つ", () => {
    const path = join(makeWorkDir(), "data");
    writeFileSync(path, "既存の内容");
    expect(ensureWritable(path)).toBe(false);
    expect(readFileSync(path, "utf8")).toBe("既存の内容");
  });

  it("既存のディレクトリを再び判定しても中身を消さない", () => {
    const dir = join(makeWorkDir(), "data");
    expect(ensureWritable(dir)).toBe(true);
    writeFileSync(join(dir, "settings.json"), "{}");
    expect(ensureWritable(dir)).toBe(true);
    expect(existsSync(join(dir, "settings.json"))).toBe(true);
  });

  it("作成はできても書き込めない場合は false を返し、作成したディレクトリを残さない", () => {
    const dir = join(makeWorkDir(), "data");
    writeControl.denied = true;
    expect(ensureWritable(dir)).toBe(false);
    expect(existsSync(dir)).toBe(false);
  });

  it("既存のディレクトリへ書き込めない場合は false を返し、そのディレクトリと中身を保つ", () => {
    const dir = join(makeWorkDir(), "data");
    expect(ensureWritable(dir)).toBe(true);
    writeFileSync(join(dir, "settings.json"), "{}");
    writeControl.denied = true;
    expect(ensureWritable(dir)).toBe(false);
    expect(existsSync(join(dir, "settings.json"))).toBe(true);
  });
});
