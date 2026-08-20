import { describe, it, expect, vi } from "vitest";
import { resolve } from "node:path";
import { importSource, type ImportFileSystem } from "./importSource";
import type { ImportSourceRequest } from "../../shared/engine-api";

const INPUT_DIR = "C:/assets";

/**
 * 既存とみなすパスを与えて偽のファイルシステムを組む。書込内容は written へ残す。
 * links は接合・symlink の模擬であり、realPath がその実体パスへ解決する。
 */
function fakeFs(
  existing: string[] = [],
  links: Record<string, string> = {},
): ImportFileSystem & {
  written: Map<string, string>;
  madeDirs: string[];
} {
  const written = new Map<string, string>();
  const madeDirs: string[] = [];
  return {
    written,
    madeDirs,
    makeDir: vi.fn(async (absPath: string) => {
      madeDirs.push(absPath);
    }),
    exists: vi.fn(async (absPath: string) => existing.includes(absPath)),
    realPath: vi.fn(async (absPath: string) => links[absPath] ?? absPath),
    writeText: vi.fn(async (absPath: string, text: string) => {
      written.set(absPath, text);
    }),
  };
}

function request(overrides: Partial<ImportSourceRequest> = {}): ImportSourceRequest {
  return {
    inputDir: INPUT_DIR,
    kind: "cobol",
    destDir: "",
    fileName: "SYK001",
    lines: ["       IDENTIFICATION DIVISION.", "       PROGRAM-ID. SYK001."],
    overwrite: false,
    ...overrides,
  };
}

describe("importSource(取込の書出)", () => {
  it("保存先の既定は資産フォルダの直下であり、CRLF 区切りの UTF-8 テキストとして書き出す", async () => {
    const fs = fakeFs();
    const result = await importSource(fs, request());
    const target = resolve(INPUT_DIR, "SYK001");
    expect(result).toEqual({ status: "written", relPath: "SYK001", lineCount: 2 });
    expect(fs.written.get(target)).toBe(
      "       IDENTIFICATION DIVISION.\r\n       PROGRAM-ID. SYK001.\r\n",
    );
  });

  it("指定した相対パスの下へ置き、無ければフォルダを作る", async () => {
    const fs = fakeFs();
    const result = await importSource(fs, request({ destDir: "src/cobol" }));
    expect(result.relPath).toBe("src/cobol/SYK001");
    expect(fs.madeDirs).toEqual([resolve(INPUT_DIR, "src/cobol")]);
  });

  it("同名のファイルがあり上書きの許可が無ければ、書かずに exists を返す", async () => {
    const fs = fakeFs([resolve(INPUT_DIR, "SYK001")]);
    const result = await importSource(fs, request());
    expect(result).toEqual({ status: "exists", relPath: "SYK001", lineCount: 0 });
    expect(fs.written.size).toBe(0);
  });

  it("上書きの許可があれば同名のファイルへ書く", async () => {
    const fs = fakeFs([resolve(INPUT_DIR, "SYK001")]);
    const result = await importSource(fs, request({ overwrite: true }));
    expect(result.status).toBe("written");
    expect(fs.written.size).toBe(1);
  });

  it("拡張子を補うのはコピー句だけである", async () => {
    const fs = fakeFs();
    const jcl = await importSource(fs, request({ kind: "jcl", fileName: "SYKJOB1" }));
    expect(jcl.relPath).toBe("SYKJOB1");
    const copybook = await importSource(fs, request({ kind: "copybook", fileName: "SYKCPY1" }));
    expect(copybook.relPath).toBe("SYKCPY1.cpy");
  });

  it("資産フォルダの外へ抜けるファイル名を拒む", async () => {
    const fs = fakeFs();
    await expect(importSource(fs, request({ fileName: "../SYK001" }))).rejects.toThrow(/保存先/);
    expect(fs.written.size).toBe(0);
  });

  it("資産フォルダの外へ抜ける保存先を拒む", async () => {
    const fs = fakeFs();
    await expect(importSource(fs, request({ destDir: "../外" }))).rejects.toThrow(/保存先/);
    expect(fs.written.size).toBe(0);
  });

  it("行が1つも無い取込を拒む", async () => {
    const fs = fakeFs();
    await expect(importSource(fs, request({ lines: [] }))).rejects.toThrow(/本文/);
    expect(fs.written.size).toBe(0);
  });

  it("保存先のフォルダが接合で資産フォルダの外を指すときは書かない", async () => {
    const fs = fakeFs([], { [resolve(INPUT_DIR, "cobol")]: resolve("D:/outside") });
    await expect(importSource(fs, request({ destDir: "cobol" }))).rejects.toThrow(
      /資産フォルダの外/,
    );
    expect(fs.written.size).toBe(0);
  });

  it("接合を解いた実体パスが資産フォルダ配下であれば、その実体パスへ書く", async () => {
    const real = resolve(INPUT_DIR, "実体/cobol");
    const fs = fakeFs([], { [resolve(INPUT_DIR, "cobol")]: real });
    const result = await importSource(fs, request({ destDir: "cobol" }));
    expect(result.status).toBe("written");
    expect([...fs.written.keys()]).toEqual([resolve(real, "SYK001")]);
  });
});
