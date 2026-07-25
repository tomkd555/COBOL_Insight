import { describe, it, expect } from "vitest";
import { join } from "node:path";
import { resolvePortableUserData } from "./portable";

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
});
