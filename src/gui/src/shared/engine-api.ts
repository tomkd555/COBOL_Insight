/**
 * renderer↔main の IPC 契約(型とチャネル名)。preload・main・renderer の3ビルドが共有する。
 * ここで公開する API が扱うのは、engine CLI サブプロセスの起動、その成果物ファイルの読取、
 * および資産フォルダへのソース取込である。
 * ネットワーク通信・ソケットは用いない。やり取りはすべて renderer 発の invoke/handle で1往復し、
 * main から renderer へ送る通知チャネルは持たない。
 */

import type { ImportAssetKind } from "./assetImport";
import type { AppSettings } from "./appSettings";

/** engine CLI の起動対象サブコマンド。fix は preview/apply を別値として区別する。 */
export type EngineSubcommand =
  | "scan"
  | "callgraph"
  | "lint"
  | "sql-advise"
  | "report"
  | "transpile"
  | "fix-preview"
  | "fix-apply"
  | "rules";

/** 全解析コマンドが共有する入力・コピー句探索パス・コードページ手動指定。 */
export interface EngineCommonOptions {
  /** 資産フォルダ(picocli の位置引数 INPUT_DIR)。 */
  inputDir: string;
  /** コピー句探索パス(--copybook-path、繰り返し指定)。 */
  copybookPaths?: string[];
  /** ファイル単位のコードページ手動指定(--codepage FILE=CHARSET)。キーは相対パスまたはファイル名。 */
  codepageOverrides?: Record<string, string>;
}

export interface ScanRequest extends EngineCommonOptions {
  /** SQLite プロジェクトファイル(--db)。 */
  db?: string;
  /** COPY 展開の対応表の書き出し先(--copy-expansion)。省略時は engine の作業ディレクトリへ書く。 */
  copyExpansion?: string;
}

export interface CallgraphRequest extends EngineCommonOptions {
  db?: string;
  jsonFile?: string;
  dotFile?: string;
  svgFile?: string;
  pngFile?: string;
}

export interface LintRequest extends EngineCommonOptions {
  /** SARIF 2.1.0 出力ファイル(--sarif)。 */
  sarifFile?: string;
  /** 無効化するルールID(--disable-rule、繰り返し指定)。 */
  disabledRules?: string[];
  /** 利用者定義ルールの定義ファイル(--user-rules)。 */
  userRulesFile?: string;
}

/** sql-advise は SQL 文モデルを見るルールだけを走らせるため、利用者定義ルールを受けない。 */
export type SqlAdviseRequest = Omit<LintRequest, "userRulesFile">;

export interface ReportRequest extends EngineCommonOptions {
  db?: string;
  htmlFile?: string;
  textFile?: string;
  disabledRules?: string[];
  userRulesFile?: string;
}

/** rules サブコマンドの起動。資産フォルダを取らない唯一のサブコマンドである。 */
export interface RulesRequest {
  /** 一覧へ併せて載せる利用者定義ルールの定義ファイル(--user-rules)。 */
  userRulesFile?: string;
}

export interface TranspileRequest extends EngineCommonOptions {
  db?: string;
  language?: "python" | "java" | "both";
  outDir?: string;
}

export interface FixPreviewRequest extends EngineCommonOptions {
  htmlFile?: string;
}

export interface FixApplyRequest extends EngineCommonOptions {
  outDir?: string;
}

/** サブコマンドと、その型付きリクエストを対にした判別可能ユニオン。引数組立の入力とする。 */
export type EngineInvocation =
  | { subcommand: "scan"; request: ScanRequest }
  | { subcommand: "callgraph"; request: CallgraphRequest }
  | { subcommand: "lint"; request: LintRequest }
  | { subcommand: "sql-advise"; request: SqlAdviseRequest }
  | { subcommand: "report"; request: ReportRequest }
  | { subcommand: "transpile"; request: TranspileRequest }
  | { subcommand: "fix-preview"; request: FixPreviewRequest }
  | { subcommand: "fix-apply"; request: FixApplyRequest }
  | { subcommand: "rules"; request: RulesRequest };

/** 起動で生成した成果物ファイルの解決済みパス。renderer はここを起点に成果物を読む。 */
export interface EngineOutputs {
  db?: string;
  sarif?: string;
  json?: string;
  dot?: string;
  svg?: string;
  png?: string;
  html?: string;
  text?: string;
  outDir?: string;
  /** scan が書く COPY 展開の対応表(JSON)。 */
  copyExpansion?: string;
}

/** サブプロセス起動の結果。stdout 末尾のサマリ JSON をパースして summary へ格納する。 */
export interface EngineResult {
  subcommand: EngineSubcommand;
  exitCode: number;
  /** stdout 末尾の1行サマリ JSON(callgraph 無指定時はグラフ本体 JSON)。無ければ null。 */
  summary: Record<string, unknown> | null;
  stdout: string;
  stderr: string;
  outputs: EngineOutputs;
}

/**
 * ルール1件のメタ情報と説明。engine の `rules --json` が返す形をそのまま写す。
 * 画面はルール名・カテゴリ・説明を自前で持たず、これを唯一の供給源とする。
 */
export interface RuleCatalogEntry {
  id: string;
  name: string;
  category: string;
  /** engine の Severity(HIGH/MEDIUM/LOW/ADVISORY)。画面の重大度へはここから写す。 */
  severity: string;
  /** 解析段階(SYNTAX/CONTROL_FLOW/DATA_FLOW)。 */
  phase: string;
  /** 修正案 diff を生成できるルールか(engine の FixProducer の有無)。 */
  hasFix: boolean;
  /** 組み込みか、利用者が定義したものか。 */
  source: "builtin" | "user";
  /** 何を検出するか。 */
  summary: string;
  /** なぜ問題か。 */
  rationale: string;
  /** 検出条件。対象外の扱いも含む。 */
  detection: string;
  /** どう直すか。 */
  remedy: string;
  /** 該当する例。書けないルールでは空文字。 */
  badExample: string;
  /** 直した例。書けないルールでは空文字。 */
  goodExample: string;
}

/** ルール一覧の取得結果。定義の誤りは一覧を止めず、userRuleErrors で伝える。 */
export interface RuleCatalog {
  rules: RuleCatalogEntry[];
  userRuleErrors: string[];
}

/** 利用者定義ルール1件。定義ファイル(user-rules.json)の rules 要素と同じ形である。 */
export interface UserRuleDefinition {
  /** U で始まる ID。組み込み(R・S)と衝突させない。 */
  id: string;
  name: string;
  category: string;
  /** HIGH/MEDIUM/LOW/ADVISORY。 */
  severity: string;
  /** 走査する資産の種別(COBOL/COPYBOOK/BMS)。 */
  targets: string[];
  /** 一致を探す正規表現(Java の構文)。 */
  pattern: string;
  /** 同じ行がこれにも一致する場合は検出しない。空文字は指定なし。 */
  excludePattern: string;
  /** 大小を区別しないか。 */
  ignoreCase: boolean;
  /** 行全体を対象にするか。false のとき COBOL 系は注記行を除き 8〜72 桁を見る。 */
  wholeLine: boolean;
  /** 指摘のメッセージ。${match} は一致した文字列へ置き換わる。 */
  message: string;
  /** なぜ問題か。空文字なら engine が既定文を補う。 */
  rationale: string;
  /** どう直すか。空文字なら engine が既定文を補う。 */
  remedy: string;
}

/** 定義ファイルの中身。version は engine の UserRuleLoader.SUPPORTED_VERSION と揃える。 */
export interface UserRulesFile {
  version: number;
  rules: UserRuleDefinition[];
}

/** SARIF 2.1.0 の1件の検出結果を、画面が要する形へ平坦化したもの。 */
export interface SarifFinding {
  ruleId: string;
  ruleIndex?: number;
  /** SARIF の level(error/warning/note/none)。 */
  level: string;
  message: string;
  /** 対象ファイルの相対パス(physicalLocation.artifactLocation.uri)。 */
  file: string;
  startLine: number;
  startColumn: number;
}

/** 呼出関係グラフのノード(CallGraph.toJson の nodes 要素)。 */
export interface CallGraphNode {
  id: string;
  /** NodeKind(JOB/STEP/PROGRAM/PARAGRAPH/DATASET/DB2_TABLE/UNRESOLVED/EXTERNAL_UTILITY/TRANSACTION/BMS_MAP)。 */
  kind: string;
  label: string;
  attributes: Record<string, string>;
}

/** 呼出関係グラフのエッジ(CallGraph.toJson の edges 要素)。 */
export interface CallGraphEdge {
  from: string;
  to: string;
  /** EdgeKind(CALL/EXECUTION/REFERENCE/TRANSACTION_TRANSITION/MAP_REFERENCE)。 */
  kind: string;
  /** Resolution(CONSTANT/DATAFLOW/UNRESOLVED)。破線描画は DATAFLOW/UNRESOLVED を条件にする。 */
  resolution: string;
}

export interface CallGraphData {
  nodes: CallGraphNode[];
  edges: CallGraphEdge[];
}

/** fix の1ファイル分の原本・修正後テキスト対。Monaco DiffEditor へそのまま渡せる形。 */
export interface FixDiff {
  relPath: string;
  originalText: string;
  fixedText: string;
}

/** コピー句由来の修正の影響範囲。原本は書き換えず、取り込むプログラム一覧を併記する。 */
export interface FixCopybookImpact {
  copybook: string;
  importers: string[];
}

/** fix preview/apply のサマリ JSON を型付き情報へ正規化したもの。 */
export interface FixSummaryInfo {
  /** 修正対象ファイル(preview の fixedFiles、apply の writtenFiles)。 */
  files: string[];
  copybookFixes: FixCopybookImpact[];
  fixCount: number;
  analysisErrors: number;
  /** apply のみ。再パース検証で失敗した件数。 */
  reparseFailures?: number;
}

/** readFixResult の入力。原本(資産フォルダ側)と修正後(apply 出力先)の対応を渡す。 */
export interface FixResultRequest {
  originalPath: string;
  fixedPath: string;
  relPath: string;
}

/** 資産一覧の1件(SQLite の SOURCE を NODE.type・FINDING 件数と結合したもの)。 */
export interface AssetInventoryItem {
  id: number;
  /** 資産フォルダからの相対パス(例: cobol/SYK001.cbl)。 */
  path: string;
  /** ファイル名(path の末尾要素)。 */
  name: string;
  /** NODE.type(PROGRAM/JCL/COPYBOOK/BMS)。ノード未登録時は UNKNOWN。 */
  type: string;
  /** 検出コードページ(SOURCE.codepage)。復号失敗時は null。 */
  codepage: string | null;
  byteSize: number;
  /** グラフ層(id ≥ 1e12)を除いた、この資産に紐づく scan 由来 finding 件数。 */
  findingCount: number;
}

/**
 * ソース本文の読取要求。復号は表示のためだけに行い、構文解析・判定は engine CLI が担う。
 * path は inputDir 配下に限る(境界外の読取は main が拒む)。
 */
export interface SourceTextRequest {
  /**
   * 境界検査の基準ディレクトリ。AppState の project.inputDir、またはコピー句の探索では
   * project.copybookPaths の1件を渡す。main は symlink を解決した実体パスで配下判定を行う。
   */
  inputDir: string;
  /** 読むファイル。inputDir からの相対パス、または inputDir 配下の絶対パス。 */
  path: string;
  /** 復号に用いるコードページ(SOURCE.codepage の検出値、または画面での手動指定)。null は検出失敗。 */
  codepage: string | null;
  /** 先頭 N 行で打ち切る。省略時は全文を返す。 */
  maxLines?: number;
}

/** readSourceText の結果。復号非対応・復号不能は unsupported で示し、text は空にする。 */
export interface SourceTextResult {
  text: string;
  /** 復号に用いたコードページ表示名。非対応のときは要求値(不明なら "不明")を返す。 */
  codepage: string;
  /** maxLines で打ち切ったか。 */
  truncated: boolean;
  /** EBCDIC(CP930/CP939)またはコードページ不明で復号しなかったか。 */
  unsupported: boolean;
}

/** transpile 成果物の読取要求。outDir は transpile の --out、cobolRelPath は SOURCE.path と同形。 */
export interface TranspileArtifactsRequest {
  /** transpile の出力先。TranspileRunner はこの直下へ平坦に生成物を書く。 */
  outDir: string;
  /** LINE_MAP を持つ SQLite プロジェクトファイル。 */
  dbPath: string;
  /** 対訳を読む COBOL 本体の相対パス(例: cobol/SYK001.cbl)。 */
  cobolRelPath: string;
}

/** 逐語対訳の生成言語。生成物の拡張子(.py/.java)から決まる。 */
export type TranspileLanguage = "python" | "java";

/** transpile が出力先直下へ書いた生成物1件。 */
export interface TranspileGeneratedFile {
  /** ファイル名(出力先直下の平坦な名前)。 */
  name: string;
  language: TranspileLanguage;
  /** 本文(TranspileRunner は UTF-8 で書く)。 */
  text: string;
}

/** LINE_MAP の1行(Schema.java の LINE_MAP 表)。行範囲は 1 起点で両端を含む。 */
export interface LineMapEntry {
  id: number;
  cobolLineStart: number;
  cobolLineEnd: number;
  /** 対応する生成ファイル名(TranspileGeneratedFile.name と一致する)。 */
  genFile: string;
  genLineStart: number;
  genLineEnd: number;
  /** 対応の種別。engine の MappingKindCodec が書く "1:1" / "1:N" / "N:1" のいずれか。 */
  kind: string;
  /** 直訳できなかった箇所の注記。空文字は注記なし。 */
  note: string;
  /** 生成物側のアンカー識別子。 */
  anchorId: string;
}

/** ソースビューアが要する transpile 成果物一式(生成物本文と行対応表)。 */
export interface TranspileArtifacts {
  files: TranspileGeneratedFile[];
  lineMap: LineMapEntry[];
}

/**
 * scan が書く COPY 展開の対応表のファイル名。engine の既定の出力先(cobol-insight.db)と同じ場所へ
 * 置くため、main は引数の組立で、renderer はプロジェクトファイルの位置から、この名前で同じパスを指す。
 */
export const COPY_EXPANSION_FILE_NAME = "cobol-insight-copy-expansion.json";

/** COPY 文1件の展開行。 */
export interface CopyExpansionLine {
  /** コピー句の中での行番号(1 起点)。 */
  copybookLine: number;
  /**
   * REPLACING 適用後のテキスト。engine の前処理を通した後の姿であり、注記行・一連番号欄(1〜6桁)・
   * 識別欄(73桁以降)は空白になる。原本の姿を要する側は copybookLine で引き直す。
   */
  text: string;
}

/** COPY 文1件のインライン展開。入れ子の COPY と暗黙のコピー句(SQLCA)は対象外。 */
export interface CopyExpansion {
  /** 原本の COPY 文の行番号(1 起点)。 */
  copyStatementLine: number;
  copybookName: string;
  /** 資産フォルダからの相対パス。資産フォルダの外にあるコピー句は絶対パス。 */
  copybookPath: string;
  lines: CopyExpansionLine[];
}

/** 1プログラム分の展開。COPY 文を持たないプログラムは現れない。 */
export interface CopyExpansionProgram {
  /** 資産フォルダからの相対パス(AssetInventoryItem.path と同形)。 */
  path: string;
  programId: string;
  expansions: CopyExpansion[];
}

/** scan の --copy-expansion が書く対応表。programs は相対パス昇順。 */
export interface CopyExpansionData {
  programs: CopyExpansionProgram[];
}

/**
 * 端末エミュレータの画面から複写した本文の取込要求。桁の切り出しは renderer で済ませ、
 * 保存する行の並びとして渡す。
 */
export interface ImportSourceRequest {
  /** 取込先の資産フォルダ。 */
  inputDir: string;
  /** 資産の種別。保存先のフォルダと拡張子を決める。 */
  kind: ImportAssetKind;
  /** ファイル名。種別の拡張子で終わっていなければ補う。 */
  fileName: string;
  /** 保存する本文の各行。 */
  lines: string[];
  /** 同名のファイルがあるとき上書きしてよいか。 */
  overwrite: boolean;
}

/** 取込の結果。exists は同名のファイルがあり、上書きの許可を得ていないことを表す。 */
export interface ImportSourceResult {
  status: "written" | "exists";
  /** 資産フォルダからの相対パス(例: cobol/SYK001.cbl)。 */
  relPath: string;
  /** 書き込んだ行数。exists のときは0。 */
  lineCount: number;
}

/**
 * engine が成果物を書く位置。main が userData(配布時は展開先直下の data/)を基準に決め、
 * renderer はこれを engine の引数へそのまま渡す。engine の既定値と作業ディレクトリの一致に
 * 頼ると、書かれた位置と読みにいく位置が食い違いうるため、常に絶対パスで指定する。
 */
export interface EngineOutputPaths {
  readonly db: string;
  readonly lintSarif: string;
  readonly sqlAdviseSarif: string;
  readonly copyExpansion: string;
  /** 利用者定義ルールの定義ファイル。engine の成果物ではなく、画面が書いて engine が読む。 */
  readonly userRules: string;
}

/** renderer へ contextBridge で公開する API の型。window.cobolInsight として参照する。 */
export interface CobolInsightApi {
  runScan(request: ScanRequest): Promise<EngineResult>;
  runCallgraph(request: CallgraphRequest): Promise<EngineResult>;
  runLint(request: LintRequest): Promise<EngineResult>;
  runSqlAdvise(request: SqlAdviseRequest): Promise<EngineResult>;
  runReport(request: ReportRequest): Promise<EngineResult>;
  runTranspile(request: TranspileRequest): Promise<EngineResult>;
  runFixPreview(request: FixPreviewRequest): Promise<EngineResult>;
  runFixApply(request: FixApplyRequest): Promise<EngineResult>;
  /** 実行中の engine を止める。実行中でなければ何もしない。 */
  cancelRun(): Promise<void>;
  /** 資産フォルダ選択ダイアログを開く。キャンセルは null。 */
  selectInputFolder(): Promise<string | null>;
  /** 指定パスがディレクトリとして実在するか(コピー句探索パスの検査に用いる)。 */
  checkDirectoryExists(path: string): Promise<boolean>;
  /** engine の成果物を書く位置(絶対パス)。解析実行のたびに引数へ指定する。 */
  getOutputPaths(): Promise<EngineOutputPaths>;
  readSarif(path: string): Promise<SarifFinding[]>;
  readCallgraphJson(path: string): Promise<CallGraphData>;
  readFixResult(request: FixResultRequest): Promise<FixDiff>;
  readReportHtml(path: string): Promise<string>;
  readReportText(path: string): Promise<string>;
  readAssetInventory(dbPath: string): Promise<AssetInventoryItem[]>;
  readSourceText(request: SourceTextRequest): Promise<SourceTextResult>;
  readTranspileArtifacts(request: TranspileArtifactsRequest): Promise<TranspileArtifacts>;
  /** scan が書いた COPY 展開の対応表を読む。 */
  readCopyExpansion(path: string): Promise<CopyExpansionData>;
  /** 端末エミュレータから複写した本文を、資産フォルダへソースファイルとして書き出す。 */
  importSource(request: ImportSourceRequest): Promise<ImportSourceResult>;
  /** 組み込みと利用者定義を併せたルール一覧を engine から取得する。 */
  listRules(request: RulesRequest): Promise<RuleCatalog>;
  /** 利用者定義ルールの定義ファイルを読む。未作成なら空の定義を返す。 */
  readUserRules(path: string): Promise<UserRulesFile>;
  /** 利用者定義ルールの定義ファイルを書く。 */
  writeUserRules(path: string, file: UserRulesFile): Promise<void>;
  /** 保存してある画面の設定を読む。未保存なら空の設定を返す。 */
  readSettings(): Promise<AppSettings>;
  /** 画面の設定を保存する。保存先は main が userData 直下に決める。 */
  writeSettings(settings: AppSettings): Promise<void>;
  versions: { chrome: string; node: string; electron: string };
}

/** IPC チャネル名。preload(invoke)と main(handle)で同一の値を使う。 */
export const ENGINE_CHANNELS = {
  runScan: "engine:run-scan",
  runCallgraph: "engine:run-callgraph",
  runLint: "engine:run-lint",
  runSqlAdvise: "engine:run-sql-advise",
  runReport: "engine:run-report",
  runTranspile: "engine:run-transpile",
  runFixPreview: "engine:run-fix-preview",
  runFixApply: "engine:run-fix-apply",
  cancelRun: "engine:cancel-run",
  selectInputFolder: "dialog:select-input-folder",
  checkDirectoryExists: "fs:check-directory-exists",
  getOutputPaths: "fs:get-output-paths",
  readSarif: "artifact:read-sarif",
  readCallgraphJson: "artifact:read-callgraph-json",
  readFixResult: "artifact:read-fix-result",
  readReportHtml: "artifact:read-report-html",
  readReportText: "artifact:read-report-text",
  readAssetInventory: "artifact:read-asset-inventory",
  readSourceText: "artifact:read-source-text",
  readTranspileArtifacts: "artifact:read-transpile-artifacts",
  readCopyExpansion: "artifact:read-copy-expansion",
  importSource: "asset:import-source",
  listRules: "engine:list-rules",
  readUserRules: "rules:read-definitions",
  writeUserRules: "rules:write-definitions",
  readSettings: "settings:read",
  writeSettings: "settings:write",
} as const;
