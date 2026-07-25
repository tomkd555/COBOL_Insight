import { describe, it, expect } from "vitest";
import type { SourceTextResult, TranspileGeneratedFile } from "../../../../shared/engine-api";
import { buildLineMapIndex, linkFromCobolLine } from "./lineMapIndex";
import {
  LINE_CLASS,
  availableGeneratedLanguages,
  buildLineDecorations,
  generatedFilesFor,
  generatedLanguageOf,
  identificationRanges,
  isTranspileTarget,
  linkSummary,
  originScreen,
  selectGeneratedFile,
  toDocument,
  transpileOutDir,
  viewerFileOptions,
} from "./viewerModel";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";

const FILES: readonly TranspileGeneratedFile[] = [
  { name: "syk001.py", language: "python", text: "print(1)\n" },
  { name: "Syk001.java", language: "java", text: "class Syk001 {}\n" },
  { name: "syk001_record.py", language: "python", text: "REC = 1\n" },
];

function sourceText(part: Partial<SourceTextResult> = {}): SourceTextResult {
  return { text: "AAA\nBBB\n", codepage: "UTF-8", truncated: false, unsupported: false, ...part };
}

describe("toDocument(ソース本文の取得状態)", () => {
  it("復号できた本文は原文のまま保つ(Monaco へそのまま載せる)", () => {
    expect(toDocument(sourceText())).toEqual({
      status: "ready",
      text: "AAA\nBBB\n",
      codepage: "UTF-8",
      truncated: false,
    });
  });

  it("復号非対応はコードページ付きで示し、本文を空と混同しない", () => {
    expect(toDocument(sourceText({ text: "", codepage: "x-IBM930", unsupported: true }))).toEqual({
      status: "unsupported",
      codepage: "x-IBM930",
    });
  });

  it("打ち切りの有無を保つ", () => {
    expect(toDocument(sourceText({ truncated: true })).status).toBe("ready");
    const document = toDocument(sourceText({ truncated: true }));
    expect(document.status === "ready" && document.truncated).toBe(true);
  });
});

describe("transpileOutDir(逐語対訳の出力先)", () => {
  it("SQLite と同じフォルダの transpile を出力先にする", () => {
    expect(transpileOutDir("C:\\proj\\cobol-insight.db")).toBe("C:\\proj\\transpile");
  });

  it("スラッシュ区切りのパスでも区切りを保つ", () => {
    expect(transpileOutDir("/home/user/proj/cobol-insight.db")).toBe("/home/user/proj/transpile");
  });

  it("フォルダを持たないパスでは相対名を返す", () => {
    expect(transpileOutDir("cobol-insight.db")).toBe("transpile");
  });
});

describe("生成物の言語と選択", () => {
  it("画面の言語切替を engine の生成言語へ写す", () => {
    expect(generatedLanguageOf("py")).toBe("python");
    expect(generatedLanguageOf("java")).toBe("java");
  });

  it("指定した言語の生成物だけを名前順に返す", () => {
    expect(generatedFilesFor(FILES, "python").map((file) => file.name)).toEqual([
      "syk001.py",
      "syk001_record.py",
    ]);
    expect(generatedFilesFor(FILES, "java").map((file) => file.name)).toEqual(["Syk001.java"]);
  });

  it("生成物が無い言語では空を返す", () => {
    expect(generatedFilesFor([], "python")).toEqual([]);
  });

  it("指定が無ければ先頭の生成物を選ぶ", () => {
    expect(selectGeneratedFile(FILES, "python", null)?.name).toBe("syk001.py");
  });

  it("指定した名前の生成物があればそれを選ぶ", () => {
    expect(selectGeneratedFile(FILES, "python", "syk001_record.py")?.name).toBe("syk001_record.py");
  });

  it("指定した名前が他の言語のものなら、その言語の先頭へ戻す", () => {
    expect(selectGeneratedFile(FILES, "python", "Syk001.java")?.name).toBe("syk001.py");
  });

  it("生成物が無ければ null を返す", () => {
    expect(selectGeneratedFile([], "python", null)).toBeNull();
  });

  it("生成された言語を Python → Java の順で列挙する", () => {
    expect(availableGeneratedLanguages(FILES)).toEqual(["python", "java"]);
    expect(availableGeneratedLanguages([FILES[1]])).toEqual(["java"]);
    expect(availableGeneratedLanguages([])).toEqual([]);
  });
});

describe("identificationRanges(識別欄 73〜80桁)", () => {
  it("72桁を超える行だけを、73桁目から行末までの範囲として返す", () => {
    const text = ["short", `${"A".repeat(72)}ID000010`, "x".repeat(72)].join("\n");
    expect(identificationRanges(text, "Shift_JIS")).toEqual([
      { line: 2, startColumn: 73, endColumn: 81 },
    ]);
  });

  it("どの行も72桁以内なら空を返す", () => {
    expect(identificationRanges("AAA\nBBB", "Shift_JIS")).toEqual([]);
  });

  it("空の本文でも空を返す", () => {
    expect(identificationRanges("", "Shift_JIS")).toEqual([]);
  });

  it("Shift_JIS の日本語混在行では、桁をバイトで数えて識別欄を求める", () => {
    // ASCII 59 文字 + 全角 10 文字 = 69 文字・79 バイト。72 バイトに収まるのは 65 文字である。
    const line = `${"A".repeat(59)}${"資".repeat(10)}`;
    expect(identificationRanges(line, "Shift_JIS")).toEqual([
      { line: 1, startColumn: 66, endColumn: 70 },
    ]);
  });

  it("UTF-8 の日本語混在行では 3 バイト換算で識別欄を求める", () => {
    const line = `${"A".repeat(59)}${"資".repeat(10)}`;
    expect(identificationRanges(line, "UTF-8")).toEqual([
      { line: 1, startColumn: 64, endColumn: 70 },
    ]);
  });

  it("日本語を含んでも 72 バイト以内の行には識別欄を付けない", () => {
    const line = `${"A".repeat(52)}${"資".repeat(10)}`;
    expect(identificationRanges(line, "Shift_JIS")).toEqual([]);
  });
});

describe("viewerFileOptions・isTranspileTarget(表示対象の資産)", () => {
  it("資産一覧の相対パスをそのまま選択肢にする", () => {
    expect(viewerFileOptions(SAMPLE_INVENTORY).map((option) => option.value)).toEqual([
      "bms/SYKMAP1.bms",
      "cobol/SYK001.cbl",
      "cobol/SYK002.cbl",
      "cobol/SYKENC1.cbl",
      "copybook/SYKCPY1.cpy",
      "jcl/SYKD010.jcl",
    ]);
  });

  it("逐語対訳の対象は COBOL 本体(NODE.type=PROGRAM)に限る", () => {
    const program = SAMPLE_INVENTORY.find((item) => item.path === "cobol/SYK001.cbl");
    const copybook = SAMPLE_INVENTORY.find((item) => item.path === "copybook/SYKCPY1.cpy");
    expect(isTranspileTarget(program ?? null)).toBe(true);
    expect(isTranspileTarget(copybook ?? null)).toBe(false);
    expect(isTranspileTarget(null)).toBe(false);
  });
});

describe("originScreen(ジャンプ元への戻り導線)", () => {
  it("ジャンプ文言の先頭にある画面名から遷移元を引く", () => {
    expect(originScreen("指摘一覧 から cobol/SYK001.cbl:85 へジャンプ")).toBe("findings");
    expect(originScreen("SQL助言 から cobol/SYK007.cbl:84 へジャンプ")).toBe("sql");
    expect(originScreen("呼出関係図 から cobol/SYK002.cbl を表示")).toBe("graph");
  });

  it("画面名で始まらない文言と未設定では戻り先を持たない", () => {
    expect(originScreen("どこからか")).toBeNull();
    expect(originScreen(null)).toBeNull();
  });
});

describe("linkSummary(相互ハイライトの状態)", () => {
  const index = buildLineMapIndex(
    [
      { id: 1, cobolLineStart: 20, cobolLineEnd: 22, genFile: "syk001.py", genLineStart: 40, genLineEnd: 45, kind: "1:N", note: "", anchorId: "" },
      { id: 2, cobolLineStart: 20, cobolLineEnd: 20, genFile: "syk001.py", genLineStart: 60, genLineEnd: 60, kind: "1:1", note: "", anchorId: "" },
    ],
    "syk001.py",
  );

  it("強調している行数を両ペインについて示す", () => {
    const linked = linkFromCobolLine(index, 20);
    expect(linkSummary(linked.cobolLines, linked.generatedLines)).toBe(
      "対応行を強調中 ― COBOL 3 行 ↔ 生成 7 行",
    );
  });

  it("対応が無い行はその旨を示す", () => {
    const linked = linkFromCobolLine(index, 5);
    expect(linkSummary(linked.cobolLines, linked.generatedLines)).toBe(
      "カーソル行に対応する行はない（逐語対訳の対応表に無い行）",
    );
  });
});

describe("buildLineDecorations(Monaco へ渡す装飾)", () => {
  it("注記行・対応行・ジャンプ先の行を、後の装飾が上へ重なる順に並べる", () => {
    const decorations = buildLineDecorations({
      notedLines: [3],
      linkedLines: [5, 6],
      focusLine: 5,
      identification: [],
    });
    expect(decorations.map((decoration) => [decoration.range.startLineNumber, decoration.options.className])).toEqual([
      [3, LINE_CLASS.noted],
      [5, LINE_CLASS.linked],
      [6, LINE_CLASS.linked],
      [5, LINE_CLASS.focus],
    ]);
    expect(decorations.every((decoration) => decoration.options.isWholeLine === true)).toBe(true);
  });

  it("識別欄は行内の桁範囲として装飾する", () => {
    const decorations = buildLineDecorations({
      notedLines: [],
      linkedLines: [],
      focusLine: null,
      identification: [{ line: 2, startColumn: 73, endColumn: 81 }],
    });
    expect(decorations).toEqual([
      {
        range: { startLineNumber: 2, startColumn: 73, endLineNumber: 2, endColumn: 81 },
        options: { inlineClassName: LINE_CLASS.identification },
      },
    ]);
  });

  it("強調も注記も無ければ装飾を出さない", () => {
    expect(
      buildLineDecorations({ notedLines: [], linkedLines: [], focusLine: null, identification: [] }),
    ).toEqual([]);
  });
});
