import type { ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import type { CobolInsightApi } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import {
  ProjectProvider,
  initialProjectState,
  useProjectDispatch,
  type ProjectState,
} from "../../state/projectStore";
import { SettingsProvider } from "../../state/settingsStore";
import { ReportEditor } from "./ReportEditor";

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

const ANALYSED: ProjectState = {
  ...initialProjectState,
  mode: "results",
  inputDir: "C:/assets",
  dbPath: OUTPUT_PATHS.db,
  outputPaths: OUTPUT_PATHS,
  runId: 1,
  lastWholeFolderRunId: 1,
};

/** Hands the project dispatch to the test, which plays the shell's part in a save and its scan. */
let dispatchProject: ReturnType<typeof useProjectDispatch> = () => undefined;
function Probe(): null {
  dispatchProject = useProjectDispatch();
  return null;
}

/** A report run that finishes, and a report file that reads back as `content`. */
function install(content: string): void {
  const api = {
    run: vi.fn(async () => ({
      subcommand: "report",
      exitCode: 0,
      summary: {},
      stdout: "",
      stderr: "",
      outputs: {},
    })),
    readReport: vi.fn(async () => content),
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
}

function renderReport(project: ProjectState): ReactElement | void {
  render(
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <Probe />
        <ReportEditor notify={vi.fn()} />
      </ProjectProvider>
    </SettingsProvider>,
  );
}

describe("ReportEditor", () => {
  describe("the report tab", () => {
    it("fills the HTML button once an analysis of the whole folder exists", () => {
      renderReport(ANALYSED);
      expect(screen.getByRole("button", { name: text.report.generateHtml })).toHaveClass("ci-button--primary");
    });

    it("leaves the HTML button outlined while only a scoped run has finished", () => {
      renderReport({ ...ANALYSED, lastWholeFolderRunId: 0, lastScopedRunId: 1 });
      expect(screen.getByRole("button", { name: text.report.generateHtml })).not.toHaveClass("ci-button--primary");
    });

    it("disables both generate buttons until a run of the whole folder exists", () => {
      renderReport({ ...ANALYSED, lastWholeFolderRunId: 0, lastScopedRunId: 1 });
      expect(screen.getByRole("button", { name: text.report.generateHtml })).toBeDisabled();
      expect(screen.getByRole("button", { name: text.report.generateText })).toBeDisabled();
    });

    it("enables both generate buttons once a run of the whole folder exists", () => {
      renderReport(ANALYSED);
      expect(screen.getByRole("button", { name: text.report.generateHtml })).toBeEnabled();
      expect(screen.getByRole("button", { name: text.report.generateText })).toBeEnabled();
    });

    it("names the difference when a scoped run is newer than the run of the whole folder", () => {
      renderReport({ ...ANALYSED, lastWholeFolderRunId: 1, lastScopedRunId: 2 });
      expect(screen.getByTestId("report-note")).toHaveTextContent(text.report.scopedSinceWholeRun);
    });

    it("shows no note while the report covers the last run", () => {
      renderReport({ ...ANALYSED, lastWholeFolderRunId: 2, lastScopedRunId: 1 });
      expect(screen.queryByTestId("report-note")).toBeNull();
    });
  });

  describe("the report tab while the engine is busy", () => {
    it("says it waits for the analysis while the scan after a save is due", () => {
      renderReport({ ...ANALYSED, structureStale: ["cobol/A.cbl"], staleWrites: 1 });
      expect(screen.getByTestId("report-waiting")).toHaveTextContent(text.report.waiting);
      expect(screen.queryByTestId("report-generating")).toBeNull();
    });

    it("says it waits for the analysis while an analysis runs", () => {
      renderReport({ ...ANALYSED, mode: "running" });
      expect(screen.getByTestId("report-waiting")).toHaveTextContent(text.report.waiting);
    });
  });

  describe("a report generated before a later save or scan", () => {
    it("carries no note while nothing moved since it was generated", async () => {
      install("report body");
      renderReport(ANALYSED);
      fireEvent.click(screen.getByTestId("report-generate-text"));

      expect(await screen.findByTestId("report-text")).toHaveTextContent("report body");
      expect(screen.queryByTestId("report-outdated")).toBeNull();
    });

    it("stays on screen with a note once a save and its scan came after it", async () => {
      install("report body");
      renderReport(ANALYSED);
      fireEvent.click(screen.getByTestId("report-generate-text"));
      await screen.findByTestId("report-text");

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
      act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: true }));

      expect(screen.getByTestId("report-text")).toHaveTextContent("report body");
      expect(screen.getByTestId("report-outdated")).toHaveTextContent(text.report.outdated);
    });

    it("carries the note after a save whose scan failed, which leaves the epoch where it was", async () => {
      install("report body");
      renderReport(ANALYSED);
      fireEvent.click(screen.getByTestId("report-generate-text"));
      await screen.findByTestId("report-text");

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
      act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));

      expect(screen.getByTestId("report-outdated")).toHaveTextContent(text.report.outdated);
    });
  });
});
