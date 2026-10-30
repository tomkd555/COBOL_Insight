import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render } from "@testing-library/react";
import type { GraphElement } from "../../model/graphLayout";
import { GraphCanvas } from "./GraphCanvas";

/** The cores and layouts the canvas asked the library for, in order. */
const made = vi.hoisted(() => ({
  cores: [] as FakeCore[],
  layouts: [] as FakeLayout[],
  /** Set by a case that wants `layout.run()` to throw. */
  runFails: false,
  /** The zoom `core.fit` leaves the drawing at: what the size of the drawing decides in the library. */
  fitZoom: 1,
  /** Where a node's top edge is drawn, in the pixels of the viewport. */
  nodeTop: 0,
  /** The callbacks of the resize observers the canvas started. */
  observers: [] as (() => void)[],
}));

interface FakeLayout {
  run: ReturnType<typeof vi.fn>;
  one(event: string, handler: () => void): void;
  /** Delivers the stop event the layout library sends once the positions are in, as its stop() does. */
  stop: ReturnType<typeof vi.fn>;
}

interface FakeCore {
  add: ReturnType<typeof vi.fn>;
  destroy: ReturnType<typeof vi.fn>;
  zoom: ReturnType<typeof vi.fn>;
  fit: ReturnType<typeof vi.fn>;
  center: ReturnType<typeof vi.fn>;
  panBy: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  [member: string]: unknown;
}

vi.mock("../../vendor/graphLibrary", () => ({
  graphLibrary: () => () => {
    /** The nodes added so far, and the view the drawing is in. */
    const ids: string[] = [];
    const view = { zoom: 1, pan: { x: 0, y: 0 } };
    const collection = (members: string[]) => ({
      ids: members,
      remove: vi.fn(),
      removeClass: vi.fn(),
      addClass: vi.fn(),
      empty: () => members.length === 0,
      nonempty: () => members.length > 0,
      first: () => collection(members.slice(0, 1)),
      renderedBoundingBox: () => ({ y1: made.nodeTop }),
    });
    const core: FakeCore = {
      add: vi.fn((added: { group: string; data: { id: string } }[]) => {
        for (const element of added) {
          if (element.group === "nodes") ids.push(element.data.id);
        }
      }),
      destroy: vi.fn(),
      on: vi.fn(),
      batch: (change: () => void) => change(),
      elements: () => ({
        remove: () => {
          ids.length = 0;
        },
      }),
      nodes: () => collection([...ids]),
      getElementById: (id: string) => collection(ids.includes(id) ? [id] : []),
      style: vi.fn(),
      // Without an argument the zoom is read; with one it is set, as the library has it.
      zoom: vi.fn((level?: number | { level: number }) => {
        if (level === undefined) return view.zoom;
        view.zoom = typeof level === "number" ? level : level.level;
        return undefined;
      }),
      pan: () => ({ ...view.pan }),
      panBy: vi.fn((delta: { x: number; y: number }) => {
        view.pan = { x: view.pan.x + delta.x, y: view.pan.y + delta.y };
      }),
      fit: vi.fn(() => {
        view.zoom = made.fitZoom;
        view.pan = { x: 5, y: 5 };
      }),
      center: vi.fn(() => {
        view.pan = { x: 7, y: 7 };
      }),
      resize: vi.fn(),
      width: () => 800,
      height: () => 600,
      layout: () => {
        let stopped: (() => void) | null = null;
        const layout: FakeLayout = {
          run: vi.fn(() => {
            if (made.runFails) {
              throw new Error("elk refused");
            }
          }),
          one: (_event, handler) => {
            stopped = handler;
          },
          stop: vi.fn(() => stopped?.()),
        };
        made.layouts.push(layout);
        return layout;
      },
    };
    made.cores.push(core);
    return core;
  },
}));

const FIRST: GraphElement[] = [{ group: "nodes", data: { id: "A", label: "A", kind: "PROGRAM" } }];
const SECOND: GraphElement[] = [{ group: "nodes", data: { id: "B", label: "B", kind: "PROGRAM" } }];

function canvas(elements: GraphElement[], onLayoutRunning: (running: boolean) => void) {
  return (
    <GraphCanvas
      elements={elements}
      selectedId={null}
      theme="light"
      onSelectNode={vi.fn()}
      onLayoutRunning={onLayoutRunning}
    />
  );
}

/** Lets the timers due within `ms` run, which is how a layout's stop is settled. */
function advance(ms: number): void {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

beforeEach(() => {
  made.cores.length = 0;
  made.layouts.length = 0;
  made.runFails = false;
  made.fitZoom = 1;
  made.nodeTop = 0;
  made.observers.length = 0;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("GraphCanvas", () => {
  describe("the layout of the drawing", () => {
    it("draws the elements swapped in while it ran once it has stopped", () => {
      const { rerender } = render(canvas(FIRST, vi.fn()));
      rerender(canvas(SECOND, vi.fn()));

      // The layout in flight still holds the first elements; the swap waits for it.
      expect(made.cores[0].add).toHaveBeenCalledTimes(1);
      expect(made.cores[0].add).toHaveBeenLastCalledWith(FIRST);

      made.layouts[0].stop();
      advance(0);

      expect(made.cores[0].add).toHaveBeenCalledTimes(2);
      expect(made.cores[0].add).toHaveBeenLastCalledWith(SECOND);
      expect(made.layouts).toHaveLength(2);
    });

    it("destroys the core on unmount once the layout in flight has stopped", () => {
      const { unmount } = render(canvas(FIRST, vi.fn()));
      unmount();

      expect(made.cores[0].destroy).not.toHaveBeenCalled();

      made.layouts[0].stop();
      advance(0);

      expect(made.cores[0].destroy).toHaveBeenCalledTimes(1);
    });

    it("destroys the core on unmount at once when no layout is in flight", () => {
      const { unmount } = render(canvas([], vi.fn()));
      made.layouts[0].stop();
      advance(0);
      unmount();

      expect(made.cores[0].destroy).toHaveBeenCalledTimes(1);
    });

    it("is given up after the watchdog time, reporting that no layout is running", () => {
      const onLayoutRunning = vi.fn();
      render(canvas(FIRST, onLayoutRunning));
      expect(onLayoutRunning.mock.calls).toEqual([[true]]);

      // The watchdog is 30 seconds (LAYOUT_TIMEOUT_MS in GraphCanvas.tsx).
      advance(29_999);
      expect(onLayoutRunning.mock.calls).toEqual([[true]]);
      advance(1);
      expect(onLayoutRunning.mock.calls).toEqual([[true], [false]]);

      // A stop that arrives after the watchdog has nothing left to report.
      made.layouts[0].stop();
      advance(0);
      expect(onLayoutRunning.mock.calls).toEqual([[true], [false]]);
    });

    it("stops the layout the watchdog gives up on, and draws the next elements without waiting for it", () => {
      const onLayoutRunning = vi.fn();
      const { rerender } = render(canvas(FIRST, onLayoutRunning));
      rerender(canvas(SECOND, onLayoutRunning));

      advance(30_000);

      expect(made.layouts[0].stop).toHaveBeenCalledTimes(1);
      expect(made.cores[0].add).toHaveBeenLastCalledWith(SECOND);
      expect(made.layouts).toHaveLength(2);

      // A late stop of the released layout settles nothing of the one that took its place.
      made.layouts[0].stop();
      advance(0);
      expect(onLayoutRunning.mock.calls).toEqual([[true], [false], [true]]);
    });

    it("lays nothing out again for elements read again unchanged", () => {
      const { rerender } = render(canvas(FIRST, vi.fn()));
      made.layouts[0].stop();
      advance(0);

      rerender(canvas(structuredClone(FIRST), vi.fn()));

      expect(made.cores[0].add).toHaveBeenCalledTimes(1);
      expect(made.layouts).toHaveLength(1);
    });

    it("reports that no layout is running when layout.run throws", () => {
      made.runFails = true;
      const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
      const onLayoutRunning = vi.fn();

      render(canvas(FIRST, onLayoutRunning));

      expect(onLayoutRunning.mock.calls).toEqual([[true], [false]]);
      expect(error).toHaveBeenCalledTimes(1);
    });
  });

  const THREE: GraphElement[] = ["A", "B", "C"].map((id) => ({
    group: "nodes",
    data: { id, label: id, kind: "PARAGRAPH" },
  }));

  interface ViewProps {
    selectedId?: string | null;
    direction?: "RIGHT" | "DOWN";
    readable?: boolean;
  }

  function drawing({ selectedId = null, direction = "RIGHT", readable = false }: ViewProps) {
    return (
      <GraphCanvas
        elements={THREE}
        selectedId={selectedId}
        theme="light"
        direction={direction}
        readable={readable}
        onSelectNode={vi.fn()}
        onLayoutRunning={vi.fn()}
      />
    );
  }

  /** What the last call of a spy on the core was given. */
  function lastCall(spy: unknown): unknown[] {
    return (spy as ReturnType<typeof vi.fn>).mock.calls.at(-1) ?? [];
  }

  describe("the view of a readable drawing", () => {
    /** Mounts the drawing and lets its layout stop, which is when it is framed. */
    function framed(props: ViewProps) {
      const view = render(drawing({ ...props, readable: true }));
      made.layouts[0].stop();
      advance(0);
      return { core: made.cores[0], view };
    }

    it("shows a drawing whole at zoom 1, where it fits at the labels' own size", () => {
      made.fitZoom = 1;
      const { core } = framed({ selectedId: "B" });

      expect(lastCall(core.fit)).toEqual([undefined, 12]);
      expect(core.zoom()).toBe(1);
      expect(core.center).not.toHaveBeenCalled();
    });

    it("shows a drawing that would fit only under zoom 1 at zoom 1, centred on the selected node", () => {
      made.fitZoom = 0.9;
      const { core } = framed({ selectedId: "B" });

      expect(core.zoom()).toBe(1);
      expect((lastCall(core.center)[0] as { ids: string[] }).ids).toEqual(["B"]);
    });

    it("never draws above zoom 1, and centres what it shows", () => {
      made.fitZoom = 2.5;
      const { core } = framed({ selectedId: "B" });

      expect(core.zoom()).toBe(1);
      expect(core.center).toHaveBeenCalledTimes(1);
      expect(lastCall(core.center)).toEqual([]);
    });

    it("shows a drawing that would fall far under zoom 1 at zoom 1, centred on the selected node", () => {
      made.fitZoom = 0.3;
      const { core } = framed({ selectedId: "B" });

      expect(core.zoom()).toBe(1);
      expect((lastCall(core.center)[0] as { ids: string[] }).ids).toEqual(["B"]);
      expect(core.panBy).not.toHaveBeenCalled();
    });

    it("centres on the first node when nothing in the drawing is selected", () => {
      made.fitZoom = 0.3;
      const { core } = framed({ selectedId: "Z" });

      expect((lastCall(core.center)[0] as { ids: string[] }).ids).toEqual(["A"]);
    });

    it("moves the node to the top of a drawing that runs downwards", () => {
      made.fitZoom = 0.3;
      made.nodeTop = 100;
      const { core } = framed({ selectedId: "B", direction: "DOWN" });

      // The node's top edge is at 100, and it is to stand 12 under the top of the viewport.
      expect(core.panBy).toHaveBeenCalledTimes(1);
      expect(lastCall(core.panBy)).toEqual([{ x: 0, y: 12 - 100 }]);
    });

    it("frames nothing for a selection made while the layout runs, until it has stopped", () => {
      made.fitZoom = 0.3;
      // The layout is in flight: its stop has not been delivered.
      const view = render(drawing({ readable: true }));
      const core = made.cores[0];

      view.rerender(drawing({ selectedId: "B", readable: true }));

      expect(core.fit).not.toHaveBeenCalled();
      expect(core.center).not.toHaveBeenCalled();

      made.layouts[0].stop();
      advance(0);

      expect(core.fit).toHaveBeenCalledTimes(1);
      expect((lastCall(core.center)[0] as { ids: string[] }).ids).toEqual(["B"]);
    });

    it("frames a selection made while no layout runs at once", () => {
      made.fitZoom = 0.3;
      const { core, view } = framed({ selectedId: null });
      core.center.mockClear();

      view.rerender(drawing({ selectedId: "C", readable: true }));

      expect((lastCall(core.center)[0] as { ids: string[] }).ids).toEqual(["C"]);
    });
  });

  describe("the view of a drawing at its own size", () => {
    beforeEach(() => {
      vi.stubGlobal(
        "ResizeObserver",
        class {
          constructor(callback: () => void) {
            made.observers.push(callback);
          }
          observe(): void {}
          disconnect(): void {}
          unobserve(): void {}
        },
      );
    });

    function resize(): void {
      act(() => made.observers[0]());
    }

    it("is fitted again by a resize only while the view is the one the last fit left", () => {
      render(drawing({}));
      const core = made.cores[0];

      // Nothing has been shown whole yet, so there is no view to keep.
      resize();
      expect(core.resize).toHaveBeenCalledTimes(1);
      expect(core.fit).not.toHaveBeenCalled();

      made.layouts[0].stop();
      advance(0);

      // The layout fitted the drawing itself; the view it left is still the one on screen.
      resize();
      expect(core.fit).toHaveBeenCalledTimes(1);
      expect(lastCall(core.fit)).toEqual([undefined, 24]);

      // The fit is the view now on screen, so the next resize fits again.
      resize();
      expect(core.fit).toHaveBeenCalledTimes(2);

      // The reader zooms: the view is theirs, and a resize leaves it.
      core.zoom(3);
      resize();
      expect(core.resize).toHaveBeenCalledTimes(4);
      expect(core.fit).toHaveBeenCalledTimes(2);
      expect(core.zoom()).toBe(3);
    });

    it("fits nothing at a resize while a layout runs", () => {
      const { rerender } = render(drawing({}));
      const core = made.cores[0];
      made.layouts[0].stop();
      advance(0);

      // Other elements are being laid out: their nodes have yet to be placed.
      rerender(
        <GraphCanvas
          elements={[...THREE, { group: "nodes", data: { id: "D", label: "D", kind: "PARAGRAPH" } }]}
          selectedId={null}
          theme="light"
          onSelectNode={vi.fn()}
          onLayoutRunning={vi.fn()}
        />,
      );
      expect(made.layouts).toHaveLength(2);
      resize();

      expect(core.fit).not.toHaveBeenCalled();
    });

    it("frames a readable drawing again at a resize only while the view is the one the last framing left", () => {
      made.fitZoom = 0.3;
      render(drawing({ selectedId: "B", readable: true }));
      const core = made.cores[0];
      made.layouts[0].stop();
      advance(0);
      expect(core.fit).toHaveBeenCalledTimes(1);

      resize();
      expect(core.fit).toHaveBeenCalledTimes(2);

      // The reader moves the drawing: the view is theirs, and a resize leaves it.
      core.panBy({ x: 10, y: 0 });
      resize();
      expect(core.fit).toHaveBeenCalledTimes(2);
    });

    it("leaves a view the reader has moved, and keeps leaving it at each resize", () => {
      render(drawing({}));
      const core = made.cores[0];
      made.layouts[0].stop();
      advance(0);

      core.panBy({ x: 10, y: 0 });
      resize();
      resize();

      expect(core.fit).not.toHaveBeenCalled();
    });
  });
});
