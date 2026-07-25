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
  { id: 2, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 4200, findingCount: 0 },
  { id: 3, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 3800, findingCount: 1 },
  { id: 4, path: "copybook/SYKCPY1.cpy", name: "SYKCPY1.cpy", type: "COPYBOOK", codepage: "windows-31j", byteSize: 640, findingCount: 0 },
  { id: 5, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "windows-31j", byteSize: 900, findingCount: 0 },
];

const FINDINGS = [
  { ruleId: "R004", level: "warning", message: "ON SIZE ERROR 句が無い。", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 12 },
  { ruleId: "R017", level: "error", message: "FILE STATUS の検査が無い。", file: "cobol/SYK002.cbl", startLine: 24, startColumn: 12 },
];

const SQL_ADVICE = [
  { ruleId: "S001", level: "warning", message: "SELECT * を列指定へ改める。", file: "cobol/SYK001.cbl", startLine: 10, startColumn: 12 },
];

const CALLGRAPH = {
  nodes: [
    { id: "job:SYKD010", kind: "JOB", label: "SYKD010", attributes: {} },
    { id: "step:SYKD010.STEP010", kind: "STEP", label: "STEP010", attributes: {} },
    { id: "program:SYK001", kind: "PROGRAM", label: "SYK001", attributes: {} },
    { id: "program:SYK002", kind: "PROGRAM", label: "SYK002", attributes: {} },
    { id: "dataset:SYKT.D250718.ORDER.DAILY", kind: "DATASET", label: "SYKT.D250718.ORDER.DAILY", attributes: {} },
    { id: "db2:SYKDB.ZAIKOM", kind: "DB2_TABLE", label: "SYKDB.ZAIKOM", attributes: {} },
    { id: "unresolved:WS-PROG-NAME", kind: "UNRESOLVED", label: "WS-PROG-NAME", attributes: { variable: "WS-PROG-NAME" } },
    { id: "transaction:SYK8", kind: "TRANSACTION", label: "SYK8", attributes: {} },
  ],
  edges: [
    { from: "job:SYKD010", to: "step:SYKD010.STEP010", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP010", to: "program:SYK001", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP010", to: "dataset:SYKT.D250718.ORDER.DAILY", kind: "REFERENCE", resolution: "CONSTANT" },
    { from: "program:SYK001", to: "program:SYK002", kind: "CALL", resolution: "CONSTANT" },
    { from: "program:SYK001", to: "db2:SYKDB.ZAIKOM", kind: "REFERENCE", resolution: "CONSTANT" },
    { from: "program:SYK002", to: "unresolved:WS-PROG-NAME", kind: "CALL", resolution: "UNRESOLVED" },
    { from: "transaction:SYK8", to: "program:SYK002", kind: "TRANSACTION_TRANSITION", resolution: "DATAFLOW" },
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
      engineResult("callgraph", {
        db: DB_PATH,
        ...(request.jsonFile === undefined ? {} : { json: CALLGRAPH_JSON }),
        ...(request.svgFile === undefined ? {} : { svg: request.svgFile }),
        ...(request.pngFile === undefined ? {} : { png: request.pngFile }),
      }),
    ),
  runLint: () => Promise.resolve(engineResult("lint", { sarif: LINT_SARIF })),
  runSqlAdvise: () => Promise.resolve(engineResult("sql-advise", { sarif: SQL_SARIF })),
  runReport: (request) =>
    Promise.resolve(
      engineResult(
        "report",
        {
          ...(request.htmlFile === undefined ? {} : { html: request.htmlFile }),
          ...(request.textFile === undefined ? {} : { text: request.textFile }),
        },
        { assets: 5, findings: 2, sqlAdvice: 1, callEdges: 7, analysisErrors: 0 },
      ),
    ),
  runTranspile: () => Promise.resolve(engineResult("transpile", { outDir: "C:\\smoke\\transpile" })),
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
  selectInputFolder: () => Promise.resolve("C:\\smoke\\assets"),
  readSarif: (path) => Promise.resolve(path === SQL_SARIF ? SQL_ADVICE : FINDINGS),
  readCallgraphJson: () => Promise.resolve(CALLGRAPH),
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
  readTranspileArtifacts: () =>
    Promise.resolve({
      files: [
        { name: "Syk001.java", language: "java", text: GENERATED_JAVA },
        { name: "syk001.py", language: "python", text: GENERATED_PYTHON },
      ],
      lineMap: LINE_MAP,
    }),
  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);
