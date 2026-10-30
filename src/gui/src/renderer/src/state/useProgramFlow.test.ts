import { createElement, type ReactElement, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { CobolInsightApi, ProgramFlow } from "../../../shared/ipc";
import {
  ProjectProvider,
  initialProjectState,
  useProjectDispatch,
  type ProjectState,
} from "./projectStore";
import { useProgramFlow } from "./useProgramFlow";

const READY_PROJECT: ProjectState = {
  ...initialProjectState,
  inputDir: "C:/assets",
  dbPath: "C:/data/p.db",
  mode: "results",
};

function flowOf(name: string): ProgramFlow {
  return {
    paragraphs: [{ id: 1, name, kind: "PARAGRAPH", sectionName: null, startLine: 1, endLine: 2 }],
    edges: [],
  };
}

/** A promise a test settles by hand, so it decides which of two reads answers first. */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason: unknown) => void = () => undefined;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

let readProgramFlow: ReturnType<typeof vi.fn>;

beforeEach(() => {
  readProgramFlow = vi.fn(async () => flowOf("MAIN"));
  Object.defineProperty(window, "cobolInsight", {
    value: { readProgramFlow } as unknown as CobolInsightApi,
    configurable: true,
  });
});

/** The hook under test, with the project dispatch a test uses to move the state it reads. */
function renderFlow(path: string, project: ProjectState = READY_PROJECT) {
  const renders = { count: 0 };
  const wrapper = ({ children }: { children: ReactNode }): ReactElement =>
    createElement(ProjectProvider, { initial: project, children });
  const hook = renderHook(
    ({ path: shown }: { path: string }) => {
      renders.count += 1;
      return { flow: useProgramFlow(shown), dispatch: useProjectDispatch() };
    },
    { wrapper, initialProps: { path } },
  );
  return { ...hook, renders };
}

describe("useProgramFlow", () => {
  it("drops the answer for the old path when the path changed during the read", async () => {
    const old = deferred<ProgramFlow>();
    const current = deferred<ProgramFlow>();
    readProgramFlow.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { result, rerender } = renderFlow("cobol/A.cbl");
    rerender({ path: "cobol/B.cbl" });

    await act(async () => old.resolve(flowOf("OLD")));
    expect(result.current.flow.state).toEqual({ status: "loading" });

    await act(async () => current.resolve(flowOf("NEW")));
    expect(result.current.flow.state).toEqual({ status: "ready", flow: flowOf("NEW") });
  });

  it("keeps the answer for the new path when the old one arrives after it", async () => {
    const old = deferred<ProgramFlow>();
    const current = deferred<ProgramFlow>();
    readProgramFlow.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    const { result, rerender } = renderFlow("cobol/A.cbl");
    rerender({ path: "cobol/B.cbl" });

    await act(async () => current.resolve(flowOf("NEW")));
    await act(async () => old.resolve(flowOf("OLD")));

    expect(result.current.flow.state).toEqual({ status: "ready", flow: flowOf("NEW") });
  });

  it("reads nothing while a run is in progress, and reads once it has finished", async () => {
    const { result } = renderFlow("cobol/A.cbl", { ...READY_PROJECT, mode: "running" });
    await act(async () => undefined);
    expect(readProgramFlow).not.toHaveBeenCalled();

    act(() => result.current.dispatch({ type: "FINISH_RUN", failed: false }));

    await waitFor(() => expect(result.current.flow.state.status).toBe("ready"));
    expect(readProgramFlow).toHaveBeenCalledTimes(1);
  });

  it("reports the flow as reading while the scan after a write is due", async () => {
    const { result } = renderFlow("cobol/A.cbl");
    await waitFor(() => expect(result.current.flow.state.status).toBe("ready"));
    expect(result.current.flow.stale).toBeNull();

    act(() => result.current.dispatch({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
    expect(result.current.flow.stale).toBe("reading");
  });

  it("reports the flow as unread once the scan after a write failed", async () => {
    const { result } = renderFlow("cobol/A.cbl");
    await waitFor(() => expect(result.current.flow.state.status).toBe("ready"));

    act(() => result.current.dispatch({ type: "SOURCE_SAVED", path: "cobol/A.cbl" }));
    act(() => result.current.dispatch({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));
    expect(result.current.flow.stale).toBe("unread");
  });

  it("keeps the same state object when a later read brings back an equal flow", async () => {
    const { result } = renderFlow("cobol/A.cbl");
    await waitFor(() => expect(result.current.flow.state.status).toBe("ready"));
    const held = result.current.flow.state;

    // A finished run moves the artefact epoch, which reads the flow again; the copy is a new object.
    readProgramFlow.mockImplementationOnce(async () => flowOf("MAIN"));
    act(() => result.current.dispatch({ type: "FINISH_RUN", failed: false }));
    await waitFor(() => expect(readProgramFlow).toHaveBeenCalledTimes(2));
    await act(async () => undefined);

    expect(result.current.flow.state).toBe(held);
  });

  it("never shows the late answer of a read for a path that was left and came back", async () => {
    const first = deferred<ProgramFlow>();
    const second = deferred<ProgramFlow>();
    const third = deferred<ProgramFlow>();
    readProgramFlow
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise);
    const { result, rerender } = renderFlow("cobol/A.cbl");
    rerender({ path: "cobol/B.cbl" });
    rerender({ path: "cobol/A.cbl" });

    // The first read of A answers while the third, which belongs to the A on screen, is still out.
    await act(async () => first.resolve(flowOf("STALE-A")));
    expect(result.current.flow.state).toEqual({ status: "loading" });

    await act(async () => third.resolve(flowOf("FRESH-A")));
    expect(result.current.flow.state).toEqual({ status: "ready", flow: flowOf("FRESH-A") });
  });

  it("never shows the late failure of a read for a path that was left and came back", async () => {
    const first = deferred<ProgramFlow>();
    readProgramFlow
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(new Promise<ProgramFlow>(() => undefined))
      .mockReturnValueOnce(new Promise<ProgramFlow>(() => undefined));
    const { result, rerender } = renderFlow("cobol/A.cbl");
    rerender({ path: "cobol/B.cbl" });
    rerender({ path: "cobol/A.cbl" });

    await act(async () => first.reject(new Error("the old read failed")));

    expect(result.current.flow.state).toEqual({ status: "loading" });
  });

  it("shows loading for the new path while a run is in progress, with the old path's flow out of view", async () => {
    const { result, rerender } = renderFlow("cobol/A.cbl");
    await waitFor(() => expect(result.current.flow.state).toEqual({ status: "ready", flow: flowOf("MAIN") }));

    act(() => result.current.dispatch({ type: "START_RUN" }));
    readProgramFlow.mockImplementation(async () => flowOf("OTHER"));
    rerender({ path: "cobol/B.cbl" });

    expect(result.current.flow.state).toEqual({ status: "loading" });
    // The project file is being written, so no read is made for B before the run has finished.
    expect(readProgramFlow).toHaveBeenCalledTimes(1);

    act(() => result.current.dispatch({ type: "FINISH_RUN", failed: false }));
    await waitFor(() => expect(result.current.flow.state).toEqual({ status: "ready", flow: flowOf("OTHER") }));
    expect(readProgramFlow).toHaveBeenCalledTimes(2);
  });
});
