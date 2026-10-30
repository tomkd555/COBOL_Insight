import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { CobolInsightApi } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { GRAPH, INVENTORY } from "../../model/graphFixture";
import {
  ProjectProvider,
  initialProjectState,
  useProjectDispatch,
  type ProjectAction,
} from "../../state/projectStore";
import { SettingsProvider } from "../../state/settingsStore";
import { WorkbenchProvider } from "../../state/workbenchStore";
import { GraphEditor } from "./GraphEditor";

// The drawing needs a canvas and a layout; what is under test is the tree beside it.
vi.mock("./GraphCanvas", () => ({ GraphCanvas: () => <div data-testid="graph-canvas" /> }));

/** Hands the project's dispatch out, so a case can finish a run the way the shell does. */
let dispatchProject: (action: ProjectAction) => void = () => undefined;
function DispatchProbe(): null {
  dispatchProject = useProjectDispatch();
  return null;
}

/**
 * `read` answers every read of the graph; the default gives the fixture itself. The spy it is wrapped
 * in is handed back, so a case can count the reads.
 */
function renderEditor(
  focusLabel: string | null,
  read: () => Promise<typeof GRAPH> = async () => GRAPH,
): ReturnType<typeof vi.fn> {
  const readGraph = vi.fn(read);
  Object.defineProperty(window, "cobolInsight", {
    value: { readGraph } as unknown as CobolInsightApi,
    configurable: true,
  });
  render(
    <SettingsProvider>
      <ProjectProvider
        initial={{
          ...initialProjectState,
          mode: "results",
          inputDir: "C:/assets",
          dbPath: "C:/data/p.db",
          inventory: { status: "ready", items: INVENTORY },
        }}
      >
        <WorkbenchProvider>
          <DispatchProbe />
          <GraphEditor focusLabel={focusLabel} />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
  return readGraph;
}

/** The rows of the tree, top to bottom, with whether each is the selected one. */
function rows(): { text: string; selected: boolean }[] {
  return within(screen.getByTestId("trace-tree"))
    .getAllByRole("treeitem")
    .map((row) => ({ text: row.textContent ?? "", selected: row.getAttribute("aria-selected") === "true" }));
}

describe("GraphEditor", () => {
  it("opens on the roots of the graph with the deeper rows closed", async () => {
    renderEditor(null);

    const shown = await waitFor(() => {
      const current = rows();
      expect(current).toHaveLength(3);
      return current;
    });
    expect(shown.map((row) => row.text)).toEqual([
      expect.stringContaining("SYKD010"),
      expect.stringContaining("STEP010"),
      expect.stringContaining("STEP020"),
    ]);
    expect(shown.some((row) => row.selected)).toBe(false);
  });

  it("selects the tree row of the node it was opened on, with the rows above it opened", async () => {
    renderEditor("SYK001");

    const shown = await waitFor(() => {
      const current = rows();
      expect(current.some((row) => row.selected)).toBe(true);
      return current;
    });
    // The job and the step above the program are open; the program's own paragraphs stay closed.
    expect(shown.map((row) => row.text)).toEqual([
      expect.stringContaining("SYKD010"),
      expect.stringContaining("STEP010"),
      expect.stringContaining("SYK001"),
      expect.stringContaining("STEP020"),
    ]);
    expect(shown.filter((row) => row.selected).map((row) => row.text)).toEqual([
      expect.stringContaining("SYK001"),
    ]);
  });

  it("makes the row of the node it was opened on the Tab stop of the tree", async () => {
    renderEditor("SYK001");

    // The tree takes the Tab stop to the selected row in the render after the one that selects it.
    await waitFor(() => {
      const stops = within(screen.getByTestId("trace-tree"))
        .getAllByRole("treeitem")
        .filter((row) => row.tabIndex === 0);
      expect(stops.map((row) => row.textContent)).toEqual([expect.stringContaining("SYK001")]);
    });
  });

  it("keeps the node the reader selected, with the rows above it open, after the graph was read again", async () => {
    // Every read answers a copy, as a read of the project file does, so the tree is built again.
    const readGraph = renderEditor("SYK001", async () => structuredClone(GRAPH));
    await waitFor(() => expect(rows().some((row) => row.selected)).toBe(true));
    // The reader picks another node than the one the tab was opened on.
    const step = within(screen.getByTestId("trace-tree"))
      .getAllByRole("treeitem")
      .find((row) => (row.textContent ?? "").includes("STEP020"));
    fireEvent.click(step as HTMLElement);
    const before = await waitFor(() => {
      const current = rows();
      expect(current.filter((row) => row.selected).map((row) => row.text)).toEqual([
        expect.stringContaining("STEP020"),
      ]);
      return current;
    });

    // A run that finished moves the artefact epoch: the graph is read again.
    act(() => dispatchProject({ type: "FINISH_RUN", failed: false }));

    await waitFor(() => expect(readGraph).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId("graph-rereading")).toBeNull());
    const after = rows();
    expect(after).toEqual(before);
    expect(after.filter((row) => row.selected).map((row) => row.text)).toEqual([
      expect.stringContaining("STEP020"),
    ]);
  });

  it("keeps the drawing and the tree on screen with a note while the graph is read again", async () => {
    let answer: (graph: typeof GRAPH) => void = () => undefined;
    let reads = 0;
    renderEditor("SYK001", () => {
      reads += 1;
      return reads === 1
        ? Promise.resolve(structuredClone(GRAPH))
        : new Promise((done) => {
            answer = done;
          });
    });
    const before = await waitFor(() => {
      const current = rows();
      expect(current.some((row) => row.selected)).toBe(true);
      return current;
    });
    expect(screen.queryByTestId("graph-rereading")).toBeNull();

    act(() => dispatchProject({ type: "FINISH_RUN", failed: false }));

    expect(await screen.findByTestId("graph-rereading")).toHaveTextContent(text.graph.rereading);
    expect(screen.getByTestId("graph-canvas")).toBeInTheDocument();
    expect(rows()).toEqual(before);

    await act(async () => answer(structuredClone(GRAPH)));

    expect(screen.queryByTestId("graph-rereading")).toBeNull();
    expect(rows()).toEqual(before);
  });

  it("says when a write waits for its scan, and when that scan failed", async () => {
    renderEditor(null);
    await waitFor(() => expect(rows()).toHaveLength(3));
    expect(screen.queryByTestId("graph-structure")).toBeNull();

    act(() => dispatchProject({ type: "SOURCE_SAVED", path: "cobol/SYK001.cbl" }));
    expect(screen.getByTestId("graph-structure")).toHaveTextContent(text.graph.structureReading);

    act(() => dispatchProject({ type: "STRUCTURE_SCANNED", writes: 1, ok: false }));
    expect(screen.getByTestId("graph-structure")).toHaveTextContent(text.graph.structureUnread);
  });

  it("keeps a row the reader opened open after the graph was read again", async () => {
    renderEditor(null, async () => structuredClone(GRAPH));
    await waitFor(() => expect(rows()).toHaveLength(3));
    const step = within(screen.getByTestId("trace-tree"))
      .getAllByRole("treeitem")
      .find((row) => (row.textContent ?? "").includes("STEP010"));
    fireEvent.keyDown(step as HTMLElement, { key: "ArrowRight" });
    const opened = await waitFor(() => {
      const current = rows();
      expect(current).toHaveLength(4);
      return current;
    });

    act(() => dispatchProject({ type: "FINISH_RUN", failed: false }));

    await waitFor(() => expect(screen.queryByTestId("graph-rereading")).toBeNull());
    expect(rows()).toEqual(opened);
  });
});
