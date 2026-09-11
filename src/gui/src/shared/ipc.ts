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

/** The engine subcommands the GUI can launch. */
export type EngineSubcommand =
  | "scan"
  | "lint"
  | "report"
  | "translate"
  | "fix"
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

export interface LintRequest extends EngineCommonOptions {
  /** SARIF 2.1.0 output file for the lint findings (--sarif). */
  sarifFile?: string;
  /** SARIF 2.1.0 output file for the SQL findings (--sql-sarif). */
  sqlSarifFile?: string;
  /**
   * What to analyse inside the asset folder (repeated --scope), each a path relative to inputDir
   * naming a file or a directory. The folder stays the root, so the relative paths in the SARIF are
   * the same as in a whole-folder run. Absent means the whole folder. lint alone accepts this.
   */
  scope?: string[];
}

/** report reads existing artefacts; it takes no asset folder and no parsing options. */
export interface ReportRequest {
  db?: string;
  sarifFile?: string;
  sqlSarifFile?: string;
  htmlFile?: string;
  textFile?: string;
}

export interface TranslateRequest extends EngineCommonOptions {
  db?: string;
  language?: "python" | "java" | "both";
  outDir?: string;
}

export interface FixRequest extends EngineCommonOptions {
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
  | { subcommand: "lint"; request: LintRequest }
  | { subcommand: "report"; request: ReportRequest }
  | { subcommand: "translate"; request: TranslateRequest }
  | { subcommand: "fix"; request: FixRequest }
  | { subcommand: "rules"; request: RulesRequest }
  | { subcommand: "save"; request: SaveRequest }
  | { subcommand: "decode"; request: DecodeRequest };

/** Resolved paths of the artefacts an invocation wrote. The renderer reads artefacts from here. */
export interface EngineOutputs {
  db?: string;
  sarif?: string;
  sqlSarif?: string;
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
  /**
   * Length of the line in the original encoding, its line terminator included — one byte for LF, two
   * for CRLF. EBCDIC shift-out and shift-in bytes count too, since they occupy byte columns. The last
   * line of the file runs to the end of the file.
   */
  byteLength: number;
  /**
   * Character offsets of the fixed-format column boundaries, as [indicator, area A, area B,
   * identification] — that is, the zero-based character index within the line at which byte columns
   * 7, 8, 12 and 73 begin, or -1 when the line does not reach the column.
   *
   * A column landing inside a double-byte character does not split it: the boundary is the first
   * character starting at or after that byte. The line terminator is one of the characters searched,
   * so an exactly 72-byte line reports the position just past its text for column 73 rather than -1;
   * only a final line that ends without a terminator reports -1 there. The columns are counted in the
   * original encoding's bytes and the answer is a UTF-16 offset into the decoded text, so the two
   * coincide only on a line of single-byte characters.
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
  /** Whether the rule can produce a fix diff. */
  hasFix: boolean;
  /** Built-in or user-defined. */
  source: "builtin" | "user";
  /** Whether the rule is in effect, given the rule configuration file. */
  enabled: boolean;
  /** Which subcommands run this rule (lint, sql-lint, ...). */
  commands: string[];
  /** Which asset kinds the rule inspects (COBOL/COPYBOOK/JCL/BMS). */
  targets: string[];
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
  /** NODE.type (PROGRAM/JCL/COPYBOOK/BMS/SQL). UNKNOWN when no node was registered. */
  type: string;
  /** SOURCE.codepage; null when decoding failed. */
  codepage: string | null;
  findingCount: number;
}

/** One SARIF 2.1.0 result flattened into what the screens need. */
export interface SarifFinding {
  ruleId: string;
  /** SARIF level (error/warning/note/none). */
  level: string;
  message: string;
  /** Path relative to the asset folder. */
  file: string;
  startLine: number;
  startColumn: number;
  /** The places the finding is about besides its own line (SARIF codeFlows, flattened). */
  related?: RelatedLocation[];
}

/** One step of a finding's code flow: a position with the engine's label for it. */
export interface RelatedLocation {
  file: string;
  line: number;
  label: string;
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
  /**
   * How the origin uses the target: READ/WRITE/UPDATE/CREATE/DELETE/UNKNOWN for a step and a data
   * set, the letters R, C, U and D for a program and a Db2 table. Null when the engine recorded
   * none.
   */
  access: string | null;
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
  cobolLineStart: number;
  cobolLineEnd: number;
  genFile: string;
  genLineStart: number;
  genLineEnd: number;
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
  /** Kept apart from `sarif`: lint writes both files in one run and must not have one overwrite the other. */
  readonly sqlSarif: string;
  /**
   * Where a scoped lint writes instead of `sarif` and `sqlSarif`. A scoped run reports only the
   * assets its scope covers, so letting it write the whole-folder pair would leave the report —
   * which is generated from those two files — covering one asset while naming the whole folder.
   */
  readonly scopedSarif: string;
  readonly scopedSqlSarif: string;
  readonly copyExpansion: string;
  /** The rule configuration file. Not an engine artefact — the GUI writes it, the engine reads it. */
  readonly rules: string;
}

/** Text copied out of a terminal emulator, to be written into the asset folder as a source file. */
export interface ImportSourceRequest {
  inputDir: string;
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

/** A copy of a generated artefact, written wherever the save dialog points. */
export interface SaveAsRequest {
  /** The file name the dialog offers. */
  fileName: string;
  /** The text to write. It is written as UTF-8. */
  text: string;
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

  /** The assets of one asset folder. A project file can hold several, so the root selects one. */
  readInventory(dbPath: string, root: string): Promise<AssetInventoryItem[]>;
  readSarif(path: string): Promise<SarifFinding[]>;
  /** The call graph of one asset folder, selected by the same root as the inventory. */
  readGraph(dbPath: string, root: string): Promise<GraphData>;
  readCopyExpansion(path: string): Promise<CopyExpansionData>;
  readFixDiff(request: FixDiffRequest): Promise<FixDiff>;
  readTranspile(request: TranspileRequest): Promise<TranspileArtifacts>;
  readReport(request: ReportArtifactRequest): Promise<string>;

  outputPaths(): Promise<EngineOutputPaths>;
  selectFolder(): Promise<string | null>;
  dirExists(path: string): Promise<boolean>;
  stat(request: StatRequest): Promise<SourceStamp | null>;
  importSource(request: ImportSourceRequest): Promise<ImportSourceResult>;
  /** Writes a copy of generated text where the save dialog points. Null when it was cancelled. */
  saveAs(request: SaveAsRequest): Promise<string | null>;

  readSettings(): Promise<AppSettings>;
  /** Merges shallowly into the stored settings, so a caller need only send the keys it owns. */
  writeSettings(settings: Partial<AppSettings>): Promise<void>;
  readRules(path: string): Promise<RulesFile>;
  writeRules(path: string, file: RulesFile): Promise<void>;
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
  fsSaveAs: "fs:save-as",

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
