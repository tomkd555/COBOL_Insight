import type { ReactElement, ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import type { CobolInsightApi, EngineInvocation, EngineResult } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { ProjectProvider, initialProjectState, useProjectDispatch } from "../../state/projectStore";
import { SettingsProvider } from "../../state/settingsStore";
import { TranspilePane } from "./TranspilePane";

// The side-by-side editor needs a layout; what is under test is the run that builds the translation.
vi.mock("./SideBySide", () => ({ SideBySide: () => <div data-testid="side-by-side" /> }));

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

/** The translation of one run is shared by every pane, and keyed by the run, so each case has its own. */
let runId = 200;

function finished(invocation: EngineInvocation): EngineResult {
  return {
    subcommand: invocation.subcommand,
    exitCode: 0,
    summary: {},
    stdout: "",
    stderr: "",
    outputs: {},
  };
}

function install(run: ReturnType<typeof vi.fn>): { decode: ReturnType<typeof vi.fn> } {
  const api = {
    run,
    decode: vi.fn(async () => ({
      text: "cobol",
      codepage: "UTF-8",
      detected: true,
      lines: [],
      stamp: { mtimeMs: 1, byteSize: 1 },
      error: "",
    })),
    readTranspile: vi.fn(async () => ({
      files: [{ name: "A.py", language: "python", text: "print()" }],
      lineMap: [],
    })),
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
  return api;
}

/** Hands the project dispatch to the test, which plays the shell's part in a save and its scan. */
let dispatchProject: ReturnType<typeof useProjectDispatch> = () => undefined;
function Probe(): null {
  dispatchProject = useProjectDispatch();
  return null;
}

function providers(children: ReactNode, codepageOverrides: Record<string, string> = {}): ReactElement {
  const project = {
    ...initialProjectState,
    inputDir: "C:/assets",
    outputPaths: OUTPUT_PATHS,
    runId,
    codepageOverrides,
  };
  return (
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <Probe />
        {children}
      </ProjectProvider>
    </SettingsProvider>
  );
}

beforeEach(() => {
  runId += 1;
});

describe("TranspilePane", () => {
  describe("the translation of an asset", () => {
    it("asks the engine once for two panes of the same file", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);

      render(
        providers(
          <>
            <TranspilePane path="cobol/A.cbl" />
            <TranspilePane path="cobol/A.cbl" />
          </>,
        ),
      );

      await waitFor(() => expect(screen.getAllByTestId("side-by-side")).toHaveLength(2));
      expect(run).toHaveBeenCalledTimes(1);
      expect(run.mock.calls[0][0]).toMatchObject({ subcommand: "translate" });
    });

    it("asks the engine once for panes of two files after the same run", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);

      render(
        providers(
          <>
            <TranspilePane path="cobol/A.cbl" />
            <TranspilePane path="cobol/B.cbl" />
          </>,
        ),
      );

      await waitFor(() => expect(screen.getAllByTestId("side-by-side")).toHaveLength(2));
      expect(run).toHaveBeenCalledTimes(1);
    });
  });

  describe("the codepages the translation reads the files in", () => {
    it("are the overrides the scan and the lint read them in, and a change asks the engine again", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<TranspilePane path="cobol/A.cbl" />, { "cobol/A.cbl": "IBM930" }));
      await waitFor(() => expect(screen.getByTestId("side-by-side")).toBeInTheDocument());
      expect(run.mock.calls[0][0]).toMatchObject({
        subcommand: "translate",
        request: { codepageOverrides: { "cobol/A.cbl": "IBM930" } },
      });

      act(() => dispatchProject({ type: "SET_CODEPAGE", path: "cobol/A.cbl", charset: "IBM939" }));

      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
      expect(run.mock.calls[1][0]).toMatchObject({
        request: { codepageOverrides: { "cobol/A.cbl": "IBM939" } },
      });
    });
  });

  describe("the COBOL beside the translation", () => {
    it("is decoded in the codepage the override names", async () => {
      const api = install(vi.fn(async (invocation: EngineInvocation) => finished(invocation)));
      render(providers(<TranspilePane path="cobol/A.cbl" />, { "cobol/A.cbl": "IBM930" }));

      await waitFor(() => expect(screen.getByTestId("side-by-side")).toBeInTheDocument());
      expect(api.decode).toHaveBeenCalledWith(
        expect.objectContaining({ path: "cobol/A.cbl", codepage: "IBM930" }),
      );
    });
  });

  describe("the translation after a save", () => {
    it("asks the engine again when the scan after the write ended without success", async () => {
      // The scan failed, so the epoch (runId) stays and only the count of writes rose.
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<TranspilePane path="cobol/A.cbl" />));
      await waitFor(() => expect(screen.getByTestId("side-by-side")).toBeInTheDocument());
      expect(run).toHaveBeenCalledTimes(1);

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
      act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));

      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
      expect(run.mock.calls[1][0]).toMatchObject({ subcommand: "translate" });
    });

    it("holds the earlier translation with a note while the scan after the write is still due", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<TranspilePane path="cobol/A.cbl" />));
      await waitFor(() => expect(screen.getByTestId("side-by-side")).toBeInTheDocument());
      expect(screen.queryByTestId("transpile-before-save")).toBeNull();

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));

      expect(run).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId("side-by-side")).toBeInTheDocument();
      expect(screen.getByTestId("transpile-before-save")).toHaveTextContent(text.transpileView.beforeSave);
    });

    it("asks the engine again on the next mount after a run that printed no summary", async () => {
      // A failed run leaves the out directory holding the run before it, so its entry is dropped.
      const run = vi.fn(async (invocation: EngineInvocation) => ({
        ...finished(invocation),
        exitCode: 1,
        summary: null,
        stderr: "engine stopped",
      }));
      install(run);

      const first = render(providers(<TranspilePane path="cobol/A.cbl" />));
      expect(await screen.findByRole("alert")).toHaveTextContent("engine stopped");
      first.unmount();

      render(providers(<TranspilePane path="cobol/A.cbl" />));
      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    });
  });
});
