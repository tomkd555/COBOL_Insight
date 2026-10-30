import { createElement, type ReactElement, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type {
  CobolInsightApi,
  EngineInvocation,
  EngineResult,
  LintRequest,
  SarifFinding,
} from "../../../shared/ipc";
import { text } from "../i18n/text";
import { viewRun } from "./engineGate";
import { artifactItems, ProjectProvider, useProject, useProjectDispatch } from "./projectStore";
import { SettingsProvider } from "./settingsStore";
import { useAnalysis } from "./useAnalysis";

const INPUT_DIR = "C:/assets";

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
  scopedSarif: "C:/data/scope.sarif",
  scopedSqlSarif: "C:/data/scope-sql.sarif",
  copyExpansion: "C:/data/copy.json",
  rules: "C:/data/rules.json",
};

/** The members of the published API the analysis uses. The rest is out of this hook's reach. */
interface AnalysisApi {
  run: ReturnType<typeof vi.fn>;
  cancel: ReturnType<typeof vi.fn>;
  outputPaths: ReturnType<typeof vi.fn>;
  readInventory: ReturnType<typeof vi.fn>;
  readSarif: ReturnType<typeof vi.fn>;
}

/** A finished run: the engine always prints its summary as the last line of stdout. */
function engineResult(invocation: EngineInvocation): EngineResult {
  return {
    subcommand: invocation.subcommand,
    exitCode: 0,
    summary: { findingCount: 0 },
    stdout: "",
    stderr: "",
    outputs: {},
  };
}

function finding(file: string, ruleId = "R001"): SarifFinding {
  return { ruleId, level: "warning", message: "m", file, startLine: 1, startColumn: 1 };
}

/** The lint requests the engine was given, so a test can read the scope off them. */
function lintRequests(api: AnalysisApi): LintRequest[] {
  return api.run.mock.calls
    .map((call) => call[0] as EngineInvocation)
    .filter((invocation) => invocation.subcommand === "lint")
    .map((invocation) => invocation.request as LintRequest);
}

function fakeApi(overrides: Partial<AnalysisApi> = {}): AnalysisApi {
  const api: AnalysisApi = {
    run: vi.fn(async (invocation: EngineInvocation) => engineResult(invocation)),
    cancel: vi.fn(async () => undefined),
    outputPaths: vi.fn(async () => OUTPUT_PATHS),
    readInventory: vi.fn(async () => []),
    readSarif: vi.fn(async () => []),
    ...overrides,
  };
  Object.defineProperty(window, "cobolInsight", {
    value: api as unknown as CobolInsightApi,
    configurable: true,
  });
  return api;
}

function wrapper({ children }: { children: ReactNode }): ReactElement {
  return createElement(SettingsProvider, null, createElement(ProjectProvider, null, children));
}

/** What a failed scoped run reports through, in place of replacing the findings. */
const notified: string[] = [];

/** The hook under test, together with the state it writes to. */
function renderAnalysis(): ReturnType<
  typeof renderHook<
    {
      analysis: ReturnType<typeof useAnalysis>;
      log: readonly string[];
      findings: readonly SarifFinding[];
      findingsFailed: boolean;
      inventoryMessage: string;
      inventoryCount: number;
      dbPath: string | null;
      dispatch: ReturnType<typeof useProjectDispatch>;
      stale: readonly string[];
      runId: number;
      mode: string;
    },
    void
  >
> {
  return renderHook(
    () => {
      const project = useProject();
      return {
        dispatch: useProjectDispatch(),
        stale: project.structureStale,
        runId: project.runId,
        mode: project.mode,
        analysis: useAnalysis((message) => notified.push(message)),
        log: project.runLog.map((entry) => entry.text),
        findings: artifactItems(project.findings),
        findingsFailed: project.findings.status === "error",
        inventoryMessage:
          project.inventory.status === "error" ? project.inventory.message : "",
        inventoryCount: artifactItems(project.inventory).length,
        dbPath: project.dbPath,
      };
    },
    { wrapper },
  );
}

type AnalysisView = ReturnType<typeof renderAnalysis>;

/** The subcommands the engine was asked for, in order. */
function subcommands(api: AnalysisApi): string[] {
  return api.run.mock.calls.map((call) => (call[0] as EngineInvocation).subcommand);
}

beforeEach(() => {
  vi.clearAllMocks();
  notified.length = 0;
});

describe("useAnalysis", () => {
  describe("opening a folder", () => {
    it("runs the scan alone, without lint", async () => {
      const api = fakeApi();
      const { result } = renderAnalysis();

      await act(async () => {
        await result.current.analysis.scan(INPUT_DIR);
      });

      expect(subcommands(api)).toEqual(["scan"]);
      expect(api.readInventory).toHaveBeenCalledWith(OUTPUT_PATHS.db, INPUT_DIR);
      expect(api.readSarif).not.toHaveBeenCalled();
      expect(result.current.log).not.toContain(text.run.started);
      expect(result.current.log).toContain(text.run.scanDone(0));
    });
  });

  describe("bringing the structure up to date", () => {
    /** Runs the whole folder, then refreshes the structure; the run's findings are on screen first. */
    async function refreshedAfterRun(): Promise<{
      api: ReturnType<typeof fakeApi>;
      result: AnalysisView["result"];
    }> {
      const api = fakeApi({ readSarif: vi.fn(async () => [finding("cobol/a.cbl")]) });
      const { result } = renderAnalysis();
      await act(async () => {
        await result.current.analysis.run(INPUT_DIR);
      });
      expect(result.current.findings).toHaveLength(1);
      api.run.mockClear();
      api.readSarif.mockClear();

      await act(async () => {
        await result.current.analysis.refresh(INPUT_DIR);
      });
      return { api, result };
    }

    it("refreshes by scanning alone", async () => {
      const { api } = await refreshedAfterRun();
      expect(subcommands(api)).toEqual(["scan"]);
      expect(api.readSarif).not.toHaveBeenCalled();
    });

    it("leaves the findings on screen after a refresh", async () => {
      const { result } = await refreshedAfterRun();
      expect(result.current.findings).toHaveLength(1);
    });

    /** Opens the folder scanned, writes one asset, then lints that asset alone. */
    async function scopedLintOfWrittenAsset(): Promise<{
      api: ReturnType<typeof fakeApi>;
      result: AnalysisView["result"];
      staleBefore: readonly string[];
    }> {
      const api = fakeApi();
      const { result } = renderAnalysis();
      // The folder is open and scanned before anything is written into it.
      await act(async () => {
        await result.current.analysis.scan(INPUT_DIR);
      });
      api.run.mockClear();
      act(() => result.current.dispatch({ type: "SOURCE_SAVED", path: "cobol/a.cbl" }));
      const staleBefore = result.current.stale;

      await act(async () => {
        await result.current.analysis.runScope(INPUT_DIR, "cobol/a.cbl");
      });
      return { api, result, staleBefore };
    }

    it("scans before a scoped lint when written assets are unread", async () => {
      const { api } = await scopedLintOfWrittenAsset();
      expect(subcommands(api)).toEqual(["scan", "lint"]);
    });

    it("clears the stale mark after a scoped lint of written assets", async () => {
      const { result, staleBefore } = await scopedLintOfWrittenAsset();
      expect(staleBefore).toEqual(["cobol/a.cbl"]);
      expect(result.current.stale).toEqual([]);
    });

    it("leaves the mark after a scan that failed", async () => {
      fakeApi({
        readInventory: vi
          .fn()
          .mockResolvedValueOnce([])
          .mockRejectedValueOnce(new Error("unreadable")),
      });
      const { result } = renderAnalysis();
      await act(async () => {
        await result.current.analysis.scan(INPUT_DIR);
      });
      act(() => result.current.dispatch({ type: "SOURCE_SAVED", path: "cobol/a.cbl" }));

      await act(async () => {
        await result.current.analysis.refresh(INPUT_DIR);
      });

      expect(result.current.stale).toEqual(["cobol/a.cbl"]);
    });

    /** Opens the folder on an inventory of one asset, then writes that asset. */
    async function scannedThenWritten(api: AnalysisApi): Promise<AnalysisView> {
      const view = renderAnalysis();
      await act(async () => {
        await view.result.current.analysis.scan(INPUT_DIR);
      });
      expect(view.result.current.inventoryCount).toBe(1);
      act(() => view.result.current.dispatch({ type: "SOURCE_SAVED", path: "cobol/a.cbl" }));
      api.run.mockClear();
      return view;
    }

    const ASSET = { id: 1, path: "cobol/a.cbl", name: "a.cbl", type: "PROGRAM", codepage: "", findingCount: 0 };

    it("keeps the inventory and project file when a scan after a write crashes", async () => {
      let scans = 0;
      const api = fakeApi({
        readInventory: vi.fn(async () => [ASSET]),
        run: vi.fn(async (invocation: EngineInvocation) => {
          scans += 1;
          return scans === 2 ? { ...engineResult(invocation), exitCode: 1, summary: null } : engineResult(invocation);
        }),
      });
      const { result } = await scannedThenWritten(api);

      await act(async () => {
        await result.current.analysis.refresh(INPUT_DIR);
      });

      expect(subcommands(api)).toEqual(["scan"]);
      expect(result.current.inventoryCount).toBe(1);
      expect(result.current.inventoryMessage).toBe("");
      expect(result.current.dbPath).toBe(OUTPUT_PATHS.db);
      expect(result.current.stale).toEqual(["cobol/a.cbl"]);
    });

    it("keeps the inventory and project file when the rescan cannot be read", async () => {
      const api = fakeApi({
        readInventory: vi.fn().mockResolvedValueOnce([ASSET]).mockRejectedValueOnce(new Error("unreadable")),
      });
      const { result } = await scannedThenWritten(api);

      await act(async () => {
        await result.current.analysis.refresh(INPUT_DIR);
      });

      expect(result.current.inventoryCount).toBe(1);
      expect(result.current.inventoryMessage).toBe("");
      expect(result.current.dbPath).toBe(OUTPUT_PATHS.db);
      expect(result.current.stale).toEqual(["cobol/a.cbl"]);
    });

    it("clears the inventory and project file when a requested scan crashes", async () => {
      let scans = 0;
      const api = fakeApi({
        readInventory: vi.fn(async () => [ASSET]),
        run: vi.fn(async (invocation: EngineInvocation) => {
          scans += 1;
          return scans === 2 ? { ...engineResult(invocation), exitCode: 1, summary: null } : engineResult(invocation);
        }),
      });
      const { result } = await scannedThenWritten(api);

      await act(async () => {
        await result.current.analysis.scan(INPUT_DIR);
      });

      expect(result.current.inventoryCount).toBe(0);
      expect(result.current.dbPath).toBeNull();
    });
  });

  describe("one engine at a time", () => {
    /** Starts a scan behind a view's run that has not ended, then cancels it. */
    async function scanBehindViewRun(): Promise<{
      api: ReturnType<typeof fakeApi>;
      result: AnalysisView["result"];
      launchedBeforeCancel: string[];
    }> {
      const api = fakeApi();
      const { result } = renderAnalysis();
      let release = (): void => undefined;
      const viewDone = viewRun(() => new Promise<void>((resolve) => (release = resolve)));

      let scanned: Promise<void> = Promise.resolve();
      await act(async () => {
        scanned = result.current.analysis.scan(INPUT_DIR);
        await Promise.resolve();
      });
      // The view's run is still going: the scan has not been launched.
      const launchedBeforeCancel = subcommands(api);

      await act(async () => {
        // The engine in flight is the view's, so the cancel stops no process; the scan is not started.
        await result.current.analysis.cancel();
        release();
        await viewDone;
        await scanned;
      });
      return { api, result, launchedBeforeCancel };
    }

    it("does not launch the scan while a view's run is in flight", async () => {
      const { launchedBeforeCancel } = await scanBehindViewRun();
      expect(launchedBeforeCancel).toEqual([]);
    });

    it("launches nothing after a cancel while a view's run is in flight", async () => {
      const { api } = await scanBehindViewRun();
      expect(subcommands(api)).toEqual([]);
    });

    it("stops no process on a cancel while a view's run is in flight", async () => {
      const { api } = await scanBehindViewRun();
      expect(api.cancel).not.toHaveBeenCalled();
    });

    it("logs the cancel while a view's run is in flight", async () => {
      const { result } = await scanBehindViewRun();
      expect(result.current.log).toContain(text.run.cancelled);
    });

    it("ends the run at a cancel while a view's run is still going", async () => {
      const api = fakeApi();
      const { result } = renderAnalysis();
      let release = (): void => undefined;
      const viewDone = viewRun(() => new Promise<void>((resolve) => (release = resolve)));

      let scanned: Promise<void> = Promise.resolve();
      await act(async () => {
        scanned = result.current.analysis.scan(INPUT_DIR);
        await Promise.resolve();
      });
      expect(result.current.mode).toBe("running");

      await act(async () => {
        await result.current.analysis.cancel();
        await scanned;
      });

      // The view's run has not ended, and the analysis is over all the same.
      expect(result.current.mode).not.toBe("running");
      expect(result.current.log).toContain(text.run.cancelled);
      expect(subcommands(api)).toEqual([]);
      release();
      await viewDone;
    });
  });

  describe("cancelling", () => {
    /** Cancels while the engine is held mid-scan, as the cancel button does. */
    async function cancelledMidScan(): Promise<{
      api: ReturnType<typeof fakeApi>;
      result: AnalysisView["result"];
    }> {
      let release = (): void => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const api = fakeApi({
        run: vi.fn(async (invocation: EngineInvocation) => {
          await held;
          // A killed engine prints no summary.
          return { ...engineResult(invocation), exitCode: -1, summary: null };
        }),
      });
      const { result } = renderAnalysis();

      let running: Promise<void> = Promise.resolve();
      await act(async () => {
        running = result.current.analysis.run(INPUT_DIR);
      });
      await waitFor(() => expect(api.run).toHaveBeenCalledTimes(1));
      await act(async () => {
        await result.current.analysis.cancel();
        release();
        await running;
      });
      return { api, result };
    }

    it("ends a killed stage as cancelled", async () => {
      const { api, result } = await cancelledMidScan();

      expect(api.cancel).toHaveBeenCalledTimes(1);
      expect(subcommands(api)).toEqual(["scan"]);
      // The stage was started and cancelled, and nothing claims it finished or failed.
      expect(result.current.log.filter((line) => line.startsWith(text.run.scan))).toEqual([
        text.run.stageStarted(text.run.scan),
        text.run.stageCancelled(text.run.scan),
      ]);
      expect(result.current.log).toContain(text.run.cancelled);
    });

    it("reads no inventory after a killed scan", async () => {
      const { api } = await cancelledMidScan();
      expect(api.readInventory).not.toHaveBeenCalled();
    });

    it("reads the inventory of a scan that finished before the cancel", async () => {
      // The cancel arrives while the stage is in flight, and the engine has finished by then: the
      // project file is rewritten, so the scan is read and recorded as read, and the run then ends
      // as cancelled without starting lint.
      let release = (): void => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const api = fakeApi({
        run: vi.fn(async (invocation: EngineInvocation) => {
          await held;
          return engineResult(invocation);
        }),
      });
      const { result } = renderAnalysis();
      expect(result.current.runId).toBe(0);

      let running: Promise<void> = Promise.resolve();
      await act(async () => {
        running = result.current.analysis.run(INPUT_DIR);
      });
      await waitFor(() => expect(api.run).toHaveBeenCalledTimes(1));
      await act(async () => {
        await result.current.analysis.cancel();
        release();
        await running;
      });

      expect(api.readInventory).toHaveBeenCalledWith(OUTPUT_PATHS.db, INPUT_DIR);
      expect(result.current.runId).toBe(1);
      expect(subcommands(api)).toEqual(["scan"]);
      expect(result.current.mode).toBe("results");
      expect(result.current.log).toContain(text.run.scanDone(0));
      expect(result.current.log).toContain(text.run.cancelled);
      expect(result.current.log).not.toContain(text.run.stageCancelled(text.run.scan));
      expect(result.current.log).not.toContain(text.run.stageStarted(text.run.lint));
    });

    it("ends the run as cancelled when the cancel lands mid-read", async () => {
      // The lint has finished and its SARIF is being read when the cancel arrives: there is nothing to
      // kill, so the read runs to completion and the stage counts as done, but the run as a whole still
      // ends as cancelled: the log closes with the cancelled line, and the run is never recorded as
      // finished (no FINISH_RUN).
      let release = (): void => undefined;
      const held = new Promise<never[]>((resolve) => {
        release = () => resolve([]);
      });
      const api = fakeApi({ readSarif: vi.fn(() => held) });
      const { result } = renderAnalysis();

      let running: Promise<void> = Promise.resolve();
      await act(async () => {
        running = result.current.analysis.run(INPUT_DIR);
      });
      await waitFor(() => expect(api.readSarif).toHaveBeenCalledTimes(1));
      await act(async () => {
        await result.current.analysis.cancel();
        release();
        await running;
      });

      expect(subcommands(api)).toEqual(["scan", "lint"]);
      expect(result.current.log).toContain(text.run.stageDone(text.run.lint, 0));
      expect(result.current.log).toContain(text.run.cancelled);
    });
  });

  describe("a run the engine did not finish", () => {
    // A finished scan or lint always prints its summary JSON as the last line of stdout. Without it
    // the process died, and the artefacts on disk are the previous run's.
    // A JVM crash writes the exception first and its stack frames, each indented, below it.
    const TRACE = [
      'Exception in thread "main" java.lang.StackOverflowError',
      "\tat jp.cobolinsight.frontend.cobol.Che4zCobolParser.parse(Che4zCobolParser.java:88)",
      "\tat jp.cobolinsight.app.pipeline.Parse.run(Parse.java:41)",
      "\tat jp.cobolinsight.app.pipeline.Pipelines.run(Pipelines.java:120)",
      "\tat jp.cobolinsight.app.cli.LintRunner.run(LintRunner.java:102)",
      "\tat jp.cobolinsight.app.cli.LintCommand.call(LintCommand.java:70)",
      "\tat jp.cobolinsight.app.cli.Main.main(Main.java:38)",
    ];

    const crashed = (invocation: EngineInvocation): EngineResult => ({
      ...engineResult(invocation),
      // Finding severity lives in the exit code, so the code says nothing about a crash.
      exitCode: 1,
      summary: null,
      stderr: `${TRACE.join("\n")}\n`,
    });

    it("reports a crashed stage as failed with the engine's reason", async () => {
      const api = fakeApi({ run: vi.fn(async (invocation: EngineInvocation) => crashed(invocation)) });
      const { result } = renderAnalysis();

      await act(async () => {
        await result.current.analysis.run(INPUT_DIR);
      });

      expect(api.readInventory).not.toHaveBeenCalled();
      expect(api.readSarif).not.toHaveBeenCalled();
      expect(result.current.log).toContain(text.run.stageFailed(text.run.scan));
      // The scan wrote no project file, so the lint that would fill the problems panel with findings
      // for assets the explorer cannot list is not started at all.
      expect(subcommands(api)).toEqual(["scan"]);
      expect(result.current.log).not.toContain(text.run.stageStarted(text.run.lint));
      // What went wrong is on the first line of the trace, so the reason keeps it in front of the
      // frames the tail holds; the whole trace is in the log line by line and is not repeated there.
      expect(result.current.inventoryMessage.split("\n")[0]).toBe(TRACE[0]);
      expect(result.current.inventoryMessage).toContain(TRACE[TRACE.length - 1]);
      expect(result.current.log.filter((line) => line === TRACE[0])).toHaveLength(1);
    });

    it("puts the engine's warnings into the run log even when the run finished", async () => {
      const warning = "警告: 1件は種別を判別できなかったため対象から外しました。";
      const api = fakeApi({
        run: vi.fn(async (invocation: EngineInvocation) => ({
          ...engineResult(invocation),
          stderr: `${warning}\n\n`,
        })),
      });
      const { result } = renderAnalysis();

      await act(async () => {
        await result.current.analysis.scan(INPUT_DIR);
      });

      expect(api.readInventory).toHaveBeenCalledTimes(1);
      expect(result.current.log.filter((line) => line === warning)).toHaveLength(1);
    });
  });

  describe("analysing a selection", () => {
    /** Runs the whole folder, then one asset under a scope that reports its own finding. */
    async function wholeThenScoped(): Promise<{
      api: ReturnType<typeof fakeApi>;
      result: AnalysisView["result"];
    }> {
      let sarif: SarifFinding[] = [finding("cobol/a.cbl", "R001"), finding("jcl/c.jcl", "R003")];
      const api = fakeApi({
        // The whole-folder run reads the one pair, the scoped run the other.
        readSarif: vi.fn(async (path: string) =>
          path === OUTPUT_PATHS.sarif || path === OUTPUT_PATHS.scopedSarif ? sarif : [],
        ),
      });
      const { result } = renderAnalysis();

      await act(async () => {
        await result.current.analysis.run(INPUT_DIR);
      });
      // What the scoped run reports about the one asset it analysed.
      sarif = [finding("cobol/a.cbl", "R009")];
      await act(async () => {
        await result.current.analysis.runScope(INPUT_DIR, "cobol/a.cbl");
      });
      return { api, result };
    }

    it("runs lint alone under the scope", async () => {
      const { api } = await wholeThenScoped();
      // No second scan: the folder was scanned when it was opened.
      expect(subcommands(api)).toEqual(["scan", "lint", "lint"]);
      expect(lintRequests(api)[0].scope).toBeUndefined();
      expect(lintRequests(api)[1].scope).toEqual(["cobol/a.cbl"]);
    });

    it("writes the scoped SARIF files apart from the whole-folder pair", async () => {
      const { api } = await wholeThenScoped();
      // The whole-folder pair is what the report is generated from, so a scoped run writes its own.
      expect(lintRequests(api)[0].sarifFile).toBe(OUTPUT_PATHS.sarif);
      expect(lintRequests(api)[1].sarifFile).toBe(OUTPUT_PATHS.scopedSarif);
      expect(lintRequests(api)[1].sqlSarifFile).toBe(OUTPUT_PATHS.scopedSqlSarif);
    });

    it("merges the scoped run's findings into those outside the scope", async () => {
      const { result } = await wholeThenScoped();
      expect(result.current.findings.map((item) => `${item.file} ${item.ruleId}`)).toEqual([
        "jcl/c.jcl R003",
        "cobol/a.cbl R009",
      ]);
    });

    it("logs the start of a scoped run", async () => {
      const { result } = await wholeThenScoped();
      expect(result.current.log).toContain(text.run.startedScope("cobol/a.cbl"));
    });

    it("keeps the findings it did not cover when the scoped run fails", async () => {
      const api = fakeApi({
        readSarif: vi.fn(async (path: string) =>
          path === OUTPUT_PATHS.sarif ? [finding("cobol/a.cbl"), finding("jcl/c.jcl")] : [],
        ),
      });
      const { result } = renderAnalysis();

      await act(async () => {
        await result.current.analysis.run(INPUT_DIR);
      });
      // The asset was deleted between the two runs, so the engine refuses the scope and prints no
      // summary. Nothing was re-analysed, so nothing on screen has been overtaken.
      api.run.mockImplementation(async (invocation: EngineInvocation) => ({
        ...engineResult(invocation),
        summary: null,
        stderr: "「cobol/a.cbl」は資産フォルダの中に見つかりませんでした。",
      }));
      await act(async () => {
        await result.current.analysis.runScope(INPUT_DIR, "cobol/a.cbl");
      });

      expect(result.current.findingsFailed).toBe(false);
      expect(result.current.findings).toHaveLength(2);
      expect(notified).toContain(
        text.run.scopeFailed("cobol/a.cbl", "「cobol/a.cbl」は資産フォルダの中に見つかりませんでした。"),
      );
      expect(result.current.log).toContain(text.run.stageFailed(text.run.lint));
    });
  });
});
