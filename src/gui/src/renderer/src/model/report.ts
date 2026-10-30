/**
 * The report tab's state: which form of the report was asked for, whether it is being generated, and
 * what came back.
 *
 * The report itself is never assembled here. One `report` run writes both an HTML and a text file,
 * and the tab shows whichever of the two the button asked for.
 */

export type ReportFormat = "html" | "text";

/** The engine's default report file names (ReportCommand's defaultValue). */
const HTML_FILE_NAME = "cobol-insight-report.html";
const TEXT_FILE_NAME = "cobol-insight-report.txt";

/** The engine's default project file name, used only when no analysis has named one. */
const DEFAULT_DB_FILE = "cobol-insight.db";

/**
 * The sandbox of the iframe the HTML report is shown in. The empty value withholds every permission:
 * no script, no form submission, no navigation of the top frame. `allow-same-origin` is deliberately
 * absent, so the report is placed in an opaque origin and cannot reach the renderer's DOM.
 */
export const REPORT_SANDBOX = "";

/** The colours the dark preview sheet is written with, as CSS colour values of the document's theme. */
export interface ReportPalette {
  readonly background: string;
  readonly foreground: string;
  readonly surface: string;
  readonly mutedForeground: string;
  readonly border: string;
  readonly destructive: string;
  readonly accent: string;
}

/**
 * The dark theme's rules for the report's own markup (the engine's ReportRenderer.css), drawn in the
 * given palette. The report is a document in its own right and is written light; this sheet goes
 * into the preview alone.
 */
function darkPreviewStyle(palette: ReportPalette): string {
  return `<style>
:root { color-scheme: dark; }
body { background: ${palette.background}; color: ${palette.foreground}; }
h1 { border-bottom-color: ${palette.mutedForeground}; }
th, td { border-color: ${palette.border}; }
th { background: ${palette.surface}; }
td.lv-error { color: ${palette.destructive}; }
td.lv-warning, p.scope { color: ${palette.accent}; }
td.lv-note, p.none { color: ${palette.mutedForeground}; }
</style>`;
}

/**
 * The HTML the preview frame shows: the report as generated, with the dark sheet added last in its
 * head under the dark theme, or after everything in a document that closes no head, so the document
 * type stays the first thing in it. The file on disk and the copy written out stay as the engine made them.
 * The palette is read by the caller from the document's theme variables; the light theme ignores it.
 */
export function reportPreviewHtml(content: string, theme: "light" | "dark", palette: ReportPalette): string {
  if (theme === "light") {
    return content;
  }
  const sheet = darkPreviewStyle(palette);
  const head = content.search(/<\/head>/i);
  return head < 0 ? content + sheet : content.slice(0, head) + sheet + content.slice(head);
}

export interface ReportPaths {
  readonly db: string;
  readonly html: string;
  readonly text: string;
}

/** The report files, written beside the project file — the one directory scan is known to reach. */
export function reportArtifactPaths(dbPath: string | null): ReportPaths {
  const db = dbPath ?? DEFAULT_DB_FILE;
  const separator = Math.max(db.lastIndexOf("\\"), db.lastIndexOf("/"));
  const dir = separator < 0 ? "" : db.slice(0, separator + 1);
  return { db, html: `${dir}${HTML_FILE_NAME}`, text: `${dir}${TEXT_FILE_NAME}` };
}

/** The file the chosen form is written to. */
export function reportPathOf(format: ReportFormat, paths: ReportPaths): string {
  return format === "html" ? paths.html : paths.text;
}

/** The name the save dialog offers by default. */
export function reportFileName(format: ReportFormat): string {
  return format === "html" ? HTML_FILE_NAME : TEXT_FILE_NAME;
}

/**
 * What a report was generated from: the artefact epoch (ProjectState.runId) and the count of asset
 * writes (ProjectState.staleWrites) at the moment it was asked for.
 */
export interface ReportBasis {
  readonly runId: number;
  readonly writes: number;
}

export type ReportState =
  | { readonly status: "idle" }
  | { readonly status: "generating"; readonly format: ReportFormat }
  | {
      readonly status: "ready";
      readonly format: ReportFormat;
      readonly content: string;
      readonly path: string;
      readonly basis: ReportBasis;
    }
  | { readonly status: "failed"; readonly format: ReportFormat; readonly message: string };

export const INITIAL_REPORT_STATE: ReportState = { status: "idle" };

export type ReportAction =
  | { type: "GENERATE"; format: ReportFormat }
  | { type: "READY"; format: ReportFormat; content: string; path: string; basis: ReportBasis }
  | { type: "FAILED"; format: ReportFormat; message: string };

/**
 * The state machine. A result for a form other than the one now being generated is dropped: the
 * second button can be pressed while the first run is still going, and the slower run must not
 * replace what the newer one asked for.
 */
export function reportReducer(state: ReportState, action: ReportAction): ReportState {
  switch (action.type) {
    case "GENERATE":
      return { status: "generating", format: action.format };
    case "READY":
      return state.status === "generating" && state.format === action.format
        ? {
            status: "ready",
            format: action.format,
            content: action.content,
            path: action.path,
            basis: action.basis,
          }
        : state;
    case "FAILED":
      return state.status === "generating" && state.format === action.format
        ? { status: "failed", format: action.format, message: action.message }
        : state;
    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

/** What the tab draws: the four states, plus the two the analysis lifecycle imposes. */
export type ReportView =
  | { readonly kind: "no-project" }
  | { readonly kind: "generating" }
  | { readonly kind: "waiting" }
  | { readonly kind: "failed"; readonly message: string }
  | { readonly kind: "not-generated" }
  | { readonly kind: "ready" };

/**
 * @param busy whether an analysis runs or the scan after a save is due or running
 *   (projectStore engineBusy); the tab then waits for it.
 */
export function deriveReportView(
  busy: boolean,
  inputDir: string | null,
  dbPath: string | null,
  report: ReportState,
): ReportView {
  if (report.status === "generating") {
    return { kind: "generating" };
  }
  if (busy) {
    return { kind: "waiting" };
  }
  if (inputDir === null || dbPath === null) {
    return { kind: "no-project" };
  }
  if (report.status === "failed") {
    return { kind: "failed", message: report.message };
  }
  return report.status === "ready" ? { kind: "ready" } : { kind: "not-generated" };
}

/**
 * Whether the report on hand was generated before a later save, scan or run: the epoch or the count
 * of writes has moved since it was asked for.
 */
export function reportOutdated(report: ReportState, now: ReportBasis): boolean {
  return (
    report.status === "ready" &&
    (report.basis.runId !== now.runId || report.basis.writes !== now.writes)
  );
}
