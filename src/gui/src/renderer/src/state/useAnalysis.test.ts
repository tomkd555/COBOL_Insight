import { createElement, type ReactElement, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { CobolInsightApi, EngineInvocation, EngineResult } from "../../../shared/ipc";
import { text } from "../i18n/text";
import { ProjectProvider, useProject } from "./projectStore";
import { SettingsProvider } from "./settingsStore";
import { useAnalysis } from "./useAnalysis";

const INPUT_DIR = "C:/assets";

const OUTPUT_PATHS = {
  db: "C:/data/p.db",
  sarif: "C:/data/lint.sarif",
  sqlSarif: "C:/data/sql.sarif",
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

function engineResult(invocation: EngineInvocation): EngineResult {
  return {
    subcommand: invocation.subcommand,
    exitCode: 0,
    summary: null,
    stdout: "",
    stderr: "",
    outputs: {},
  };
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

/** The hook under test, together with the state it writes to. */
function renderAnalysis(): ReturnType<
  typeof renderHook<{ analysis: ReturnType<typeof useAnalysis>; log: readonly string[] }, void>
> {
  return renderHook(
    () => ({
      analysis: useAnalysis(),
      log: useProject().runLog.map((entry) => entry.text),
    }),
    { wrapper },
  );
}

/** The subcommands the engine was asked for, in order. */
function subcommands(api: AnalysisApi): string[] {
  return api.run.mock.calls.map((call) => (call[0] as EngineInvocation).subcommand);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("opening a folder", () => {
  it("runs the scan and neither lint nor sql-lint", async () => {
    const api = fakeApi();
    const { result } = renderAnalysis();

    await act(async () => {
      await result.current.analysis.scan(INPUT_DIR);
    });

    expect(subcommands(api)).toEqual(["scan"]);
    expect(api.readInventory).toHaveBeenCalledWith(OUTPUT_PATHS.db);
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

  it("does not start the next stage when the cancel lands while an artefact is being read", async () => {
    // The lint has finished and its SARIF is being read when the cancel arrives: there is nothing to
    // kill, so the stage counts as done, and sql-lint must not be launched afterwards.
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
