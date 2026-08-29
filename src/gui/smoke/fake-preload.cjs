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
const RULES_PATH = "C:\\smoke\\data\\rules.json";

/** The lower bound of the graph layer's ids: the engine keeps them clear of the asset ids. */
const GRAPH_ID_BASE = 1_000_000_000_000;

/**
 * A minimal rule catalog: two built-in rules and one user-defined. The real count and the coverage
 * of the prose are the engine's tests and vitest's business; the smoke only watches the rendering.
 */
const RULES = [
  {
    id: "R001",
    name: "未初期化変数の参照",
    category: "データフロー",
    severity: "HIGH",
    phase: "DATA_FLOW",
    hasFix: false,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: ["lint"],
    targets: ["COBOL"],
    needs: ["dataflow"],
    summary: "値を設定される前に参照され得るデータ項目を検出します。",
    rationale: "記憶域に残った値をそのまま使うため、実行のたびに結果が変わります。",
    detection: "到達定義解析で、入口に置いた未初期化の定義が使用位置へ届くものを検出します。",
    remedy: "宣言へ VALUE 句を置くか、参照前に値を設定します。",
    badExample: "01  WS-COUNT  PIC 9(4).",
    goodExample: "01  WS-COUNT  PIC 9(4) VALUE ZERO.",
  },
  {
    id: "R004",
    name: "ON SIZE ERROR句の欠如",
    category: "例外処理",
    severity: "MEDIUM",
    phase: "DATA_FLOW",
    hasFix: true,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: ["lint"],
    targets: ["COBOL"],
    needs: ["dataflow"],
    summary: "桁あふれを検知しない算術文を検出します。",
    rationale: "上位桁を失った値が後続へ渡ります。",
    detection: "結果の範囲が受信項目の容量を超え得るものを検出します。",
    remedy: "ON SIZE ERROR 句を付けます。",
    badExample: "COMPUTE WS-RESULT = WS-QTY * WS-PRICE.",
    goodExample: "COMPUTE WS-RESULT = WS-QTY * WS-PRICE ON SIZE ERROR CONTINUE END-COMPUTE.",
  },
  {
    id: "S001",
    name: "SELECT * の使用",
    category: "SQL",
    severity: "LOW",
    phase: "SYNTAX",
    hasFix: false,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: ["sql-lint"],
    targets: ["COBOL"],
    needs: ["sql"],
    summary: "列を明示しない SELECT を検出します。",
    rationale: "表の定義が変わると取得する列が変わります。",
    detection: "SELECT 句が * のものを検出します。",
    remedy: "必要な列を並べます。",
    badExample: "EXEC SQL SELECT * FROM ZAIKOM END-EXEC.",
    goodExample: "EXEC SQL SELECT SOKO-CD FROM ZAIKOM END-EXEC.",
  },
];

const INVENTORY = [
  { id: 1, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: "Shift_JIS", byteSize: 1140, findingCount: 0 },
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 4200, findingCount: 1 },
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 3800, findingCount: 1 },
  { id: 4, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: null, byteSize: 640, findingCount: 0 },
  { id: 5, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", byteSize: 900, findingCount: 0 },
  { id: 6, path: "encoding/SYKENC1_CP930.cbl", name: "SYKENC1_CP930.cbl", type: "PROGRAM", codepage: "IBM930", byteSize: 1200, findingCount: 0 },
];

const FINDINGS = [
  { ruleId: "R004", level: "warning", message: "ON SIZE ERROR 句が無い。", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 12 },
  { ruleId: "R001", level: "error", message: "WK-ORDER-ID が未初期化のまま参照されている。", file: "cobol/SYK002.cbl", startLine: 24, startColumn: 12 },
];

const SQL_FINDINGS = [
  { ruleId: "S001", level: "warning", message: "SELECT * を列指定へ改める。", file: "cobol/SYK001.cbl", startLine: 30, startColumn: 12 },
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
let settings = {
  severityThreshold: "warning",
  defaultEncoding: "",
  copybookPaths: [],
  lastInputDir: "",
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

const api = {
  run: (invocation) => {
    switch (invocation.subcommand) {
      case "scan":
        return Promise.resolve(engineResult("scan", { db: DB_PATH }));
      case "lint":
        return Promise.resolve(engineResult("lint", { sarif: LINT_SARIF }));
      case "sql-lint":
        return Promise.resolve(engineResult("sql-lint", { sarif: SQL_SARIF }));
      default:
        return Promise.resolve(engineResult(invocation.subcommand));
    }
  },
  cancel: () => Promise.resolve(undefined),
  decode: (request) => {
    if (request.path === CP930_PATH) {
      return Promise.resolve({
        text: CP930_TEXT,
        codepage: "x-IBM930",
        detected: false,
        soSiPresent: true,
        lines: CP930_LINES,
        stamp: stampOf(request.path),
        error: "",
      });
    }
    return Promise.resolve({
      text: request.path.includes("SYKCPY1") ? COPYBOOK_TEXT : COBOL_TEXT,
      codepage: "Shift_JIS",
      detected: true,
      soSiPresent: false,
      lines: [],
      stamp: stampOf(request.path),
      error: "",
    });
  },
  save: (request) =>
    Promise.resolve({
      written: true,
      path: `C:/smoke/assets/${request.path}`,
      changedLineFrom: 1,
      changedLineTo: 1,
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
  readSarif: (path) => Promise.resolve(path === SQL_SARIF ? SQL_FINDINGS : FINDINGS),
  readGraph: () => Promise.resolve(GRAPH),
  readCopyExpansion: () => Promise.resolve({ programs: [] }),
  readFixDiff: (request) =>
    Promise.resolve({
      relPath: request.relPath,
      originalText: COBOL_TEXT,
      // One line differs, so the diff view has something to line up.
      fixedText: COBOL_TEXT.replace("STOP RUN.", "GOBACK.  "),
    }),
  readTranspile: () => Promise.resolve({ files: [], lineMap: [] }),
  readReport: () => Promise.resolve("<h1>COBOL Insight 解析レポート</h1>"),

  outputPaths: () =>
    Promise.resolve({
      db: DB_PATH,
      sarif: LINT_SARIF,
      sqlSarif: SQL_SARIF,
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

  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);

/**
 * What the smoke needs to see but the application does not: the rule file as the last write left
 * it, so a toggle on screen can be shown to have reached the file.
 */
contextBridge.exposeInMainWorld("cobolInsightSmoke", {
  rulesFile: () => rulesFile,
});
