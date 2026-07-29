/**
 * ソースビューアのテスト用 fixture。実データと同じ形をとる。すなわち COBOL は固定形式
 * (1〜6桁=一連番号欄、7桁目=標識欄、8桁目以降=本体)、生成物は transpile が出力先直下へ平坦に書く
 * ファイル、行対応は LINE_MAP の列(kind は "1:1"/"1:N"/"N:1")である。
 */

import type {
  CopyExpansionData,
  LineMapEntry,
  TranspileGeneratedFile,
} from "../../../../shared/engine-api";

/** COBOL 原本。7行目に COPY 文、11〜12行目に対訳の対応がある。 */
export const SAMPLE_COBOL_TEXT = [
  "       IDENTIFICATION DIVISION.",
  "       PROGRAM-ID.  SYK001.",
  "",
  "       DATA DIVISION.",
  "       FILE SECTION.",
  "       FD  ORDIN",
  "           COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.",
  "",
  "       PROCEDURE DIVISION.",
  "       MAIN-PROC.",
  "           MOVE ZERO TO WS-COUNT.",
  "           GO TO EXIT-PROC.",
].join("\n");

/** コピー句の原本。1行目は注記行で、engine の前処理では空になる。 */
export const SAMPLE_COPYBOOK_TEXT = [
  "000100* 受注レコード",
  "000200 01  SYK1-REC.",
  "000300     05  SYK1-KEY             PIC X(10).",
  "000400     05  SYK1-DATA            PIC X(90).",
].join("\n");

/**
 * scan が書く COPY 展開の対応表。SAMPLE_COBOL_TEXT の 7 行目の COPY 文に対応し、text は
 * REPLACING(SYK1→ORD1)適用後・注記行と一連番号欄と識別欄が空白の姿である。
 */
export const SAMPLE_COPY_EXPANSION: CopyExpansionData = {
  programs: [
    {
      path: "cobol/SYK001.cbl",
      programId: "SYK001",
      expansions: [
        {
          copyStatementLine: 7,
          copybookName: "SYKCPY1",
          copybookPath: "copybook/SYKCPY1.cpy",
          lines: [
            { copybookLine: 1, text: "" },
            { copybookLine: 2, text: "       01  ORD1-REC." },
            { copybookLine: 3, text: "           05  ORD1-KEY             PIC X(10)." },
            { copybookLine: 4, text: "           05  ORD1-DATA            PIC X(90)." },
          ],
        },
      ],
    },
  ],
};

/** Python の生成物。12行目と15〜18行目に対応がある。 */
export const SAMPLE_PYTHON_TEXT = [
  '"""SYK001 の逐語対訳（自動生成）"""',
  "from runtime import Program",
  "",
  "class Syk001(Program):",
  "    def __init__(self):",
  "        self.ws_count = 0",
  "        self.goto = None",
  "",
  "    def run(self):",
  "        self.main_proc()",
  "",
  "    def main_proc(self):",
  "        self.ws_count = 0",
  "",
  "        while True:",
  '            if self.goto == "EXIT_PROC":',
  "                break",
  "            return",
  "",
  "# 生成終わり",
].join("\n");

/** Java の生成物。20行目に対応がある。 */
export const SAMPLE_JAVA_TEXT = [
  "package generated;",
  "",
  "/** SYK001 の逐語対訳（自動生成） */",
  "public final class Syk001 extends Program {",
  "",
  "    private int wsCount;",
  "    private String goTo;",
  "",
  "    @Override",
  "    public void run() {",
  "        mainProc();",
  "    }",
  "",
  "    private void mainProc() {",
  "        wsCount = 0;",
  "        while (true) {",
  '            if ("EXIT_PROC".equals(goTo)) {',
  "                break;",
  "            }",
  "            return;",
  "        }",
  "    }",
  "}",
].join("\n");

/** レコード定義の生成物(同一言語で生成物が複数になる場合)。 */
export const SAMPLE_RECORD_PYTHON_TEXT = [
  '"""SYK1-REC のレコード定義（自動生成）"""',
  "",
  "class Syk1Rec:",
  "    KEY_OFFSET = 0",
  "    KEY_LENGTH = 10",
  "    DATA_OFFSET = 10",
  "    DATA_LENGTH = 90",
  "",
].join("\n");

export const SAMPLE_GENERATED_FILES: readonly TranspileGeneratedFile[] = [
  { name: "Syk001.java", language: "java", text: SAMPLE_JAVA_TEXT },
  { name: "syk001.py", language: "python", text: SAMPLE_PYTHON_TEXT },
  { name: "syk001_record.py", language: "python", text: SAMPLE_RECORD_PYTHON_TEXT },
];

export const SAMPLE_LINE_MAP: readonly LineMapEntry[] = [
  {
    id: 1,
    cobolLineStart: 11,
    cobolLineEnd: 11,
    genFile: "syk001.py",
    genLineStart: 13,
    genLineEnd: 13,
    kind: "1:1",
    note: "",
    anchorId: "s:1",
  },
  {
    id: 2,
    cobolLineStart: 12,
    cobolLineEnd: 12,
    genFile: "syk001.py",
    genLineStart: 15,
    genLineEnd: 18,
    kind: "1:N",
    note: "GO TO は直訳できないため、構造化した分岐へ置き換えた",
    anchorId: "s:2",
  },
  {
    id: 3,
    cobolLineStart: 11,
    cobolLineEnd: 11,
    genFile: "Syk001.java",
    genLineStart: 15,
    genLineEnd: 15,
    kind: "1:1",
    note: "",
    anchorId: "s:1",
  },
  {
    id: 4,
    cobolLineStart: 7,
    cobolLineEnd: 7,
    genFile: "syk001_record.py",
    genLineStart: 3,
    genLineEnd: 7,
    kind: "1:N",
    note: "",
    anchorId: "r:1",
  },
];
