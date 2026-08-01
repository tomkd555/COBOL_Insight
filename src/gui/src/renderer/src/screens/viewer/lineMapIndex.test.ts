import { describe, it, expect } from "vitest";
import type { LineMapEntry } from "../../../../shared/engine-api";
import {
  buildLineMapIndex,
  linkFromCobolLine,
  linkFromGeneratedLine,
  mappingKindLabel,
  notedCobolLines,
  notedGeneratedLines,
  translationNotes,
} from "./lineMapIndex";

/** LINE_MAP の1行を組む。行範囲以外の列は既定値で埋める。 */
function entry(part: Partial<LineMapEntry> & Pick<LineMapEntry, "id">): LineMapEntry {
  return {
    cobolLineStart: 1,
    cobolLineEnd: 1,
    genFile: "syk001.py",
    genLineStart: 1,
    genLineEnd: 1,
    kind: "1:1",
    note: "",
    anchorId: "",
    ...part,
  };
}

/**
 * 逐語対訳の対応表。engine の LINE_MAP.kind は対応の多重度("1:1"/"1:N"/"N:1")を持つ。
 * COBOL 20 行が生成 40〜45 行と 60〜61 行の2箇所へ対応し(1:N・重なりを含む)、
 * COBOL 30〜32 行が生成 80 行の1行へまとまる(N:1)。
 */
const ENTRIES: readonly LineMapEntry[] = [
  entry({ id: 1, cobolLineStart: 10, cobolLineEnd: 10, genLineStart: 20, genLineEnd: 20 }),
  entry({ id: 2, cobolLineStart: 20, cobolLineEnd: 22, genLineStart: 40, genLineEnd: 45, kind: "1:N", note: "GO TO は直訳できない" }),
  entry({ id: 3, cobolLineStart: 20, cobolLineEnd: 20, genLineStart: 60, genLineEnd: 61, kind: "1:N" }),
  entry({ id: 4, cobolLineStart: 30, cobolLineEnd: 32, genLineStart: 80, genLineEnd: 80, kind: "N:1", note: "EXEC SQL は実行時スタブへ置換" }),
  entry({ id: 5, cobolLineStart: 10, cobolLineEnd: 10, genFile: "Syk001.java", genLineStart: 100, genLineEnd: 104 }),
];

describe("buildLineMapIndex(生成ファイル単位の索引)", () => {
  it("指定した生成ファイルの対応だけを索引へ入れる", () => {
    const index = buildLineMapIndex(ENTRIES, "syk001.py");
    expect(index.genFile).toBe("syk001.py");
    expect(index.links.map((link) => link.id)).toEqual([1, 2, 3, 4]);
  });

  it("生成開始行・COBOL 開始行・id の順に並べる", () => {
    const shuffled = [ENTRIES[3], ENTRIES[2], ENTRIES[0], ENTRIES[1]];
    const index = buildLineMapIndex(shuffled, "syk001.py");
    expect(index.links.map((link) => link.generated.start)).toEqual([20, 40, 60, 80]);
  });

  it("行番号が 0 以下の対応は索引へ入れない", () => {
    const index = buildLineMapIndex([entry({ id: 9, cobolLineStart: 0, cobolLineEnd: 0 })], "syk001.py");
    expect(index.links).toEqual([]);
  });

  it("終端が開始より小さい対応は単一行として扱う", () => {
    const index = buildLineMapIndex(
      [entry({ id: 9, cobolLineStart: 12, cobolLineEnd: 5, genLineStart: 30, genLineEnd: 7 })],
      "syk001.py",
    );
    expect(index.links[0].cobol).toEqual({ start: 12, end: 12 });
    expect(index.links[0].generated).toEqual({ start: 30, end: 30 });
  });

  it("対応表が空でも空の索引を返す", () => {
    expect(buildLineMapIndex([], "syk001.py").links).toEqual([]);
  });
});

describe("linkFromCobolLine(COBOL 行 → 生成行範囲)", () => {
  const index = buildLineMapIndex(ENTRIES, "syk001.py");

  it("1 対 1 の対応は生成行を1行返す", () => {
    expect(linkFromCobolLine(index, 10).generatedLines).toEqual([20]);
  });

  it("複数の対応を持つ行は、すべての生成行範囲を昇順で重複なく返す", () => {
    const linked = linkFromCobolLine(index, 20);
    expect(linked.generatedLines).toEqual([40, 41, 42, 43, 44, 45, 60, 61]);
    expect(linked.links.map((link) => link.id)).toEqual([2, 3]);
  });

  it("COBOL 側の強調は対応した範囲全体へ広げる", () => {
    expect(linkFromCobolLine(index, 21).cobolLines).toEqual([20, 21, 22]);
    expect(linkFromCobolLine(index, 21).generatedLines).toEqual([40, 41, 42, 43, 44, 45]);
  });

  it("多対1の対応では COBOL 側の複数行が同じ生成行を指す", () => {
    expect(linkFromCobolLine(index, 31).cobolLines).toEqual([30, 31, 32]);
    expect(linkFromCobolLine(index, 31).generatedLines).toEqual([80]);
  });

  it("対応の無い行(範囲の間)は空を返す", () => {
    const linked = linkFromCobolLine(index, 15);
    expect(linked.cobolLines).toEqual([]);
    expect(linked.generatedLines).toEqual([]);
    expect(linked.links).toEqual([]);
  });

  it("範囲外(先頭より前・末尾より後)の行も空を返す", () => {
    expect(linkFromCobolLine(index, 1).generatedLines).toEqual([]);
    expect(linkFromCobolLine(index, 999).generatedLines).toEqual([]);
  });
});

describe("linkFromGeneratedLine(生成行 → COBOL 行範囲)", () => {
  const index = buildLineMapIndex(ENTRIES, "syk001.py");

  it("生成行から COBOL 行範囲を引く", () => {
    const linked = linkFromGeneratedLine(index, 43);
    expect(linked.cobolLines).toEqual([20, 21, 22]);
    expect(linked.generatedLines).toEqual([40, 41, 42, 43, 44, 45]);
  });

  it("多対1では生成 1 行が COBOL 3 行を指す", () => {
    expect(linkFromGeneratedLine(index, 80).cobolLines).toEqual([30, 31, 32]);
  });

  it("対応の無い生成行は空を返す", () => {
    expect(linkFromGeneratedLine(index, 50).cobolLines).toEqual([]);
  });

  it("他の生成ファイルの対応は引かない", () => {
    expect(linkFromGeneratedLine(index, 100).cobolLines).toEqual([]);
    expect(linkFromGeneratedLine(buildLineMapIndex(ENTRIES, "Syk001.java"), 100).cobolLines).toEqual([10]);
  });
});

describe("translationNotes(直訳不能の注記)", () => {
  const index = buildLineMapIndex(ENTRIES, "syk001.py");

  it("note が非空の対応だけを、生成行順に返す", () => {
    expect(translationNotes(index)).toEqual([
      { note: "GO TO は直訳できない", cobol: { start: 20, end: 22 }, generated: { start: 40, end: 45 }, kind: "1:N" },
      { note: "EXEC SQL は実行時スタブへ置換", cobol: { start: 30, end: 32 }, generated: { start: 80, end: 80 }, kind: "N:1" },
    ]);
  });

  it("注記のある行を COBOL 側・生成側の双方で数え上げる", () => {
    expect(notedCobolLines(index)).toEqual([20, 21, 22, 30, 31, 32]);
    expect(notedGeneratedLines(index)).toEqual([40, 41, 42, 43, 44, 45, 80]);
  });

  it("注記が無ければ空を返す", () => {
    const plain = buildLineMapIndex([entry({ id: 1 })], "syk001.py");
    expect(translationNotes(plain)).toEqual([]);
    expect(notedCobolLines(plain)).toEqual([]);
  });
});

describe("mappingKindLabel(対応の多重度)", () => {
  it("engine の 1:1 / 1:N / N:1 を日本語へ写す", () => {
    expect(mappingKindLabel("1:1")).toBe("1対1");
    expect(mappingKindLabel("1:N")).toBe("1対多");
    expect(mappingKindLabel("N:1")).toBe("多対1");
  });

  it("未知の値はそのまま返す", () => {
    expect(mappingKindLabel("STATEMENT")).toBe("STATEMENT");
  });
});
