import type { ReactElement, ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { CobolInsightApi, EngineInvocation, EngineResult } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { ProjectProvider, initialProjectState, useProjectDispatch } from "../../state/projectStore";
import { SettingsProvider, initialSettingsState, type SettingsState } from "../../state/settingsStore";
import { FixDiff } from "./FixDiff";

// The diff editor needs a layout; what is under test is the run that builds the proposal.
vi.mock("./DiffView", () => ({ DiffView: () => <div data-testid="diff-view" /> }));

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

/** The preview of one run is shared by every view, and keyed by the run, so each case has its own. */
let runId = 100;

function finished(invocation: EngineInvocation, written = 1): EngineResult {
  return {
    subcommand: invocation.subcommand,
    exitCode: 0,
    summary: { writtenFiles: Array.from({ length: written }, (_, index) => `f${index}`) },
    stdout: "",
    stderr: "",
    outputs: {},
  };
}

function install(run: ReturnType<typeof vi.fn>, fixedText = "new"): void {
  const api = {
    run,
    readFixDiff: vi.fn(async () => ({ relPath: "cobol/A.cbl", originalText: "old", fixedText })),
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
}

/** Hands the project dispatch to the test, which plays the shell's part in a save and its scan. */
let dispatchProject: ReturnType<typeof useProjectDispatch> = () => undefined;
function Probe(): null {
  dispatchProject = useProjectDispatch();
  return null;
}

function providers(
  children: ReactNode,
  settings: SettingsState = initialSettingsState,
  codepageOverrides: Record<string, string> = {},
): ReactElement {
  const project = {
    ...initialProjectState,
    inputDir: "C:/assets",
    outputPaths: OUTPUT_PATHS,
    runId,
    codepageOverrides,
  };
  return (
    <SettingsProvider initial={settings}>
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

describe("FixDiff", () => {
  describe("the proposal for an asset", () => {
    it("launches one fix run for two mounts after the same run", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);

      render(
        providers(
          <>
            <FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />
            <FixDiff path="cobol/B.cbl" onNotify={vi.fn()} />
          </>,
        ),
      );

      await waitFor(() => expect(screen.getAllByTestId("diff-view")).toHaveLength(2));
      expect(run).toHaveBeenCalledTimes(1);
      expect(run.mock.calls[0][0]).toMatchObject({ subcommand: "fix" });
    });

    it("shows the empty state when the fixed text equals the original", async () => {
      install(vi.fn(async (invocation: EngineInvocation) => finished(invocation)), "old");

      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));

      expect(await screen.findByText(text.fixView.empty)).toBeInTheDocument();
      expect(screen.queryByTestId("diff-view")).toBeNull();
      expect(screen.queryByText(text.fixView.original)).toBeNull();
    });

    it("launches the run again on the next mount after a run that printed no summary", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => ({
        ...finished(invocation),
        exitCode: 1,
        summary: null,
        stderr: "engine stopped",
      }));
      install(run);

      const first = render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      expect(await screen.findByRole("alert")).toHaveTextContent(text.fixView.error);
      first.unmount();

      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
    });

    it("writes nothing out and says so when the output directory is inside the asset folder", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      const onNotify = vi.fn();

      render(
        providers(<FixDiff path="cobol/A.cbl" onNotify={onNotify} />, {
          ...initialSettingsState,
          fixOutDir: "C:/assets/out",
        }),
      );
      fireEvent.click(await screen.findByTestId("fix-apply"));

      expect(onNotify).toHaveBeenCalledWith(text.fixView.outDirInside, true);
      // The one run is the preview's; the write-out launched none.
      expect(run).toHaveBeenCalledTimes(1);
    });
  });

  describe("the proposal after a save", () => {
    it("asks the engine again when the scan after the write ended without success", async () => {
      // The scan failed, so the epoch (runId) stays and only the count of writes rose.
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      await waitFor(() => expect(screen.getByTestId("diff-view")).toBeInTheDocument());
      expect(run).toHaveBeenCalledTimes(1);

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
      act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));

      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
      expect(run.mock.calls[1][0]).toMatchObject({ subcommand: "fix" });
    });

    it("holds the earlier run while the scan after the write is still due", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      await waitFor(() => expect(screen.getByTestId("diff-view")).toBeInTheDocument());

      expect(screen.queryByTestId("fix-before-save")).toBeNull();

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));

      expect(run).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId("diff-view")).toBeInTheDocument();
      expect(screen.getByTestId("fix-before-save")).toHaveTextContent(text.fixView.beforeSave);
    });

    it("takes the note away once the scan after the write has read the files", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      await waitFor(() => expect(screen.getByTestId("diff-view")).toBeInTheDocument());

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
      act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: true }));

      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
      await waitFor(() => expect(screen.getByTestId("diff-view")).toBeInTheDocument());
      expect(screen.queryByTestId("fix-before-save")).toBeNull();
    });
  });

  describe("the codepages the proposal reads the files in", () => {
    it("are the overrides the scan and the lint read them in, and a change asks the engine again", async () => {
      const run = vi.fn(async (invocation: EngineInvocation) => finished(invocation));
      install(run);
      render(
        providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />, initialSettingsState, {
          "cobol/A.cbl": "IBM930",
        }),
      );
      await waitFor(() => expect(screen.getByTestId("diff-view")).toBeInTheDocument());
      expect(run.mock.calls[0][0]).toMatchObject({
        subcommand: "fix",
        request: { codepageOverrides: { "cobol/A.cbl": "IBM930" } },
      });

      act(() => dispatchProject({ type: "SET_CODEPAGE", path: "cobol/A.cbl", charset: "IBM939" }));

      await waitFor(() => expect(run).toHaveBeenCalledTimes(2));
      expect(run.mock.calls[1][0]).toMatchObject({
        request: { codepageOverrides: { "cobol/A.cbl": "IBM939" } },
      });
    });
  });

  describe("the write-out button while the engine is busy", () => {
    it("is off and carries text.fixView.applyWaits as its description", async () => {
      install(vi.fn(async (invocation: EngineInvocation) => finished(invocation)));
      render(providers(<FixDiff path="cobol/A.cbl" onNotify={vi.fn()} />));
      const button = await screen.findByTestId("fix-apply");
      expect(button).toBeEnabled();
      expect(button).not.toHaveAttribute("aria-description");

      act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));

      expect(button).toBeDisabled();
      expect(button).toHaveAttribute("aria-description", text.fixView.applyWaits);
      expect(button).toHaveAttribute("title", text.fixView.applyWaits);
    });
  });
});
