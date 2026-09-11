/*
 * The fake preload for the offscreen render smoke. It satisfies the same contract as the real one
 * (src/shared/ipc.ts, CobolInsightApi) with canned data, so the screens reach their results state
 * without the engine ever being launched.
 *
 * It is loaded with the production settings, contextIsolation:true and sandbox:true, which is why it
 * has to be self-contained: a sandboxed preload cannot require a local module. It touches neither
 * the filesystem nor the engine.
 */

const { contextBridge } = require("electron");

const DB_PATH = "C:\\smoke\\cobol-insight.db";
const LINT_SARIF = "C:\\smoke\\lint.sarif";
const SQL_SARIF = "C:\\smoke\\sql.sarif";
/** Where a scoped lint writes, so the whole-folder pair stays as the last full run left it. */
const SCOPE_SARIF = "C:\\smoke\\scope.sarif";
const SCOPE_SQL_SARIF = "C:\\smoke\\scope-sql.sarif";
const RULES_PATH = "C:\\smoke\\data\\rules.json";

/** The lower bound of the graph layer's ids: the engine keeps them clear of the asset ids. */
const GRAPH_ID_BASE = 1_000_000_000_000;

/**
 * A minimal rule catalog: three built-in rules. Every string here is the text the engine's own
 * RuleMeta carries for R001, R004 and S001, so a screenshot shows the prose the product ships. The
 * real count and the coverage are the engine's tests and vitest's business; the smoke only watches
 * the rendering.
 */
const RULES = [
  {
    id: "R001",
    name: "未初期化のデータ項目の参照",
    category: "データフロー",
    severity: "HIGH",
    hasFix: false,
    source: "builtin",
    enabled: true,
    commands: ["LINT", "REPORT"],
    targets: ["COBOL"],
    summary: "値を設定する前に参照し得るデータ項目を検出します。",
    rationale:
      "記憶域に残った値をそのまま使うため、実行のたびに結果が変わり、再現しない不具合になります。",
    detection:
      "値を設定する文を通らない経路がある参照を検出します。" +
      "対象は、プログラム内のいずれかの文が明示的に値を設定する基本項目です。" +
      "ファイル節の項目・集団項目・PROCEDURE DIVISION USING の引数・" +
      "特殊レジスタと、入出力状態や CICS の応答コードのように実行系が暗黙に" +
      "設定する項目は対象外です。",
    remedy: "宣言に VALUE 句を置くか、参照の前に INITIALIZE・MOVE で値を設定してください。",
    badExample: [
      "01  WS-COUNT  PIC 9(4).",
      '    IF WS-FLG = "Y"',
      "        MOVE 1 TO WS-COUNT",
      "    END-IF.",
      "    DISPLAY WS-COUNT.",
      "",
    ].join("\n"),
    goodExample: [
      "01  WS-COUNT  PIC 9(4) VALUE ZERO.",
      '    IF WS-FLG = "Y"',
      "        MOVE 1 TO WS-COUNT",
      "    END-IF.",
      "    DISPLAY WS-COUNT.",
      "",
    ].join("\n"),
  },
  {
    id: "R004",
    name: "ON SIZE ERROR 句の欠如",
    category: "例外処理",
    severity: "HIGH",
    hasFix: true,
    source: "builtin",
    enabled: true,
    commands: ["FIX", "LINT", "REPORT"],
    targets: ["COBOL"],
    summary:
      "結果が受け取り側項目のけた数を超え得るのに ON SIZE ERROR 句を持たない算術文を検出します。",
    rationale:
      "けたあふれが起きても検知されず、上位けたを失った値がそのまま後続の計算と出力に渡ります。",
    detection:
      "ADD・SUBTRACT・MULTIPLY・DIVIDE・COMPUTE のうち、ON SIZE ERROR 句がなく、" +
      "結果が受け取り側項目の整数部のけた数を超え得る" +
      "（結果の範囲が定まらない場合を含む）ものを検出します。" +
      "受け取り側項目自身を加数に含む累算は対象外です。",
    remedy:
      "ON SIZE ERROR 句を付けてけたあふれ時の処理を書くか、受け取り側項目のけた数を広げてください。",
    badExample: [
      "01  WS-RESULT  PIC 9(4).",
      "    COMPUTE WS-RESULT = WS-QTY * WS-PRICE.",
      "",
    ].join("\n"),
    goodExample: [
      "01  WS-RESULT  PIC 9(4).",
      "    COMPUTE WS-RESULT = WS-QTY * WS-PRICE",
      "        ON SIZE ERROR PERFORM OVERFLOW-SHORI",
      "    END-COMPUTE.",
      "",
    ].join("\n"),
  },
  {
    id: "S001",
    name: "列を明示しない SELECT *",
    category: "可読性・保守性",
    severity: "MEDIUM",
    hasFix: false,
    source: "builtin",
    enabled: true,
    commands: ["SQL_LINT"],
    targets: ["COBOL"],
    summary: "SELECT 句に * を使う問い合わせを検出します。",
    rationale:
      "表に列を足しただけで転送量と受け側の構造が変わります。" +
      "必要のない列まで読むため入出力も増えます。",
    detection:
      "埋込みSQL文の SELECT 句に * を書いたものを検出します。" +
      "カーソル宣言の中の SELECT 句も対象です。",
    remedy: "必要な列を明示して並べてください。",
    badExample: "SELECT * FROM CUSTOMER WHERE ID = :WS-ID\n",
    goodExample: "SELECT ID, NAME, ADDR FROM CUSTOMER WHERE ID = :WS-ID\n",
  },
];

const INVENTORY = [
  { id: 1, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: "Shift_JIS", findingCount: 0 },
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", findingCount: 1 },
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "Shift_JIS", findingCount: 1 },
  { id: 4, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: null, findingCount: 0 },
  { id: 5, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", findingCount: 0 },
  { id: 6, path: "encoding/SYKENC1_CP930.cbl", name: "SYKENC1_CP930.cbl", type: "PROGRAM", codepage: "IBM930", findingCount: 0 },
  { id: 7, path: "ddl/SYKTAB.sql", name: "SYKTAB.sql", type: "SQL", codepage: "UTF-8", findingCount: 0 },
];

/*
 * The findings each rule composes, written the way the engine writes them: R004 names the receiving
 * item and the verb, and R001 names the item, the statements that do set it, and its declaration,
 * with one code-flow step per place the reader has to look at.
 */
const FINDINGS = [
  {
    ruleId: "R004",
    level: "error",
    message:
      "WS-RESULT への COMPUTE 文に ON SIZE ERROR 句がありません。" +
      "結果がけた数を超えても、けたあふれが検知されません。",
    file: "cobol/SYK001.cbl",
    startLine: 10,
    startColumn: 12,
  },
  {
    ruleId: "R001",
    level: "error",
    message:
      "WK-ORDER-ID を未設定のまま参照しています。" +
      "値を設定する 20行 を通らない経路があります（宣言 12行）。",
    file: "cobol/SYK002.cbl",
    startLine: 24,
    startColumn: 12,
    related: [
      { file: "cobol/SYK002.cbl", line: 12, label: "宣言（VALUE 句がなく初期値は不定）" },
      {
        file: "cobol/SYK002.cbl",
        line: 20,
        label: "WK-ORDER-ID に値を設定する文（この文を通らない経路がある）",
      },
      { file: "cobol/SYK002.cbl", line: 24, label: "未設定のまま参照する箇所" },
    ],
  },
];

const SQL_FINDINGS = [
  {
    ruleId: "S001",
    level: "warning",
    message: "SELECT * で全列を取得しています。表の構造の変更に弱く、不要な列まで転送します。",
    file: "cobol/SYK001.cbl",
    startLine: 30,
    startColumn: 12,
  },
];

/** Fixed-format 80-column COBOL, with a full-width comment line, a COPY statement and DBCS text. */
const COBOL_TEXT = [
  "000100 IDENTIFICATION DIVISION.                                        SYK00110",
  "000200 PROGRAM-ID. SYK001.                                             SYK00120",
  "000300 DATA DIVISION.                                                  SYK00130",
  "000400 WORKING-STORAGE SECTION.                                        SYK00140",
  "000500     COPY SYKCPY1.                                               SYK00150",
  "000600* 受注データを検査する（全角の注記行）                           SYK00160",
  "000700 PROCEDURE DIVISION.                                             SYK00170",
  "000800 MAIN-PROC.                                                      SYK00180",
  "000900     DISPLAY 'PLEASE COPY THIS TEXT'.                            SYK00190",
  "001000     MOVE 受注番号 TO WK-ORDER-ID.                               SYK00200",
  "001100     STOP RUN.                                                   SYK00210",
].join("\n");

/*
 * One EBCDIC CP930 asset, copied from what the engine actually returns for
 * samples/encoding/SYKENC1_CP930.cbl: the text, and the byte-column boundaries of every line.
 *
 * The boundaries are the point of the fixture. Line 3 holds double-byte characters, and CP930 wraps
 * a double-byte run in shift-out and shift-in bytes, so byte column 73 — where the identification
 * area starts — falls on character 53, not on character 73 as it would in a line of single-byte
 * characters. Anything that counted characters instead of bytes would put the decoration elsewhere.
 */
const CP930_PATH = "encoding/SYKENC1_CP930.cbl";

const CP930_TEXT = [
  "      *================================================================*",
  "      *  PROGRAM-ID : SYKENC1                                         *",
  "      *  文字コード検証用サンプルプログラム                             *",
  "      *  日本語コメントと日本語混じりの見出しを含む。                    *",
  "      *================================================================*",
  "       IDENTIFICATION DIVISION.",
  "       PROGRAM-ID.  SYKENC1.",
  "       PROCEDURE DIVISION.",
  "           STOP RUN.",
].join("\n");

const CP930_LINES = [
  { byteLength: 73, boundaries: [6, 7, 11, 72] },
  { byteLength: 72, boundaries: [6, 7, 11, -1] },
  { byteLength: 76, boundaries: [6, 7, 10, 53] },
  { byteLength: 77, boundaries: [6, 7, 10, 48] },
  { byteLength: 73, boundaries: [6, 7, 11, 72] },
  { byteLength: 32, boundaries: [6, 7, 11, -1] },
  { byteLength: 29, boundaries: [6, 7, 11, -1] },
  { byteLength: 27, boundaries: [6, 7, 11, -1] },
  { byteLength: 21, boundaries: [6, 7, 11, -1] },
];

const COPYBOOK_TEXT = [
  "000100 01  SYK-ORDER-REC.                                              CPY00110",
  "000200     05  ORDER-ID      PIC X(10).                                CPY00120",
  "000300     05  ORDER-QTY     PIC 9(5).                                 CPY00130",
].join("\n");

/*
 * The COPY expansion table for the first asset. Line 5 of COBOL_TEXT is its COPY statement, and the
 * lines are the copybook's, which is what the view zone between lines 5 and 6 has to show.
 */
const COPY_EXPANSION = {
  programs: [
    {
      path: "cobol/SYK001.cbl",
      programId: "SYK001",
      expansions: [
        {
          copyStatementLine: 5,
          copybookName: "SYKCPY1",
          copybookPath: "copybook/SYKCPY1.cpy",
          lines: COPYBOOK_TEXT.split("\n").map((text, index) => ({
            copybookLine: index + 1,
            text,
          })),
        },
      ],
    },
  ],
};

/*
 * The translation of the first asset, in both languages, with the line correspondence the engine
 * persists into LINE_MAP. Only the four statements of the procedure division are mapped, which is
 * what lets the smoke tell a real mapping from a pane that merely scrolled to the same line number.
 */
const PYTHON_TEXT = [
  "def main():",
  "    print('PLEASE COPY THIS TEXT')",
  "    wk_order_id = juchu_bango",
  "    return",
].join("\n");

const JAVA_TEXT = [
  "public final class SYK001 {",
  "    public static void main(String[] args) {",
  "        System.out.println(\"PLEASE COPY THIS TEXT\");",
  "        wkOrderId = juchuBango;",
  "        return;",
  "    }",
  "}",
].join("\n");

/** COBOL line -> generated line, per generated file. The Java form sits one line further down. */
function lineMapRows(genFile, offset) {
  return [
    [7, 1],
    [9, 2],
    [10, 3],
    [11, 4],
  ].map(([cobolLine, genLine]) => ({
    cobolLineStart: cobolLine,
    cobolLineEnd: cobolLine,
    genFile,
    genLineStart: genLine + offset,
    genLineEnd: genLine + offset,
  }));
}

const TRANSPILE = {
  files: [
    { name: "syk001.py", language: "python", text: PYTHON_TEXT },
    { name: "SYK001.java", language: "java", text: JAVA_TEXT },
  ],
  lineMap: [...lineMapRows("syk001.py", 0), ...lineMapRows("SYK001.java", 1)],
};

const GRAPH = {
  nodes: [
    { id: 2, type: "PROGRAM", label: "SYK001" },
    { id: 3, type: "PROGRAM", label: "SYK002" },
    { id: GRAPH_ID_BASE + 1, type: "JOB", label: "SYKD010" },
    { id: GRAPH_ID_BASE + 2, type: "STEP", label: "STEP010" },
    { id: GRAPH_ID_BASE + 3, type: "STEP", label: "STEP020" },
  ],
  edges: [
    // The two steps are listed out of execution order on purpose: the screen has to order them by
    // seq, not by the order they were read in.
    { from: GRAPH_ID_BASE + 1, to: GRAPH_ID_BASE + 3, kind: "EXECUTION", resolution: "CONSTANT", seq: 2, line: 30 },
    { from: GRAPH_ID_BASE + 1, to: GRAPH_ID_BASE + 2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: GRAPH_ID_BASE + 2, to: 2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: 2, to: 3, kind: "CALL", resolution: "CONSTANT", seq: 1, line: 11 },
  ],
  paragraphs: [
    { id: 21, programSourceId: 2, name: "MAIN-PROC", startLine: 8, endLine: 9 },
    { id: 22, programSourceId: 2, name: "READ-ORDER", startLine: 10, endLine: 10 },
  ],
  paragraphEdges: [
    { programSourceId: 2, from: 21, to: 22, toName: "READ-ORDER", kind: "PERFORM", line: 9, seq: 1 },
  ],
};

/**
 * How many times each file has been changed outside the tool. A file's stamp is derived from this,
 * so a save that follows a `touch` finds the original no longer the one it read.
 */
const touched = new Map();

function stampOf(path) {
  const revision = touched.get(path) ?? 0;
  return { mtimeMs: 1000 + revision, byteSize: 100 + revision };
}

/** The stored settings and the rule file, held in memory so a write is visible to the next read. */
/** The theme the smoke asked for through additionalArguments; empty means "system". */
const requestedTheme = (process.argv.find((arg) => arg.startsWith("--ci-theme=")) ?? "").slice(
  "--ci-theme=".length,
);

let settings = {
  theme: requestedTheme,
  severityThreshold: "warning",
  defaultEncoding: "",
  copybookPaths: [],
  // Empty, so the fix write-out falls back to the directory beside the project file.
  fixOutDir: "",
  paneSizes: {},
};
let rulesFile = { version: 2, rules: {}, custom: [] };

function engineResult(subcommand, outputs, summary) {
  return {
    subcommand,
    exitCode: 0,
    summary: summary === undefined ? null : summary,
    stdout: "",
    stderr: "",
    outputs: outputs === undefined ? {} : outputs,
  };
}

/** The scope each lint was asked for, so a check can tell the two run commands apart. */
const lintScopes = [];

const api = {
  run: (invocation) => {
    switch (invocation.subcommand) {
      // A finished run always prints its summary; without one the renderer treats it as a crash.
      case "scan":
        return Promise.resolve(
          engineResult("scan", { db: DB_PATH }, { findingCount: 0, dbFile: DB_PATH, exitCode: 0 }),
        );
      case "lint": {
        lintScopes.push(invocation.request.scope ?? null);
        const scoped = invocation.request.scope !== undefined;
        const sarif = scoped ? SCOPE_SARIF : LINT_SARIF;
        const sqlSarif = scoped ? SCOPE_SQL_SARIF : SQL_SARIF;
        return Promise.resolve(
          engineResult(
            "lint",
            { sarif, sqlSarif },
            { findingCount: 0, sarifFile: sarif, sqlSarifFile: sqlSarif, exitCode: 0 },
          ),
        );
      }
      default:
        // Every other subcommand finishes too, so the views that read what it wrote go ahead.
        return Promise.resolve(engineResult(invocation.subcommand, {}, { exitCode: 0 }));
    }
  },
  cancel: () => Promise.resolve(undefined),
  decode: (request) => {
    if (request.path === CP930_PATH) {
      return Promise.resolve({
        text: CP930_TEXT,
        codepage: "x-IBM930",
        detected: false,
        lines: CP930_LINES,
        stamp: stampOf(request.path),
        error: "",
      });
    }
    return Promise.resolve({
      text: request.path.includes("SYKCPY1") ? COPYBOOK_TEXT : COBOL_TEXT,
      codepage: "Shift_JIS",
      detected: true,
      lines: [],
      stamp: stampOf(request.path),
      error: "",
    });
  },
  save: (request) =>
    Promise.resolve({
      written: true,
      path: `C:/smoke/assets/${request.path}`,
      reparseErrors: [],
      error: "",
      exitCode: 0,
    }),
  rules: () =>
    Promise.resolve({
      rules: RULES.map((rule) => ({
        ...rule,
        enabled: rulesFile.rules[rule.id]?.enabled !== false,
      })),
      ruleErrors: [],
    }),
  validateRules: () => Promise.resolve({ ok: true, errors: [], parsed: { rules: RULES, ruleErrors: [] } }),

  readInventory: () => Promise.resolve(INVENTORY),
  readSarif: (path) =>
    Promise.resolve(path === SQL_SARIF || path === SCOPE_SQL_SARIF ? SQL_FINDINGS : FINDINGS),
  readGraph: () => Promise.resolve(GRAPH),
  readCopyExpansion: () => Promise.resolve(COPY_EXPANSION),
  readFixDiff: (request) =>
    Promise.resolve({
      relPath: request.relPath,
      originalText: COBOL_TEXT,
      // One line differs, so the diff view has something to line up.
      fixedText: COBOL_TEXT.replace("STOP RUN.", "GOBACK.  "),
    }),
  readTranspile: () => Promise.resolve(TRANSPILE),
  readReport: () => Promise.resolve("<h1>COBOL Insight 解析レポート</h1>"),

  outputPaths: () =>
    Promise.resolve({
      db: DB_PATH,
      sarif: LINT_SARIF,
      sqlSarif: SQL_SARIF,
      scopedSarif: SCOPE_SARIF,
      scopedSqlSarif: SCOPE_SQL_SARIF,
      copyExpansion: "C:\\smoke\\data\\cobol-insight-copy-expansion.json",
      rules: RULES_PATH,
    }),
  selectFolder: () => Promise.resolve("C:\\smoke\\assets"),
  dirExists: () => Promise.resolve(true),
  stat: (request) => Promise.resolve(stampOf(request.path)),

  /*
   * Not part of the contract the real preload publishes: the smoke's way of standing in for an edit
   * made outside the tool. Bumping a file's stamp is what makes the next save find a changed
   * original and raise the conflict dialog.
   */
  touch: (path) => {
    touched.set(path, (touched.get(path) ?? 0) + 1);
    return Promise.resolve(undefined);
  },
  importSource: (request) =>
    Promise.resolve({ status: "written", relPath: request.fileName, lineCount: request.lines.length }),
  saveAs: (request) => Promise.resolve(`C:\\smoke\\${request.fileName}`),

  readSettings: () => Promise.resolve(settings),
  writeSettings: (next) => {
    settings = next;
    return Promise.resolve(undefined);
  },
  readRules: () => Promise.resolve(rulesFile),
  writeRules: (_path, file) => {
    rulesFile = file;
    return Promise.resolve(undefined);
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);

/**
 * What the smoke needs to see but the application does not: the rule file as the last write left
 * it, so a toggle on screen can be shown to have reached the file.
 */
contextBridge.exposeInMainWorld("cobolInsightSmoke", {
  rulesFile: () => rulesFile,
  lintScopes: () => lintScopes,
});
