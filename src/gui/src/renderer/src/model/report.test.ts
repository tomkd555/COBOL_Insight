import { describe, expect, it } from "vitest";
import {
  INITIAL_REPORT_STATE,
  REPORT_SANDBOX,
  deriveReportView,
  reportArtifactPaths,
  reportFileName,
  reportPathOf,
  reportPreviewHtml,
  reportOutdated,
  reportReducer,
  type ReportPalette,
  type ReportState,
} from "./report";

const BASIS = { runId: 3, writes: 1 };

describe("reportArtifactPaths", () => {
  it("writes beside the project file", () => {
    const paths = reportArtifactPaths("C:\\data\\cobol-insight.db");
    expect(paths.html).toBe("C:\\data\\cobol-insight-report.html");
    expect(paths.text).toBe("C:\\data\\cobol-insight-report.txt");
  });

  it("falls back to the engine's own defaults when no project file is known", () => {
    const paths = reportArtifactPaths(null);
    expect(paths.db).toBe("cobol-insight.db");
    expect(paths.html).toBe("cobol-insight-report.html");
  });

  it("names the file of the chosen form", () => {
    const paths = reportArtifactPaths("/tmp/p.db");
    expect(reportPathOf("text", paths)).toBe("/tmp/cobol-insight-report.txt");
    expect(reportFileName("html")).toBe("cobol-insight-report.html");
  });
});

describe("reportReducer", () => {
  it("goes from generating to ready", () => {
    const generating = reportReducer(INITIAL_REPORT_STATE, { type: "GENERATE", format: "html" });
    expect(generating).toEqual({ status: "generating", format: "html" });
    const ready = reportReducer(generating, {
      type: "READY",
      format: "html",
      content: "<h1>x</h1>",
      path: "r.html",
      basis: BASIS,
    });
    expect(ready).toMatchObject({ status: "ready", format: "html", content: "<h1>x</h1>" });
  });

  it("keeps a failure with its message", () => {
    const generating = reportReducer(INITIAL_REPORT_STATE, { type: "GENERATE", format: "text" });
    const failed = reportReducer(generating, { type: "FAILED", format: "text", message: "no db" });
    expect(failed).toEqual({ status: "failed", format: "text", message: "no db" });
  });

  it("drops the result of a run the newer request replaced", () => {
    const first = reportReducer(INITIAL_REPORT_STATE, { type: "GENERATE", format: "html" });
    const second = reportReducer(first, { type: "GENERATE", format: "text" });
    const late = reportReducer(second, {
      type: "READY",
      format: "html",
      content: "stale",
      path: "r.html",
      basis: BASIS,
    });
    expect(late).toBe(second);
  });
});

describe("deriveReportView", () => {
  const ready: ReportState = {
    status: "ready",
    format: "html",
    content: "<h1>x</h1>",
    path: "r.html",
    basis: BASIS,
  };

  it("asks for an analysis before anything else", () => {
    expect(deriveReportView(false, null, null, INITIAL_REPORT_STATE)).toEqual({
      kind: "no-project",
    });
    expect(deriveReportView(false, "C:/assets", null, INITIAL_REPORT_STATE)).toEqual({
      kind: "no-project",
    });
  });

  it("waits for the analysis while it or the scan after a save runs", () => {
    expect(deriveReportView(true, "C:/assets", "p.db", INITIAL_REPORT_STATE)).toEqual({
      kind: "waiting",
    });
    expect(deriveReportView(true, "C:/assets", "p.db", ready)).toEqual({ kind: "waiting" });
  });

  it("returns generating for its own run whether or not the engine is busy", () => {
    expect(
      deriveReportView(true, "C:/assets", "p.db", { status: "generating", format: "html" }),
    ).toEqual({ kind: "generating" });
    expect(
      deriveReportView(false, "C:/assets", "p.db", { status: "generating", format: "html" }),
    ).toEqual({ kind: "generating" });
  });

  it("returns not-generated for the initial state", () => {
    expect(deriveReportView(false, "C:/assets", "p.db", INITIAL_REPORT_STATE)).toEqual({
      kind: "not-generated",
    });
  });

  it("returns failed with the message for a failed run", () => {
    expect(
      deriveReportView(false, "C:/assets", "p.db", {
        status: "failed",
        format: "html",
        message: "boom",
      }),
    ).toEqual({ kind: "failed", message: "boom" });
  });

  it("returns ready for a generated report", () => {
    expect(deriveReportView(false, "C:/assets", "p.db", ready)).toEqual({ kind: "ready" });
  });
});

describe("reportOutdated", () => {
  const ready: ReportState = {
    status: "ready",
    format: "text",
    content: "x",
    path: "r.txt",
    basis: BASIS,
  };

  it("holds the report current while neither the epoch nor the writes moved", () => {
    expect(reportOutdated(ready, BASIS)).toBe(false);
  });

  it("marks the report built from earlier content after a later run or scan", () => {
    expect(reportOutdated(ready, { ...BASIS, runId: BASIS.runId + 1 })).toBe(true);
  });

  it("marks the report built from earlier content after a later save", () => {
    expect(reportOutdated(ready, { ...BASIS, writes: BASIS.writes + 1 })).toBe(true);
  });

  it("marks nothing while no report is on hand", () => {
    expect(reportOutdated(INITIAL_REPORT_STATE, { runId: 9, writes: 9 })).toBe(false);
  });
});

describe("reportPreviewHtml", () => {
  const palette: ReportPalette = {
    background: "oklch(19% 0.01 250)",
    foreground: "oklch(92% 0.006 250)",
    surface: "oklch(23% 0.012 250)",
    mutedForeground: "oklch(68% 0.014 250)",
    border: "oklch(42% 0.014 250)",
    destructive: "oklch(74% 0.19 27)",
    accent: "oklch(72% 0.15 80)",
  };
  const html = "<html><head><style>body { color: #1a1a1a; }</style></head><body><h1>x</h1></body></html>";

  it("shows the report as generated under the light theme", () => {
    expect(reportPreviewHtml(html, "light", palette)).toBe(html);
  });

  it("adds the dark sheet after the report's own rules under the dark theme", () => {
    const dark = reportPreviewHtml(html, "dark", palette);
    expect(dark.indexOf("color-scheme: dark")).toBeGreaterThan(dark.indexOf("#1a1a1a"));
    expect(dark.indexOf("color-scheme: dark")).toBeLessThan(dark.indexOf("</head>"));
    expect(dark.endsWith("<body><h1>x</h1></body></html>")).toBe(true);
  });

  it("puts the sheet before an upper-case closing head tag", () => {
    const upper = "<HTML><HEAD><STYLE>body { color: #1a1a1a; }</STYLE></HEAD><BODY><H1>x</H1></BODY></HTML>";

    const dark = reportPreviewHtml(upper, "dark", palette);

    expect(dark.indexOf("color-scheme: dark")).toBeGreaterThan(dark.indexOf("#1a1a1a"));
    expect(dark.indexOf("color-scheme: dark")).toBeLessThan(dark.indexOf("</HEAD>"));
    expect(dark.endsWith("<BODY><H1>x</H1></BODY></HTML>")).toBe(true);
  });

  it("writes the sheet in the colours the caller passes", () => {
    const dark = reportPreviewHtml(html, "dark", palette);
    expect(dark).toContain(`body { background: ${palette.background}; color: ${palette.foreground}; }`);
    expect(dark).toContain(`td.lv-error { color: ${palette.destructive}; }`);
  });

  it("appends the sheet at the end when the text has no head", () => {
    const shown = reportPreviewHtml("<!DOCTYPE html><h1>x</h1>", "dark", palette);

    expect(shown.startsWith("<!DOCTYPE html><h1>x</h1><style>")).toBe(true);
    expect(shown.endsWith("</style>")).toBe(true);
  });
});

describe("REPORT_SANDBOX", () => {
  it("withholds every permission, allow-same-origin included", () => {
    expect(REPORT_SANDBOX).toBe("");
  });
});
