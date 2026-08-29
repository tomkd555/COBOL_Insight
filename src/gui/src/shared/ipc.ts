/**
 * The single renderer/main IPC contract: types and channel names shared by the preload, main and
 * renderer builds.
 *
 * Everything the renderer can reach lives here. The engine CLI is spawned as a child process and its
 * artefacts are read from files; there is no network, no socket and no main-to-renderer push
 * channel. Every exchange is one renderer-initiated `invoke` round trip.
 */

import type { AppSettings } from "./settings";
import type { RulesFile } from "./rulesFile";

/* ------------------------------------------------------------------ engine invocation */

/** The engine subcommands the GUI can launch. `fix` splits into preview and apply. */
export type EngineSubcommand =
  | "scan"
  | "call-graph"
  | "lint"
  | "sql-lint"
  | "report"
  | "translate"
  | "fix-preview"
  | "fix-apply"
  | "rules"
  | "save"
  | "decode";

/** Options every asset-folder command shares. */
export interface EngineCommonOptions {
  /** The asset folder (picocli positional argument INPUT_DIR). */
  inputDir: string;
  /** Copybook search paths (repeated --copybook-path). */
  copybookPaths?: string[];
  /** Per-file codepage overrides (--codepage FILE=CHARSET). Keys are relative paths or file names. */
  codepageOverrides?: Record<string, string>;
  /**
   * The one rule configuration file (--rules). It carries both the enable/disable state of the
   * built-in rules and the user-defined rules; V1's separate --rule-config and --user-rules are
   * gone.
   */
  rulesFile?: string;
}

export interface ScanRequest extends EngineCommonOptions {
  /** The SQLite project file (--db). */
  db?: string;
  /** Where to write the COPY expansion table (--copy-expansion). */
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
  /** SARIF 2.1.0 output file (--sarif). */
  sarifFile?: string;
}

/** sql-lint runs only the rules that read the SQL model, but takes the same options. */
export type SqlLintRequest = LintRequest;

export interface ReportRequest extends EngineCommonOptions {
  db?: string;
  htmlFile?: string;
  textFile?: string;
}

export interface TranslateRequest extends EngineCommonOptions {
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

/** The only subcommand that takes no asset folder. */
export interface RulesRequest {
  /** The rule configuration file whose contents shape `enabled` and add the user-defined rules. */
  rulesFile?: string;
}

/**
 * Writes the edited text back over the original. The edited text is handed over as a UTF-8 file
 * because Node cannot encode Shift_JIS or EBCDIC; the engine re-encodes to the original codepage.
 */
export interface SaveRequest {
  /** Absolute path of the original to overwrite (--file). */
  file: string;
  /** UTF-8 file holding the full edited text (--edited). */
  editedFile: string;
  /** Manual codepage for the original (--codepage). */
  codepage?: string;
  /** Copybook search paths used by the reparse verification (--copybook-path). */
  copybookPaths?: string[];
  /** The project file the recorded codepage is read from (--db). */
  db?: string;
}

/**
 * Decodes one source file to text plus its per-line byte geometry. The engine owns every codepage
 * (Shift_JIS, UTF-8, EBCDIC CP930/CP939); the renderer never sees bytes.
 */
export interface DecodeRequest {
  /** Absolute path of the file to decode (--file). */
  file: string;
  /** Manual codepage (--codepage). Omitted means "let the engine detect it". */
  codepage?: string;
  /** The project file holding the codepage recorded at scan time (--db). */
  db?: string;
  /** Where the engine writes the decode result JSON (--out). */
  outFile: string;
}

/** Subcommand paired with its typed request. This union is the input to argv assembly. */
export type EngineInvocation =
  | { subcommand: "scan"; request: ScanRequest }
  | { subcommand: "call-graph"; request: CallgraphRequest }
  | { subcommand: "lint"; request: LintRequest }
  | { subcommand: "sql-lint"; request: SqlLintRequest }
  | { subcommand: "report"; request: ReportRequest }
  | { subcommand: "translate"; request: TranslateRequest }
  | { subcommand: "fix-preview"; request: FixPreviewRequest }
  | { subcommand: "fix-apply"; request: FixApplyRequest }
  | { subcommand: "rules"; request: RulesRequest }
  | { subcommand: "save"; request: SaveRequest }
  | { subcommand: "decode"; request: DecodeRequest };

/** Resolved paths of the artefacts an invocation wrote. The renderer reads artefacts from here. */
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
  copyExpansion?: string;
}

/** The outcome of one child-process run. */
export interface EngineResult {
  subcommand: EngineSubcommand;
  /**
   * The engine encodes finding severity in its exit code (0 = clean, 1 = warnings, 2 = errors), so a
   * non-zero code is not a failed run. -1 means the process was killed and reported no code.
   */
  exitCode: number;
  /** The trailing summary JSON line of stdout, or null when there is none. */
  summary: Record<string, unknown> | null;
  stdout: string;
  stderr: string;
  outputs: EngineOutputs;
}

/* ------------------------------------------------------------------ decode */

/** Byte geometry of one decoded line: its byte length and the character offsets of the COBOL areas. */
export interface DecodedLine {
  /** Length of the line in the original encoding, newline excluded. */
  byteLength: number;
  /**
   * Character offsets of the fixed-format column boundaries, as [sequence, indicator, area A,
   * identification] — that is, the character index where byte columns 7, 8, 12 and 73 start.
   */
  boundaries: [number, number, number, number];
}

/** Timestamp and size of the file at the moment it was decoded, for stale-edit detection. */
export interface SourceStamp {
  mtimeMs: number;
  byteSize: number;
}

export interface DecodeResult {
  text: string;
  /** The codepage actually used. */
  codepage: string;
  /** Whether the codepage was detected rather than given. */
  detected: boolean;
  /** Whether the EBCDIC shift-out/shift-in control bytes appear in the file. */
  soSiPresent: boolean;
  lines: DecodedLine[];
  stamp: SourceStamp;
  /** Non-empty when the file could not be decoded; `text` is then empty. */
  error: string;
}

/** Request to decode one file inside an allowed base directory. */
export interface DecodeSourceRequest {
  /** Boundary for the path check: the asset folder, or one of the copybook search paths. */
  baseDir: string;
  /** The file to decode, relative to baseDir or absolute inside it. */
  path: string;
  codepage?: string;
  /** The project file holding the codepage recorded at scan time. */
  db?: string;
}

/* ------------------------------------------------------------------ save */

/** One error reported by the reparse verification. */
export interface SaveReparseError {
  line: number;
  message: string;
}

/**
 * The save summary.
 *
 * exitCode 0 means written (or nothing changed), 1 means written but the reparse found problems
 * (the write is not rolled back), 2 means not written. Copybooks, JCL and BMS cannot be parsed on
 * their own and therefore always report 1.
 */
export interface SaveResult {
  written: boolean;
  /** Absolute path of the original that was written (separators normalised to /). */
  path: string;
  changedLineFrom: number;
  /** Last changed line, inclusive. A pure insertion reports changedLineFrom - 1. */
  changedLineTo: number;
  reparseErrors: SaveReparseError[];
  /** Why the write was refused. Empty when it was not. */
  error: string;
  exitCode: number;
}

/** Request to write edited text back over an original inside an allowed base directory. */
export interface SaveSourceRequest {
  baseDir: string;
  path: string;
  /** The full edited text. LF line endings are fine; the engine matches the original's style. */
  editedText: string;
  codepage?: string;
  copybookPaths?: string[];
}

/* ------------------------------------------------------------------ rules */

/** One rule's metadata and prose. The engine is the only source of these; the GUI stores none. */
export interface RuleCatalogEntry {
  id: string;
  name: string;
  category: string;
  /** Engine severity (HIGH/MEDIUM/LOW/ADVISORY). */
  severity: string;
  /** Analysis phase (SYNTAX/CONTROL_FLOW/DATA_FLOW). */
  phase: string;
  /** Whether the rule can produce a fix diff. */
  hasFix: boolean;
  /** Built-in or user-defined. */
  source: "builtin" | "user";
  /** Whether the rule is in effect, given the rule configuration file. */
  enabled: boolean;
  /** Whether the rule is on when the configuration file says nothing about it. */
  defaultEnabled: boolean;
  /** Which subcommands run this rule (lint, sql-lint, ...). */
  commands: string[];
  /** Which asset kinds the rule inspects (COBOL/COPYBOOK/JCL/BMS). */
  targets: string[];
  /** Which analyses the rule needs (cfg, dataflow, sql, ...). */
  needs: string[];
  /** What it detects. */
  summary: string;
  /** Why it matters. */
  rationale: string;
  /** How it detects, exclusions included. */
  detection: string;
  /** How to fix it. */
  remedy: string;
  /** An offending example; empty when none can be written. */
  badExample: string;
  /** The corrected example; empty when none can be written. */
  goodExample: string;
}

/** The rule listing. A malformed definition does not suppress the list; it lands in ruleErrors. */
export interface RuleCatalog {
  rules: RuleCatalogEntry[];
  /** Problems the engine found in the rule configuration file (bad definitions, unknown ids). */
  ruleErrors: string[];
}

/** The outcome of validating raw rule-file text without committing it. */
export interface RulesValidation {
  ok: boolean;
  errors: string[];
  /** The catalog the engine produced from the candidate text, or null when it could not parse it. */
  parsed: RuleCatalog | null;
}

/* ------------------------------------------------------------------ artefacts */

/** One row of the asset inventory (SOURCE joined with NODE.type and the scan finding count). */
export interface AssetInventoryItem {
  id: number;
  /** Path relative to the asset folder, e.g. cobol/SYK001.cbl. */
  path: string;
  /** The last segment of `path`. */
  name: string;
  /** NODE.type (PROGRAM/JCL/COPYBOOK/BMS). UNKNOWN when no node was registered. */
  type: string;
  /** SOURCE.codepage; null when decoding failed. */
  codepage: string | null;
  byteSize: number;
  findingCount: number;
}

/** One SARIF 2.1.0 result flattened into what the screens need. */
export interface SarifFinding {
  ruleId: string;
  ruleIndex?: number;
  /** SARIF level (error/warning/note/none). */
  level: string;
  message: string;
  /** Path relative to the asset folder. */
  file: string;
  startLine: number;
  startColumn: number;
}

/** One node of the call graph (NODE table). */
export interface GraphNode {
  id: number;
  /** NODE.type (JOB/STEP/PROGRAM/JCL/COPYBOOK/BMS/DATASET/DB2_TABLE and so on). */
  type: string;
  label: string;
}

/** One call-graph edge (CALL_EDGE table). */
export interface GraphEdge {
  from: number;
  to: number;
  /** EdgeKind (CALL/EXECUTION/REFERENCE/TRANSACTION_TRANSITION/MAP_REFERENCE). */
  kind: string;
  /** Resolution (CONSTANT/DATAFLOW/UNRESOLVED); null when not recorded. */
  resolution: string | null;
  /** Execution order among the edges leaving one node (1-based). 0 when no order applies. */
  seq: number;
  /** The calling line, when known. */
  line: number | null;
}

/** One paragraph of one program (PARAGRAPH joined to the asset through PROGRAM). */
export interface GraphParagraph {
  id: number;
  programSourceId: number;
  name: string;
  startLine: number;
  endLine: number;
}

/** One paragraph-to-paragraph flow (PARAGRAPH_EDGE table). */
export interface GraphParagraphEdge {
  programSourceId: number;
  from: number;
  /** Resolved target PARAGRAPH.id, or null when only the name is known. */
  to: number | null;
  toName: string;
  /** PERFORM (returns), GOTO (does not return) or FALLTHROUGH (flows into the next paragraph). */
  kind: string;
  line: number | null;
  seq: number;
}

/** Everything the call-graph view needs: the inter-program layer and the paragraph layer. */
export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
  paragraphs: GraphParagraph[];
  paragraphEdges: GraphParagraphEdge[];
}

/** One expanded line of a COPY statement. */
export interface CopyExpansionLine {
  /** Line number inside the copybook (1-based). */
  copybookLine: number;
  /** Text after REPLACING; comment lines, the sequence area and the identification area are blank. */
  text: string;
}

/** One COPY statement expanded inline. Nested COPY and implicit copybooks are out of scope. */
export interface CopyExpansion {
  copyStatementLine: number;
  copybookName: string;
  /** Relative to the asset folder; absolute for copybooks outside it. */
  copybookPath: string;
  lines: CopyExpansionLine[];
}

/** One program's expansions. Programs with no COPY statement do not appear. */
export interface CopyExpansionProgram {
  path: string;
  programId: string;
  expansions: CopyExpansion[];
}

/** The COPY expansion table scan writes. Programs are sorted by relative path. */
export interface CopyExpansionData {
  programs: CopyExpansionProgram[];
}

/** The original and fixed text of one file, ready for a diff view. */
export interface FixDiff {
  relPath: string;
  originalText: string;
  fixedText: string;
}

/** Which original and fixed files to pair up. */
export interface FixDiffRequest {
  originalPath: string;
  fixedPath: string;
  relPath: string;
}

/** The transpile target language, derived from the generated file's extension. */
export type TranspileLanguage = "python" | "java";

/** One file translate wrote, flat under its output directory. */
export interface TranspileGeneratedFile {
  name: string;
  language: TranspileLanguage;
  text: string;
}

/** One LINE_MAP row. Ranges are 1-based and inclusive. */
export interface LineMapEntry {
  id: number;
  cobolLineStart: number;
  cobolLineEnd: number;
  genFile: string;
  genLineStart: number;
  genLineEnd: number;
  /** "1:1", "1:N" or "N:1". */
  kind: string;
  /** A note about what could not be translated literally; empty means none. */
  note: string;
  anchorId: string;
}

/** Which translate artefacts to read. */
export interface TranspileRequest {
  /** The translate --out directory; the runner writes generated files flat under it. */
  outDir: string;
  /** The SQLite project file holding LINE_MAP. */
  dbPath: string;
  /** The COBOL source whose translation to read (SOURCE.path form, e.g. cobol/SYK001.cbl). */
  cobolRelPath: string;
}

/** Generated files plus the line correspondence table. */
export interface TranspileArtifacts {
  files: TranspileGeneratedFile[];
  lineMap: LineMapEntry[];
}

/** Which report artefact to read and in which form. */
export interface ReportArtifactRequest {
  path: string;
  kind: "html" | "text";
}

/* ------------------------------------------------------------------ filesystem */

/**
 * Where the engine writes its artefacts. Main derives these from userData (the portable `data/`
 * directory in a distribution) and the renderer passes them straight back as engine arguments.
 * Relying on the engine's defaults plus a matching working directory would let the write location
 * and the read location drift apart.
 */
export interface EngineOutputPaths {
  readonly db: string;
  readonly sarif: string;
  /** Kept apart from `sarif`: sharing one name would make sql-lint overwrite the lint results. */
  readonly sqlSarif: string;
  readonly copyExpansion: string;
  /** The rule configuration file. Not an engine artefact — the GUI writes it, the engine reads it. */
  readonly rules: string;
}

/** Where an asset kind's imported text should land. */
export type ImportAssetKind = "cobol" | "copybook" | "jcl" | "bms";

/** Text copied out of a terminal emulator, to be written into the asset folder as a source file. */
export interface ImportSourceRequest {
  inputDir: string;
  kind: ImportAssetKind;
  /** Destination folder as a path relative to the asset folder; "" means the asset folder itself. */
  destDir: string;
  /** File name. A copybook that does not already end in .cpy gets the extension added. */
  fileName: string;
  lines: string[];
  overwrite: boolean;
}

/** The import outcome. "exists" means a file of that name is there and overwriting was not allowed. */
export interface ImportSourceResult {
  status: "written" | "exists";
  relPath: string;
  /** 0 when status is "exists". */
  lineCount: number;
}

/** Which file to stat, inside an allowed base directory. */
export interface StatRequest {
  baseDir: string;
  path: string;
}

/* ------------------------------------------------------------------ the exposed API */

/** The API contextBridge publishes to the renderer as window.cobolInsight. */
export interface CobolInsightApi {
  /** Runs one engine subcommand. */
  run(invocation: EngineInvocation): Promise<EngineResult>;
  /** Kills the running engine. Does nothing when none is running. */
  cancel(): Promise<void>;
  /** Decodes one source file inside an allowed base directory. */
  decode(request: DecodeSourceRequest): Promise<DecodeResult>;
  /** Writes edited text back over an original, keeping its codepage. */
  save(request: SaveSourceRequest): Promise<SaveResult>;
  /** Lists the built-in and user-defined rules the engine knows about. */
  rules(request: RulesRequest): Promise<RuleCatalog>;
  /** Checks candidate rule-file text without committing it. */
  validateRules(raw: string): Promise<RulesValidation>;

  readInventory(dbPath: string): Promise<AssetInventoryItem[]>;
  readSarif(path: string): Promise<SarifFinding[]>;
  readGraph(dbPath: string): Promise<GraphData>;
  readCopyExpansion(path: string): Promise<CopyExpansionData>;
  readFixDiff(request: FixDiffRequest): Promise<FixDiff>;
  readTranspile(request: TranspileRequest): Promise<TranspileArtifacts>;
  readReport(request: ReportArtifactRequest): Promise<string>;

  outputPaths(): Promise<EngineOutputPaths>;
  selectFolder(): Promise<string | null>;
  dirExists(path: string): Promise<boolean>;
  stat(request: StatRequest): Promise<SourceStamp | null>;
  importSource(request: ImportSourceRequest): Promise<ImportSourceResult>;

  readSettings(): Promise<AppSettings>;
  writeSettings(settings: AppSettings): Promise<void>;
  readRules(path: string): Promise<RulesFile>;
  writeRules(path: string, file: RulesFile): Promise<void>;

  versions: { chrome: string; node: string; electron: string };
}

/** IPC channel names. The preload invokes them and main handles them; nothing else names a channel. */
export const CHANNELS = {
  engineRun: "engine:run",
  engineCancel: "engine:cancel",
  engineDecode: "engine:decode",
  engineSave: "engine:save",
  engineRules: "engine:rules",
  engineValidateRules: "engine:validate-rules",

  artifactInventory: "artifact:inventory",
  artifactSarif: "artifact:sarif",
  artifactGraph: "artifact:graph",
  artifactCopyExpansion: "artifact:copy-expansion",
  artifactFixDiff: "artifact:fix-diff",
  artifactTranspile: "artifact:transpile",
  artifactReport: "artifact:report",

  fsOutputPaths: "fs:output-paths",
  fsSelectFolder: "fs:select-folder",
  fsDirExists: "fs:dir-exists",
  fsStat: "fs:stat",
  fsImportSource: "fs:import-source",

  settingsRead: "settings:read",
  settingsWrite: "settings:write",
  rulesRead: "rules:read",
  rulesWrite: "rules:write",
} as const;

/**
 * The file name scan writes the COPY expansion table under. Main names it in the engine arguments
 * and the renderer reads it from the same place, so both must agree on this one constant.
 */
export const COPY_EXPANSION_FILE_NAME = "cobol-insight-copy-expansion.json";
