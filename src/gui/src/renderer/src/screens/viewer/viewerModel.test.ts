import { describe, it, expect } from "vitest";
import type {
  CopyExpansion,
  SarifFinding,
  SourceTextResult,
  TranspileGeneratedFile,
} from "../../../../shared/engine-api";
import type { CopyStatement } from "./copybookLookup";
import { buildLineMapIndex, linkFromCobolLine } from "./lineMapIndex";
import {
  COLUMN_MARKS,
  LINE_CLASS,
  LINE_END_COLUMN,
  availableGeneratedLanguages,
  buildExpansionZones,
  buildLineDecorations,
  columnLeftPx,
  copyExpansionFile,
  copyExpansionSummary,
  expansionsFor,
  findingGlyphClass,
  findingHoverText,
  findingLineClass,
  findingLinesOf,
  generatedFilesFor,
  generatedLanguageOf,
  identificationRanges,
  isTranspileTarget,
  linkSummary,
  metricsEqual,
  selectGeneratedFile,
  toDocument,
  transpileOutDir,
} from "./viewerModel";
import { SAMPLE_INVENTORY } from "../../data/__fixtures__/samples";
import { FIXTURE_CATALOG } from "../../data/__fixtures__/catalog";

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

describe("isTranspileTarget(逐語対訳の対象)", () => {
  it("対象は COBOL 本体(NODE.type=PROGRAM)に限る", () => {
    const program = SAMPLE_INVENTORY.find((item) => item.path === "cobol/SYK001.cbl");
    const copybook = SAMPLE_INVENTORY.find((item) => item.path === "copybook/SYKCPY1.cpy");
    expect(isTranspileTarget(program ?? null)).toBe(true);
    expect(isTranspileTarget(copybook ?? null)).toBe(false);
    expect(isTranspileTarget(null)).toBe(false);
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
      "カーソル行に対応する行はありません",
    );
  });
});

describe("findingLinesOf(表示中のファイルの指摘を行ごとに集約する)", () => {
  const FINDINGS: readonly SarifFinding[] = [
    { ruleId: "R009", level: "warning", message: "GO TO で制御が飛ぶ。", file: "cobol/SYK001.cbl", startLine: 12, startColumn: 1 },
    { ruleId: "R001", level: "error", message: "未初期化の参照。", file: "cobol/SYK001.cbl", startLine: 11, startColumn: 1 },
    { ruleId: "R008", level: "warning", message: "THRU 句が無い。", file: "cobol/SYK001.cbl", startLine: 12, startColumn: 8 },
    { ruleId: "R002", level: "note", message: "未使用の項目。", file: "cobol/SYK002.cbl", startLine: 11, startColumn: 1 },
  ];

  it("表示中のファイルの指摘だけを行番号昇順にまとめる", () => {
    expect(findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK001.cbl").map((line) => line.line)).toEqual([11, 12]);
    expect(findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK002.cbl").map((line) => line.line)).toEqual([11]);
    expect(findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK009.cbl")).toEqual([]);
  });

  it("同じ行の指摘は最も重い重大度で示し、件数と内訳を持つ", () => {
    const line = findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK001.cbl")[1];
    expect(line.count).toBe(2);
    // R008 は中・R009 は警告なので、重い方の中で行を示し、内訳も重い順に並べる。
    expect(line.severity).toBe("medium");
    expect(line.entries.map((entry) => entry.ruleId)).toEqual(["R008", "R009"]);
  });

  it("重大度と名称はルールカタログから引く(SARIF の level ではない)", () => {
    const line = findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK001.cbl")[0];
    expect(line.entries).toEqual([
      {
        ruleId: "R001",
        ruleName: "未初期化変数の参照",
        severity: "high",
        message: "未初期化の参照。",
      },
    ]);
  });

  it("ホバー本文は件数・記号・重大度・ルール ID・ルール名・根拠を持つ", () => {
    const text = findingHoverText(findingLinesOf(FIXTURE_CATALOG, FINDINGS, "cobol/SYK001.cbl")[1]);
    expect(text).toContain("この行の指摘 2 件");
    expect(text).toContain("◆ 中");
    expect(text).toContain("R008");
    expect(text).toContain("PERFORM単独段落名の直接指定");
    expect(text).toContain("THRU 句が無い。");
    expect(text).toContain("▲ 推奨");
  });
});

describe("buildLineDecorations(Monaco へ渡す装飾)", () => {
  it("注記行・対応行・ジャンプ先の行を、後の装飾が上へ重なる順に並べる", () => {
    const decorations = buildLineDecorations({
      notedLines: [3],
      linkedLines: [5, 6],
      focusLine: 5,
      identification: [],
      findings: [],
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
      findings: [],
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
      buildLineDecorations({
        notedLines: [],
        linkedLines: [],
        focusLine: null,
        identification: [],
        findings: [],
      }),
    ).toEqual([]);
  });

  it("指摘行は重大度の記号(グリフ)・重大度色の行装飾・ホバー本文を持つ", () => {
    const decorations = buildLineDecorations({
      notedLines: [],
      linkedLines: [],
      focusLine: null,
      identification: [],
      findings: [
        {
          line: 11,
          severity: "high",
          count: 1,
          entries: [
            { ruleId: "R001", ruleName: "未初期化変数の参照", severity: "high", message: "根拠" },
          ],
        },
      ],
    });
    expect(decorations).toHaveLength(1);
    const [decoration] = decorations;
    // 行の全幅を指す範囲にすることで、行のどこにカーソルを載せてもホバーが出る。
    expect(decoration.range).toEqual({
      startLineNumber: 11,
      startColumn: 1,
      endLineNumber: 11,
      endColumn: LINE_END_COLUMN,
    });
    expect(decoration.options.className).toBe(findingLineClass("high"));
    expect(decoration.options.glyphMarginClassName).toBe(findingGlyphClass("high"));
    expect(decoration.options.hoverMessage?.value).toContain("R001");
    expect(decoration.options.glyphMarginHoverMessage?.value).toContain("R001");
    // 1 件の行には件数を添えない(記号だけで足りる)。
    expect(decoration.options.after).toBeUndefined();
  });

  it("同じ行に複数の指摘がある場合は、記号と件数を行末へ添える", () => {
    const decorations = buildLineDecorations({
      notedLines: [],
      linkedLines: [],
      focusLine: null,
      identification: [],
      findings: [
        {
          line: 12,
          severity: "medium",
          count: 3,
          entries: [
            { ruleId: "R008", ruleName: "PERFORM単独段落名の直接指定", severity: "medium", message: "a" },
            { ruleId: "R009", ruleName: "GO TO文による構造化フローからの逸脱", severity: "warning", message: "b" },
            { ruleId: "R025", ruleName: "二項演算子の両辺が同一の式", severity: "warning", message: "c" },
          ],
        },
      ],
    });
    expect(decorations[0].options.after?.content).toBe(" ◆3");
  });

  it("指摘の装飾は対応行・ジャンプ先より下へ敷く(ジャンプ先の強調を隠さない)", () => {
    const decorations = buildLineDecorations({
      notedLines: [11],
      linkedLines: [11],
      focusLine: 11,
      identification: [],
      findings: [{ line: 11, severity: "low", count: 1, entries: [] }],
    });
    expect(decorations.map((decoration) => decoration.options.className)).toEqual([
      findingLineClass("low"),
      LINE_CLASS.noted,
      LINE_CLASS.linked,
      LINE_CLASS.focus,
    ]);
  });
});

describe("桁見出しの位置(Monaco の実測寸法から求める)", () => {
  const METRICS = { contentLeft: 60, charWidth: 7.2, scrollLeft: 0 };

  it("桁の左端は、コード面の左端 + (桁-1) × 文字送り である", () => {
    expect(columnLeftPx(METRICS, 1)).toBe(60);
    expect(columnLeftPx(METRICS, 8)).toBeCloseTo(110.4, 5);
    expect(columnLeftPx(METRICS, 73)).toBeCloseTo(578.4, 5);
  });

  it("水平スクロール量の分だけ左へずらす", () => {
    expect(columnLeftPx({ ...METRICS, scrollLeft: 50 }, 1)).toBe(10);
  });

  it("見出しは固定形式の欄境界(8桁目・73桁目)へ寄せる", () => {
    expect(COLUMN_MARKS.map((mark) => [mark.column, mark.anchor])).toEqual([
      [8, "end"],
      [8, "start"],
      [73, "end"],
      [73, "start"],
    ]);
  });

  it("実測寸法の同値判定は3つの値をすべて見る", () => {
    expect(metricsEqual(null, METRICS)).toBe(false);
    expect(metricsEqual(METRICS, { ...METRICS })).toBe(true);
    expect(metricsEqual(METRICS, { ...METRICS, scrollLeft: 1 })).toBe(false);
    expect(metricsEqual(METRICS, { ...METRICS, charWidth: 7.3 })).toBe(false);
    expect(metricsEqual(METRICS, { ...METRICS, contentLeft: 61 })).toBe(false);
  });
});

describe("copyExpansionFile(COPY 展開の対応表の位置)", () => {
  it("SQLite と同じフォルダの対応表を指す", () => {
    expect(copyExpansionFile("C:\\proj\\cobol-insight.db")).toBe(
      "C:\\proj\\cobol-insight-copy-expansion.json",
    );
  });

  it("フォルダを持たないパスでは相対名を返す", () => {
    expect(copyExpansionFile("cobol-insight.db")).toBe("cobol-insight-copy-expansion.json");
  });
});

describe("expansionsFor(表示中の資産の展開)", () => {
  const DATA = {
    programs: [
      {
        path: "cobol/SYK002.cbl",
        programId: "SYK002",
        expansions: [{ copyStatementLine: 5, copybookName: "B", copybookPath: "copybook/B.cpy", lines: [] }],
      },
      {
        path: "cobol/SYK001.cbl",
        programId: "SYK001",
        expansions: [
          { copyStatementLine: 9, copybookName: "C", copybookPath: "copybook/C.cpy", lines: [] },
          { copyStatementLine: 7, copybookName: "A", copybookPath: "copybook/A.cpy", lines: [] },
        ],
      },
    ],
  };

  it("相対パスが一致するプログラムの展開を COPY 文の行番号順で返す", () => {
    expect(expansionsFor(DATA, "cobol/SYK001.cbl").map((e) => e.copyStatementLine)).toEqual([7, 9]);
  });

  it("対応表に無い資産は空を返す", () => {
    expect(expansionsFor(DATA, "cobol/SYK009.cbl")).toEqual([]);
  });
});

describe("buildExpansionZones(COPY 文の位置へ差し込む展開)", () => {
  const STATEMENT: CopyStatement = {
    line: 7,
    name: "SYKCPY1",
    replacing: "LEADING ==SYK1== BY ==ORD1==",
    text: "COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.",
  };
  const EXPANSION: CopyExpansion = {
    copyStatementLine: 7,
    copybookName: "SYKCPY1",
    copybookPath: "copybook/SYKCPY1.cpy",
    lines: [
      { copybookLine: 1, text: "" },
      { copybookLine: 2, text: "       01  ORD1-REC." },
      { copybookLine: 3, text: "           05  ORD1-KEY   PIC X(10)." },
    ],
  };
  /** 原本のコピー句。1行目は注記行で、engine の前処理では空になる。 */
  const ORIGINAL: readonly string[] = [
    "000100* 受注レコード                                                CPY00110",
    "000200 01  SYK1-REC.                                                CPY00120",
    "000300     05  SYK1-KEY   PIC X(10).                                CPY00130",
  ];

  function zones(
    statements: readonly CopyStatement[],
    expansions: readonly CopyExpansion[],
    copybookLines: ReadonlyMap<string, readonly string[]>,
  ) {
    return buildExpansionZones({ statements, expansions, copybookLines, codepage: "Shift_JIS" });
  }

  it("COPY 文の直後へ、コピー句の行番号付きで展開行を並べる", () => {
    const zone = zones([STATEMENT], [EXPANSION], new Map())[0];
    expect(zone.afterLine).toBe(7);
    expect(zone.title).toContain("COPY SYKCPY1");
    expect(zone.title).toContain("copybook/SYKCPY1.cpy");
    expect(zone.lines.map((line) => line.copybookLine)).toEqual([1, 2, 3]);
    expect(zone.lines[1].text).toBe("       01  ORD1-REC.");
    expect(zone.lines[1].restored).toBe(false);
  });

  it("前処理で空になった注記行を、原本から一連番号欄を除いて補う", () => {
    const zone = zones([STATEMENT], [EXPANSION], new Map([[EXPANSION.copybookPath, ORIGINAL]]))[0];
    expect(zone.lines[0]).toEqual({
      copybookLine: 1,
      text: "      * 受注レコード                                                CPY00110",
      restored: true,
    });
    expect(zone.note).toContain("注記行は原本から補います");
  });

  it("原本を読めないときは空行のまま示し、空に見える理由を添える", () => {
    const zone = zones([STATEMENT], [EXPANSION], new Map())[0];
    expect(zone.lines[0]).toEqual({ copybookLine: 1, text: "", restored: false });
    expect(zone.note).toContain("注記行は空のまま");
  });

  it("原本の側も空の行は補わない", () => {
    const original = ["", ...ORIGINAL.slice(1)];
    const zone = zones([STATEMENT], [EXPANSION], new Map([[EXPANSION.copybookPath, original]]))[0];
    expect(zone.lines[0].restored).toBe(false);
  });

  it("展開データが無い COPY 文には、対象外である旨だけを差し込む", () => {
    const nested: CopyStatement = { line: 20, name: "SYKCPY9", replacing: null, text: "COPY SYKCPY9." };
    const built = zones([STATEMENT, nested], [EXPANSION], new Map());
    expect(built.map((zone) => zone.afterLine)).toEqual([7, 20]);
    expect(built[1].lines).toEqual([]);
    expect(built[1].title).toContain("SYKCPY9");
    expect(built[1].note).toContain("入れ子の COPY");
  });

  it("読み上げ用の名前に、原本の行番号とコピー句名を含める", () => {
    const zone = zones([STATEMENT], [EXPANSION], new Map())[0];
    expect(zone.ariaLabel).toBe("7 行の COPY SYKCPY1 の展開 3 行");
  });
});

describe("copyExpansionSummary(展開の状態の1行表示)", () => {
  const STATEMENTS: readonly CopyStatement[] = [
    { line: 7, name: "A", replacing: null, text: "COPY A." },
    { line: 9, name: "B", replacing: null, text: "COPY B." },
  ];
  const EXPANSION: CopyExpansion = {
    copyStatementLine: 7,
    copybookName: "A",
    copybookPath: "copybook/A.cpy",
    lines: [],
  };

  it("閉じているあいだは件数を示す", () => {
    expect(copyExpansionSummary(STATEMENTS, { status: "idle" })).toBe("COPY 文 2 件");
  });

  it("読込中と取得失敗は、空表示に潰さずそれぞれの理由を示す", () => {
    expect(copyExpansionSummary(STATEMENTS, { status: "loading" })).toContain("読み込んでいます");
    expect(copyExpansionSummary(STATEMENTS, { status: "error", message: "ファイルが無い" })).toContain(
      "ファイルが無い",
    );
  });

  it("展開できた件数と、展開データが無い件数を分けて示す", () => {
    const state = {
      status: "ready",
      expansions: [EXPANSION],
      copybookLines: new Map<string, readonly string[]>(),
    } as const;
    expect(copyExpansionSummary(STATEMENTS, state)).toBe(
      "COPY 文 2 件のうち 1 件を展開中（1 件は展開データがありません）",
    );
    expect(copyExpansionSummary(STATEMENTS.slice(0, 1), state)).toBe(
      "COPY 文 1 件のうち 1 件を展開中",
    );
  });
});
