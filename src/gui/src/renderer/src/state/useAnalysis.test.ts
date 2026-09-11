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
import { artifactItems, ProjectProvider, useProject } from "./projectStore";
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
    },
    void
  >
> {
  return renderHook(
    () => {
      const project = useProject();
      return {
        analysis: useAnalysis((message) => notified.push(message)),
        log: project.runLog.map((entry) => entry.text),
        findings: artifactItems(project.findings),
        findingsFailed: project.findings.status === "error",
        inventoryMessage:
          project.inventory.status === "error" ? project.inventory.message : "",
      };
    },
    { wrapper },
  );
}

/** The subcommands the engine was asked for, in order. */
function subcommands(api: AnalysisApi): string[] {
  return api.run.mock.calls.map((call) => (call[0] as EngineInvocation).subcommand);
}

beforeEach(() => {
  vi.clearAllMocks();
  notified.length = 0;
});

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

describe("cancelling", () => {
  it("leaves the killed stage's artefact unread and says the stage was cancelled", async () => {
    // The engine is held mid-scan, so the cancel lands while the stage is running, as it does when
    // the user presses the cancel button.
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

    expect(api.cancel).toHaveBeenCalledTimes(1);
    expect(api.readInventory).not.toHaveBeenCalled();
    expect(subcommands(api)).toEqual(["scan"]);
    // The stage was started and cancelled, and nothing claims it finished or failed.
    expect(result.current.log.filter((line) => line.startsWith(text.run.scan))).toEqual([
      text.run.stageStarted(text.run.scan),
      text.run.stageCancelled(text.run.scan),
    ]);
    expect(result.current.log).toContain(text.run.cancelled);
  });

  it("reports the run cancelled when the cancel lands while an artefact is being read", async () => {
    // The lint has finished and its SARIF is being read when the cancel arrives: there is nothing to
    // kill, so the read runs to completion and the stage counts as done, but the run as a whole still
    // ends in the cancelled state rather than the ordinary results state.
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

  it("reports the stage as failed, leaves the artefact unread and shows the engine's reason", async () => {
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
  it("runs lint alone under the scope and keeps the findings outside it", async () => {
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

    // No second scan: the folder was scanned when it was opened.
    expect(subcommands(api)).toEqual(["scan", "lint", "lint"]);
    expect(lintRequests(api)[0].scope).toBeUndefined();
    expect(lintRequests(api)[1].scope).toEqual(["cobol/a.cbl"]);
    // The whole-folder pair is what the report is generated from, so a scoped run writes its own.
    expect(lintRequests(api)[0].sarifFile).toBe(OUTPUT_PATHS.sarif);
    expect(lintRequests(api)[1].sarifFile).toBe(OUTPUT_PATHS.scopedSarif);
    expect(lintRequests(api)[1].sqlSarifFile).toBe(OUTPUT_PATHS.scopedSqlSarif);
    expect(result.current.findings.map((item) => `${item.file} ${item.ruleId}`)).toEqual([
      "jcl/c.jcl R003",
      "cobol/a.cbl R009",
    ]);
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
    expect(notified).toContain(text.run.scopeFailed("cobol/a.cbl"));
    expect(result.current.log).toContain(text.run.stageFailed(text.run.lint));
  });
});
