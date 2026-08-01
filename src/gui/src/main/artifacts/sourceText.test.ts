import { describe, it, expect, vi } from "vitest";
import { resolve } from "node:path";
import { decodeSourceText, readSourceText, resolveWithinInputDir } from "./sourceText";

/** Shift_JIS の「資産」。TextDecoder("shift_jis") で復号できることの確認に用いる。 */
const SJIS_SHISAN = new Uint8Array([0x8e, 0x91, 0x8e, 0x59]);

function utf8(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

describe("resolveWithinInputDir(パス境界検査)", () => {
  it("相対パスを資産フォルダ基準の絶対パスへ解決する", () => {
    expect(resolveWithinInputDir("C:/assets", "cobol/SYK001.cbl")).toBe(
      resolve("C:/assets", "cobol/SYK001.cbl"),
    );
  });

  it("資産フォルダ配下の絶対パスをそのまま受ける", () => {
    const abs = resolve("C:/assets", "cobol/SYK001.cbl");
    expect(resolveWithinInputDir("C:/assets", abs)).toBe(abs);
  });

  it("上位へ抜ける相対パスを拒む", () => {
    expect(resolveWithinInputDir("C:/assets", "../secret.txt")).toBeNull();
    expect(resolveWithinInputDir("C:/assets", "cobol/../../secret.txt")).toBeNull();
  });

  it("資産フォルダ外の絶対パスを拒む", () => {
    expect(resolveWithinInputDir("C:/assets", resolve("C:/other/secret.txt"))).toBeNull();
  });

  it("資産フォルダ自身を拒む(ファイルではない)", () => {
    expect(resolveWithinInputDir("C:/assets", ".")).toBeNull();
    expect(resolveWithinInputDir("C:/assets", resolve("C:/assets"))).toBeNull();
  });
});

describe("decodeSourceText(復号)", () => {
  it("Shift_JIS のバイト列を復号する", () => {
    const result = decodeSourceText(SJIS_SHISAN, "Shift_JIS");
    expect(result).toEqual({
      text: "資産",
      codepage: "Shift_JIS",
      truncated: false,
      unsupported: false,
    });
  });

  it("検出コードページの別名(MS932/CP932/SJIS)も Shift_JIS として復号する", () => {
    for (const name of ["MS932", "CP932", "SJIS", "windows-31j"]) {
      expect(decodeSourceText(SJIS_SHISAN, name).text).toBe("資産");
    }
  });

  it("UTF-8 のバイト列を復号し BOM を落とす", () => {
    const withBom = new Uint8Array([0xef, 0xbb, 0xbf, ...utf8("MOVE A TO B")]);
    const result = decodeSourceText(withBom, "UTF-8");
    expect(result.text).toBe("MOVE A TO B");
    expect(result.codepage).toBe("UTF-8");
    expect(result.unsupported).toBe(false);
  });

  it("EBCDIC(CP930/CP939)は復号せず非対応として返す", () => {
    for (const name of ["CP930", "CP939", "IBM-930", "ebcdic"]) {
      const result = decodeSourceText(SJIS_SHISAN, name);
      expect(result.unsupported).toBe(true);
      expect(result.text).toBe("");
      expect(result.codepage).toBe(name);
    }
  });

  it("コードページ不明(検出失敗)は非対応として返す", () => {
    const result = decodeSourceText(SJIS_SHISAN, null);
    expect(result).toEqual({ text: "", codepage: "不明", truncated: false, unsupported: true });
  });

  it("maxLines を超える行は落として truncated を立てる", () => {
    const bytes = utf8("1行\n2行\n3行\n4行\n5行\n6行\n");
    const result = decodeSourceText(bytes, "UTF-8", 5);
    expect(result.text).toBe("1行\n2行\n3行\n4行\n5行");
    expect(result.truncated).toBe(true);
  });

  it("行数が maxLines 以下なら truncated を立てない(末尾改行だけの空行は数えない)", () => {
    const result = decodeSourceText(utf8("1行\n2行\n3行\n"), "UTF-8", 5);
    expect(result.text).toBe("1行\n2行\n3行");
    expect(result.truncated).toBe(false);
  });

  it("CRLF を含む行を改行 LF へそろえて返す", () => {
    const result = decodeSourceText(utf8("A\r\nB\r\nC"), "UTF-8", 2);
    expect(result.text).toBe("A\nB");
    expect(result.truncated).toBe(true);
  });

  it("maxLines 省略時は全文を返す", () => {
    const result = decodeSourceText(utf8("A\nB\nC"), "UTF-8");
    expect(result.text).toBe("A\nB\nC");
    expect(result.truncated).toBe(false);
  });
});

describe("readSourceText(読取と境界検査の合成)", () => {
  /** symlink を持たないファイルシステム(実体パスは指定パスと同じ)。 */
  function plainFs(bytes: Uint8Array = SJIS_SHISAN): {
    readBytes: ReturnType<typeof vi.fn>;
    realPath: ReturnType<typeof vi.fn>;
  } {
    return {
      readBytes: vi.fn().mockResolvedValue(bytes),
      realPath: vi.fn((absPath: string) => Promise.resolve(absPath)),
    };
  }

  it("資産フォルダ配下のファイルを読んで復号する", async () => {
    const fs = plainFs();
    const result = await readSourceText(fs, {
      inputDir: "C:/assets",
      path: "cobol/SYK001.cbl",
      codepage: "Shift_JIS",
    });
    expect(fs.readBytes).toHaveBeenCalledWith(resolve("C:/assets", "cobol/SYK001.cbl"));
    expect(result.text).toBe("資産");
  });

  it("資産フォルダ外の読取を拒み、ファイルへ触れない", async () => {
    const fs = plainFs();
    await expect(
      readSourceText(fs, {
        inputDir: "C:/assets",
        path: "../secret.txt",
        codepage: "UTF-8",
      }),
    ).rejects.toThrow("資産フォルダの外");
    expect(fs.readBytes).not.toHaveBeenCalled();
  });

  it("symlink の実体が資産フォルダの外にあれば拒む", async () => {
    const link = resolve("C:/assets", "cobol/link.cbl");
    const fs = plainFs();
    fs.realPath = vi.fn((absPath: string) =>
      Promise.resolve(absPath === link ? resolve("C:/other/secret.txt") : absPath),
    );
    await expect(
      readSourceText(fs, { inputDir: "C:/assets", path: "cobol/link.cbl", codepage: "UTF-8" }),
    ).rejects.toThrow("資産フォルダの外");
    expect(fs.readBytes).not.toHaveBeenCalled();
  });

  it("資産フォルダ自身が symlink でも、実体の配下なら読む", async () => {
    const fs = plainFs(utf8("MOVE A TO B"));
    fs.realPath = vi.fn((absPath: string) =>
      Promise.resolve(absPath.replace(resolve("C:/assets"), resolve("D:/real/assets"))),
    );
    const result = await readSourceText(fs, {
      inputDir: "C:/assets",
      path: "cobol/SYK001.cbl",
      codepage: "UTF-8",
    });
    expect(fs.readBytes).toHaveBeenCalledWith(resolve("D:/real/assets", "cobol/SYK001.cbl"));
    expect(result.text).toBe("MOVE A TO B");
  });

  it("復号非対応のコードページではファイルを読まない", async () => {
    const fs = plainFs();
    const result = await readSourceText(fs, {
      inputDir: "C:/assets",
      path: "cobol/SYK001.cbl",
      codepage: "CP930",
    });
    expect(result.unsupported).toBe(true);
    expect(fs.readBytes).not.toHaveBeenCalled();
    expect(fs.realPath).not.toHaveBeenCalled();
  });

  it("maxLines を渡すと先頭 N 行で打ち切る", async () => {
    const fs = plainFs(utf8("1\n2\n3\n4\n5\n6"));
    const result = await readSourceText(fs, {
      inputDir: "C:/assets",
      path: "cobol/SYK001.cbl",
      codepage: "UTF-8",
      maxLines: 5,
    });
    expect(result.text).toBe("1\n2\n3\n4\n5");
    expect(result.truncated).toBe(true);
  });
});
