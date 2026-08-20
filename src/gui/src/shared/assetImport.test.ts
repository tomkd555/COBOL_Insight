import { describe, it, expect } from "vitest";
import { ASSET_KIND_SPECS, assetKindSpec, importRelPath, normalizeImportDir } from "./assetImport";

/** 制御文字1つ。ソースへ直に置くと編集の途中で失われるため、符号位置から組む。 */
const CONTROL = String.fromCharCode(1);

describe("assetKindSpec(種別の規約)", () => {
  it("一覧に並べた種別をすべて引ける", () => {
    for (const spec of ASSET_KIND_SPECS) {
      expect(assetKindSpec(spec.kind)).toBe(spec);
    }
  });

  it("画面に出す種別名を持つ", () => {
    expect(assetKindSpec("copybook").label).toBe("コピー句");
  });
});

describe("normalizeImportDir(保存先フォルダ)", () => {
  it("空文字と区切りだけの入力は資産フォルダの直下を表す", () => {
    expect(normalizeImportDir("")).toBe("");
    expect(normalizeImportDir("  ")).toBe("");
    expect(normalizeImportDir("/")).toBe("");
    expect(normalizeImportDir("./")).toBe("");
  });

  it("区切りを / へそろえ、前後の区切りを落とす", () => {
    expect(normalizeImportDir("src\\cobol\\")).toBe("src/cobol");
    expect(normalizeImportDir("/src/cobol/")).toBe("src/cobol");
  });

  it("上位へ抜ける段を拒む", () => {
    expect(normalizeImportDir("..")).toBeNull();
    expect(normalizeImportDir("src/../..")).toBeNull();
  });

  it("Windows が扱えない記号と制御文字を拒む", () => {
    expect(normalizeImportDir("C:src")).toBeNull();
    expect(normalizeImportDir("src|cobol")).toBeNull();
    expect(normalizeImportDir(`src${CONTROL}cobol`)).toBeNull();
  });

  it("空白または点で終わる段を拒む", () => {
    expect(normalizeImportDir("src.")).toBeNull();
    expect(normalizeImportDir("src /cobol")).toBeNull();
  });
});

describe("importRelPath(取込先の相対パス)", () => {
  it("既定の保存先は資産フォルダの直下である", () => {
    expect(importRelPath("cobol", "", "SYK001")).toBe("SYK001");
    expect(importRelPath("jcl", "", "SYKJOB1.jcl")).toBe("SYKJOB1.jcl");
  });

  it("指定した相対パスの下へ置く", () => {
    expect(importRelPath("cobol", "src/cobol", "SYK001.cbl")).toBe("src/cobol/SYK001.cbl");
  });

  it("コピー句にだけ .cpy を補う(大小を問わない)", () => {
    expect(importRelPath("copybook", "", "SYKCPY1")).toBe("SYKCPY1.cpy");
    expect(importRelPath("copybook", "", "SYKCPY1.cpy")).toBe("SYKCPY1.cpy");
    expect(importRelPath("copybook", "", "SYKCPY1.CPY")).toBe("SYKCPY1.CPY");
    expect(importRelPath("cobol", "", "SYK001")).toBe("SYK001");
  });

  it("前後の空白を落とす", () => {
    expect(importRelPath("cobol", "  copy  ", "  SYK001  ")).toBe("copy/SYK001");
  });

  it("空の名前を拒む", () => {
    expect(importRelPath("cobol", "", "")).toBeNull();
    expect(importRelPath("cobol", "", "   ")).toBeNull();
  });

  it("名前にパス区切りを含めさせない(保存先は別の欄で指定する)", () => {
    expect(importRelPath("cobol", "", "../SYK001")).toBeNull();
    expect(importRelPath("cobol", "", "sub/SYK001")).toBeNull();
    expect(importRelPath("cobol", "", "sub\\SYK001")).toBeNull();
    expect(importRelPath("cobol", "", "C:SYK001")).toBeNull();
  });

  it("Windows が扱えない記号と制御文字を含む名前を拒む", () => {
    const names = [
      "SYK*001",
      "SYK?001",
      'SYK"001',
      "SYK<001",
      "SYK>001",
      "SYK|001",
      `SYK${CONTROL}001`,
    ];
    for (const name of names) {
      expect(importRelPath("cobol", "", name)).toBeNull();
    }
  });

  it("点で終わる名前を拒む", () => {
    expect(importRelPath("cobol", "", ".")).toBeNull();
    expect(importRelPath("cobol", "", "..")).toBeNull();
    expect(importRelPath("cobol", "", "SYK001.")).toBeNull();
  });

  it("点で始まる名前を拒む(基底名の無い隠しファイルになる)", () => {
    expect(importRelPath("cobol", "", ".cbl")).toBeNull();
    expect(importRelPath("cobol", "", ".SYK001")).toBeNull();
  });

  it("保存先が正しくなければ相対パスを組まない", () => {
    expect(importRelPath("cobol", "..", "SYK001")).toBeNull();
  });
});
