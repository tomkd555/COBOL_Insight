import { describe, expect, it } from "vitest";
import {
  INITIAL_REPORT_STATE,
  REPORT_SANDBOX,
  deriveReportView,
  reportArtifactPaths,
  reportFileName,
  reportPathOf,
  reportReducer,
  type ReportState,
} from "./report";

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
  };

  it("asks for an analysis before anything else", () => {
    expect(deriveReportView("empty", null, null, INITIAL_REPORT_STATE)).toEqual({
      kind: "no-project",
    });
    expect(deriveReportView("results", "C:/assets", null, INITIAL_REPORT_STATE)).toEqual({
      kind: "no-project",
    });
  });

  it("waits while the analysis or the report is running", () => {
    expect(deriveReportView("running", "C:/assets", "p.db", INITIAL_REPORT_STATE)).toEqual({
      kind: "generating",
    });
    expect(
      deriveReportView("results", "C:/assets", "p.db", { status: "generating", format: "html" }),
    ).toEqual({ kind: "generating" });
  });

  it("distinguishes not generated, failed and ready", () => {
    expect(deriveReportView("results", "C:/assets", "p.db", INITIAL_REPORT_STATE)).toEqual({
      kind: "not-generated",
    });
    expect(
      deriveReportView("results", "C:/assets", "p.db", {
        status: "failed",
        format: "html",
        message: "boom",
      }),
    ).toEqual({ kind: "failed", message: "boom" });
    expect(deriveReportView("results", "C:/assets", "p.db", ready)).toEqual({ kind: "ready" });
  });
});

describe("REPORT_SANDBOX", () => {
  it("withholds every permission, allow-same-origin included", () => {
    expect(REPORT_SANDBOX).toBe("");
  });
});
