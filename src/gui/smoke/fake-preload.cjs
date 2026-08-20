/*
 * 実描画 smoke 用の偽 preload。engine CLI を起動せずに画面を results 状態まで進めるため、
 * window.cobolInsight と同じ契約(src/shared/engine-api.ts の CobolInsightApi)を固定データで満たす。
 *
 * 本番と同じ contextIsolation:true・sandbox:true で読み込むため、この1ファイルだけで完結させる
 * (sandbox 下の preload はローカルモジュールを require できない)。ファイルにも engine にも触れない。
 */

const { contextBridge } = require("electron");

const DB_PATH = "C:\\smoke\\cobol-insight.db";
const LINT_SARIF = "C:\\smoke\\lint.sarif";
const SQL_SARIF = "C:\\smoke\\sql-advise.sarif";
const CALLGRAPH_JSON = "C:\\smoke\\callgraph.json";
const USER_RULES_PATH = "C:\\smoke\\data\\user-rules.json";
const RULE_CONFIG_PATH = "C:\\smoke\\data\\rules-config.json";

/** グラフ層のノード ID の下限。engine は資産の ID と混ざらないようこの帯を使う。 */
const GRAPH_ID_BASE = 1_000_000_000_000;

/**
 * ルール表と説明の描画を確かめるための最小のカタログ。組み込み2件と利用者定義1件を置く。
 * 実際の件数(37件)と説明の網羅は engine 側のテストと vitest が担うため、ここでは描画の成立
 * (行が並ぶ・説明が開く・利用者定義が別枠に出る)だけを見る。
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
    severity: "HIGH",
    phase: "DATA_FLOW",
    hasFix: true,
    source: "builtin",
    summary: "結果が受信項目の桁を超え得るのに ON SIZE ERROR 句を持たない算術文を検出します。",
    rationale: "桁あふれが検知されず、上位桁を失った値が後続へ渡ります。",
    detection: "結果の範囲が受信項目の整数部の容量を超え得るものを検出します。",
    remedy: "ON SIZE ERROR 句を付けるか、受信項目の桁を広げます。",
    badExample: "COMPUTE WS-RESULT = WS-QTY * WS-PRICE.",
    goodExample: "COMPUTE WS-RESULT = WS-QTY * WS-PRICE\n    ON SIZE ERROR PERFORM OVERFLOW-SHORI\nEND-COMPUTE.",
  },
  {
    id: "U001",
    name: "コンソール入力の使用",
    category: "社内規約",
    severity: "MEDIUM",
    phase: "SYNTAX",
    hasFix: false,
    source: "user",
    summary: "正規表現「FROM\\s+CONSOLE」に一致する行を検出します。",
    rationale: "運用手順の外で値が入り、記録が残りません。",
    detection: "対象は COBOL の各行です。注記行を除き、8〜72桁の範囲を対象とします。",
    remedy: "入力をパラメータファイルから受け取ります。",
    badExample: "",
    goodExample: "",
  },
];

/**
 * ルールの有効・無効。engine は rules-config.json を読んで enabled を決めるため、偽 preload でも
 * 書いた内容がそのまま次の listRules へ効くようにする(切替が往復することを smoke が見る)。
 */
let ruleConfig = { version: 1, disabledRules: [] };

/** 利用者定義ルールの定義ファイルの中身。上の U001 に対応する。 */
const USER_RULE = {
  id: "U001",
  name: "コンソール入力の使用",
  category: "社内規約",
  severity: "MEDIUM",
  targets: ["COBOL"],
  pattern: "FROM\\s+CONSOLE",
  excludePattern: "",
  ignoreCase: false,
  wholeLine: false,
  message: "コンソール入力は運用規約で禁止されている",
  rationale: "運用手順の外で値が入り、記録が残りません。",
  remedy: "入力をパラメータファイルから受け取ります。",
};

/** 固定形式 80 桁の COBOL 原本。DBCS 混在行・識別欄・COPY 文・リテラル中の COPY を含む。 */
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
].join("\r\n");

/** 修正後ソース。原本の 10 行目へ ON SIZE ERROR 句を足した状態にし、差分が1箇所出るようにする。 */
const FIXED_COBOL_TEXT = COBOL_TEXT.split("\r\n")
  .map((line, index) =>
    index === 9
      ? "001000     MOVE 受注番号 TO WK-ORDER-ID ON SIZE ERROR CONTINUE.      SYK00200"
      : line,
  )
  .join("\r\n");

/** fix preview が標準出力へ書く unified diff。コピー句の修正案の表示に使う。 */
const FIX_UNIFIED_DIFF = [
  "--- a/copybook/SYKCPY1.cpy",
  "+++ b/copybook/SYKCPY1.cpy",
  "@@ -2,1 +2,1 @@",
  "-000200     05  ORDER-ID      PIC X(10).                                CPY00120",
  "+000200     05  ORDER-ID      PIC X(12).                                CPY00120",
  "",
].join("\n");

const COPYBOOK_TEXT = [
  "000100 01  SYK-ORDER-REC.                                              CPY00110",
  "000200     05  ORDER-ID      PIC X(10).                                CPY00120",
  "000300     05  ORDER-QTY     PIC 9(5).                                 CPY00130",
].join("\r\n");

const GENERATED_PYTHON = [
  "# 逐語対訳(Python)",
  "class Syk001:",
  "    def main_proc(self):",
  "        print('PLEASE COPY THIS TEXT')",
  "        self.wk_order_id = self.order_no",
  "        return 0",
].join("\n");

const GENERATED_JAVA = [
  "// 逐語対訳(Java)",
  "public class Syk001 {",
  "  void mainProc() {",
  "    System.out.println(\"PLEASE COPY THIS TEXT\");",
  "  }",
  "}",
].join("\n");

const INVENTORY = [
  { id: 1, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: "windows-31j", byteSize: 1140, findingCount: 0 },
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 4200, findingCount: 1 },
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 3800, findingCount: 1 },
  { id: 4, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: "windows-31j", byteSize: 640, findingCount: 0 },
  { id: 5, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "windows-31j", byteSize: 900, findingCount: 0 },
];

const FINDINGS = [
  { ruleId: "R004", level: "warning", message: "ON SIZE ERROR 句が無い。", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 12 },
  { ruleId: "R001", level: "error", message: "WK-ORDER-ID が未初期化のまま参照されている。", file: "cobol/SYK002.cbl", startLine: 24, startColumn: 12 },
];

const SQL_ADVICE = [
  { ruleId: "S001", level: "warning", message: "SELECT * を列指定へ改める。", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 12 },
];

/**
 * 走査済みプロジェクトファイルから読む呼出関係。ジョブ→ステップ→プログラム→段落まで辿れる形に
 * し、段落は FALLTHROUGH・PERFORM・GOTO の3種の流れを持たせる。実行順は seq が決める。
 */
const GRAPH = {
  nodes: [
    { id: 2, type: "PROGRAM", label: "SYK001" },
    { id: 3, type: "PROGRAM", label: "SYK002" },
    { id: GRAPH_ID_BASE + 1, type: "JOB", label: "SYKD010" },
    { id: GRAPH_ID_BASE + 2, type: "STEP", label: "STEP010" },
    { id: GRAPH_ID_BASE + 3, type: "STEP", label: "STEP020" },
    { id: GRAPH_ID_BASE + 4, type: "DATASET", label: "SYKT.D250718.ORDER.DAILY" },
  ],
  edges: [
    { from: GRAPH_ID_BASE + 1, to: GRAPH_ID_BASE + 2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: GRAPH_ID_BASE + 1, to: GRAPH_ID_BASE + 3, kind: "EXECUTION", resolution: "CONSTANT", seq: 2, line: 30 },
    { from: GRAPH_ID_BASE + 2, to: 2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: GRAPH_ID_BASE + 3, to: 3, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 30 },
    { from: GRAPH_ID_BASE + 2, to: GRAPH_ID_BASE + 4, kind: "REFERENCE", resolution: "CONSTANT", seq: 0, line: null },
    { from: 2, to: 3, kind: "CALL", resolution: "CONSTANT", seq: 1, line: 11 },
  ],
  paragraphs: [
    { id: 21, programSourceId: 2, name: "MAIN-PROC", startLine: 8, endLine: 9 },
    { id: 22, programSourceId: 2, name: "READ-ORDER", startLine: 10, endLine: 10 },
    { id: 23, programSourceId: 2, name: "ERROR-EXIT", startLine: 11, endLine: 11 },
  ],
  paragraphEdges: [
    { programSourceId: 2, from: 21, to: 22, toName: "READ-ORDER", kind: "PERFORM", line: 9, seq: 1 },
    { programSourceId: 2, from: 21, to: 23, toName: "ERROR-EXIT", kind: "GOTO", line: 9, seq: 2 },
    { programSourceId: 2, from: 21, to: 22, toName: "READ-ORDER", kind: "FALLTHROUGH", line: null, seq: 3 },
    { programSourceId: 2, from: 22, to: 23, toName: "ERROR-EXIT", kind: "FALLTHROUGH", line: null, seq: 1 },
  ],
};

const LINE_MAP = [
  { id: 1, cobolLineStart: 8, cobolLineEnd: 8, genFile: "syk001.py", genLineStart: 3, genLineEnd: 3, kind: "1:1", note: "", anchorId: "MAIN-PROC" },
  { id: 2, cobolLineStart: 9, cobolLineEnd: 9, genFile: "syk001.py", genLineStart: 4, genLineEnd: 4, kind: "1:1", note: "", anchorId: "" },
  { id: 3, cobolLineStart: 10, cobolLineEnd: 10, genFile: "syk001.py", genLineStart: 5, genLineEnd: 5, kind: "1:1", note: "全角の変数名は逐語対訳できない", anchorId: "" },
  { id: 4, cobolLineStart: 8, cobolLineEnd: 8, genFile: "Syk001.java", genLineStart: 3, genLineEnd: 3, kind: "1:1", note: "", anchorId: "mainProc" },
];

function engineResult(subcommand, outputs, summary, stdout) {
  return {
    subcommand,
    exitCode: 0,
    summary: summary === undefined ? null : summary,
    stdout: stdout === undefined ? "" : stdout,
    stderr: "",
    outputs,
  };
}

/** fix preview のサマリ。COBOL 1件とコピー句1件を返し、両方の表示経路を検査対象にする。 */
const FIX_PREVIEW_SUMMARY = {
  fixedFiles: ["cobol/SYK001.cbl"],
  copybookFixes: [
    { copybook: "copybook/SYKCPY1.cpy", importers: ["cobol/SYK001.cbl", "cobol/SYK002.cbl"] },
  ],
  fixCount: 2,
  analysisErrors: 0,
};

const REPORT_HTML = [
  "<style>body{font-family:sans-serif}h1{font-size:18px}</style>",
  "<h1>COBOL Insight 解析レポート</h1>",
  "<table><tr><th>資産</th><td>5</td></tr><tr><th>指摘</th><td>2</td></tr></table>",
].join("");

const api = {
  runScan: () => Promise.resolve(engineResult("scan", { db: DB_PATH })),
  runCallgraph: (request) =>
    Promise.resolve(
      engineResult("call-graph", {
        db: DB_PATH,
        ...(request.jsonFile === undefined ? {} : { json: CALLGRAPH_JSON }),
        ...(request.svgFile === undefined ? {} : { svg: request.svgFile }),
        ...(request.pngFile === undefined ? {} : { png: request.pngFile }),
      }),
    ),
  runLint: () => Promise.resolve(engineResult("lint", { sarif: LINT_SARIF })),
  runSqlLint: () => Promise.resolve(engineResult("sql-lint", { sarif: SQL_SARIF })),
  runReport: (request) =>
    Promise.resolve(
      engineResult(
        "report",
        {
          ...(request.htmlFile === undefined ? {} : { html: request.htmlFile }),
          ...(request.textFile === undefined ? {} : { text: request.textFile }),
        },
        { assets: 5, findings: 2, sqlAdvice: 1, callEdges: 6, analysisErrors: 0 },
      ),
    ),
  runTranspile: () => Promise.resolve(engineResult("translate", { outDir: "C:\\smoke\\transpile" })),
  runFixPreview: () =>
    Promise.resolve(engineResult("fix-preview", {}, FIX_PREVIEW_SUMMARY, FIX_UNIFIED_DIFF)),
  runFixApply: (request) =>
    Promise.resolve(
      engineResult(
        "fix-apply",
        { outDir: request.outDir === undefined ? "C:\\smoke\\fix" : request.outDir },
        {
          writtenFiles: ["cobol/SYK001.cbl"],
          copybookFixes: FIX_PREVIEW_SUMMARY.copybookFixes,
          fixCount: 1,
          analysisErrors: 0,
          reparseFailures: 0,
        },
      ),
    ),
  cancelRun: () => Promise.resolve(undefined),
  selectInputFolder: () => Promise.resolve("C:\\smoke\\assets"),
  checkDirectoryExists: () => Promise.resolve(true),
  getOutputPaths: () =>
    Promise.resolve({
      db: DB_PATH,
      lintSarif: LINT_SARIF,
      sqlAdviseSarif: SQL_SARIF,
      copyExpansion: "C:\\smoke\\data\\cobol-insight-copy-expansion.json",
      userRules: USER_RULES_PATH,
      ruleConfig: RULE_CONFIG_PATH,
    }),
  listRules: () =>
    Promise.resolve({
      rules: RULES.map((rule) => ({
        ...rule,
        enabled: !ruleConfig.disabledRules.includes(rule.id),
      })),
      userRuleErrors: [],
      ruleConfigWarnings: [],
    }),
  readUserRules: () => Promise.resolve({ version: 1, rules: [USER_RULE] }),
  writeUserRules: () => Promise.resolve(undefined),
  readRuleConfig: () => Promise.resolve(ruleConfig),
  writeRuleConfig: (_path, file) => {
    ruleConfig = file;
    return Promise.resolve(undefined);
  },
  readSettings: () =>
    Promise.resolve({
      severityThreshold: "warning",
      defaultEncoding: "手動: Shift_JIS",
      copybookPaths: [],
      paneSizes: {},
    }),
  writeSettings: () => Promise.resolve(undefined),
  readSarif: (path) => Promise.resolve(path === SQL_SARIF ? SQL_ADVICE : FINDINGS),
  readGraph: () => Promise.resolve(GRAPH),
  readFixResult: (request) =>
    Promise.resolve({
      relPath: request.relPath,
      originalText: COBOL_TEXT,
      fixedText: FIXED_COBOL_TEXT,
    }),
  readReportHtml: () => Promise.resolve(REPORT_HTML),
  readReportText: () => Promise.resolve("COBOL Insight 解析レポート\n資産 5 ・ 指摘 2"),
  readAssetInventory: () => Promise.resolve(INVENTORY),
  readSourceText: (request) => {
    const text = request.path.includes("SYKCPY1") ? COPYBOOK_TEXT : COBOL_TEXT;
    const lines = text.split(/\r\n|\n/);
    const limited = request.maxLines === undefined ? lines : lines.slice(0, request.maxLines);
    return Promise.resolve({
      text: limited.join("\n"),
      codepage: "Shift_JIS",
      truncated: limited.length < lines.length,
      unsupported: false,
    });
  },
  /** 書き戻しは行わず、engine が返す要約だけを模す(原本にも engine にも触れない)。 */
  saveSource: (request) =>
    Promise.resolve({
      written: true,
      path: `C:/smoke/assets/${request.path}`,
      changedLineFrom: 1,
      changedLineTo: 1,
      reparseErrors: [],
      error: "",
      exitCode: 0,
    }),
  readCopyExpansion: () => Promise.resolve({ programs: [] }),
  readTranspileArtifacts: () =>
    Promise.resolve({
      files: [
        { name: "Syk001.java", language: "java", text: GENERATED_JAVA },
        { name: "syk001.py", language: "python", text: GENERATED_PYTHON },
      ],
      lineMap: LINE_MAP,
    }),
  importSource: (request) =>
    Promise.resolve({
      status: "written",
      relPath: `cobol/${request.fileName}.cbl`,
      lineCount: request.lines.length,
    }),
  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);
