/**
 * The report tab's state: which form of the report was asked for, whether it is being generated, and
 * what came back.
 *
 * The report itself is never assembled here. One `report` run writes both an HTML and a text file,
 * and the tab shows whichever of the two the button asked for.
 */

import type { AnalysisMode } from "../state/projectStore";

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

export type ReportState =
  | { readonly status: "idle" }
  | { readonly status: "generating"; readonly format: ReportFormat }
  | {
      readonly status: "ready";
      readonly format: ReportFormat;
      readonly content: string;
      readonly path: string;
    }
  | { readonly status: "failed"; readonly format: ReportFormat; readonly message: string };

export const INITIAL_REPORT_STATE: ReportState = { status: "idle" };

export type ReportAction =
  | { type: "GENERATE"; format: ReportFormat }
  | { type: "READY"; format: ReportFormat; content: string; path: string }
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
        ? { status: "ready", format: action.format, content: action.content, path: action.path }
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
  | { readonly kind: "failed"; readonly message: string }
  | { readonly kind: "not-generated" }
  | { readonly kind: "ready" };

export function deriveReportView(
  mode: AnalysisMode,
  inputDir: string | null,
  dbPath: string | null,
  report: ReportState,
): ReportView {
  if (report.status === "generating" || mode === "running") {
    return { kind: "generating" };
  }
  if (inputDir === null || dbPath === null) {
    return { kind: "no-project" };
  }
  if (report.status === "failed") {
    return { kind: "failed", message: report.message };
  }
  return report.status === "ready" ? { kind: "ready" } : { kind: "not-generated" };
}
