import { describe, it, expect } from "vitest";
import { ASSET_KIND_SPECS, assetKindSpec, importRelPath } from "./assetImport";

describe("assetKindSpec(種別の規約)", () => {
  it("engine の走査が見るフォルダと拡張子を種別ごとに返す", () => {
    expect(assetKindSpec("cobol")).toMatchObject({ folder: "cobol", extension: ".cbl" });
    expect(assetKindSpec("copybook")).toMatchObject({ folder: "copy", extension: ".cpy" });
    expect(assetKindSpec("jcl")).toMatchObject({ folder: "jcl", extension: ".jcl" });
    expect(assetKindSpec("bms")).toMatchObject({ folder: "bms", extension: ".bms" });
  });

  it("一覧に並べた種別をすべて引ける", () => {
    for (const spec of ASSET_KIND_SPECS) {
      expect(assetKindSpec(spec.kind)).toBe(spec);
    }
  });
});

describe("importRelPath(取込先の相対パス)", () => {
  it("種別のフォルダと拡張子を付けた相対パスを返す", () => {
    expect(importRelPath("cobol", "SYK001")).toBe("cobol/SYK001.cbl");
    expect(importRelPath("copybook", "SYKCPY1")).toBe("copy/SYKCPY1.cpy");
    expect(importRelPath("jcl", "SYKJOB1")).toBe("jcl/SYKJOB1.jcl");
    expect(importRelPath("bms", "SYKMAP1")).toBe("bms/SYKMAP1.bms");
  });

  it("拡張子が既に付いていれば重ねない(大小を問わない)", () => {
    expect(importRelPath("cobol", "SYK001.cbl")).toBe("cobol/SYK001.cbl");
    expect(importRelPath("cobol", "SYK001.CBL")).toBe("cobol/SYK001.CBL");
  });

  it("前後の空白を落とす", () => {
    expect(importRelPath("cobol", "  SYK001  ")).toBe("cobol/SYK001.cbl");
  });

  it("空の名前を拒む", () => {
    expect(importRelPath("cobol", "")).toBeNull();
    expect(importRelPath("cobol", "   ")).toBeNull();
  });

  it("パス区切りを含む名前を拒む", () => {
    expect(importRelPath("cobol", "../SYK001")).toBeNull();
    expect(importRelPath("cobol", "sub/SYK001")).toBeNull();
    expect(importRelPath("cobol", "sub\\SYK001")).toBeNull();
    expect(importRelPath("cobol", "C:SYK001")).toBeNull();
  });

  it("Windows が扱えない記号と制御文字を含む名前を拒む", () => {
    const names = ["SYK*001", "SYK?001", 'SYK"001', "SYK<001", "SYK>001", "SYK|001", "SYK\u0001"];
    for (const name of names) {
      expect(importRelPath("cobol", name)).toBeNull();
    }
  });

  it("点で終わる名前を拒む", () => {
    expect(importRelPath("cobol", ".")).toBeNull();
    expect(importRelPath("cobol", "..")).toBeNull();
    expect(importRelPath("cobol", "SYK001.")).toBeNull();
  });

  it("点で始まる名前を拒む(基底名の無い隠しファイルになる)", () => {
    expect(importRelPath("cobol", ".cbl")).toBeNull();
    expect(importRelPath("cobol", ".SYK001")).toBeNull();
  });
});
