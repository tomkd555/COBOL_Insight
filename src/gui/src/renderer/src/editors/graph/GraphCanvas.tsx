import { useEffect, useImperativeHandle, useRef, type ReactElement, type Ref } from "react";
import type cytoscape from "cytoscape";
import { text } from "../../i18n/text";
import { graphLibrary } from "../../vendor/graphLibrary";
import {
  SELECTED_NODE_CLASS,
  graphCoreOptions,
  graphLayoutOptions,
  graphStylesheet,
  type GraphDirection,
  type GraphElement,
} from "../../model/graphLayout";
import type { ThemeName } from "../../vendor/monarch";

/**
 * How long a layout may take before the canvas stops waiting for it. The largest graph the layout is
 * measured on (500 nodes, model/graphLayout.perf.test.ts) takes about two seconds.
 */
const LAYOUT_TIMEOUT_MS = 30_000;

/**
 * The smallest zoom a readable drawing is shown whole at. A label can be Japanese, which is read at
 * the labels' own size (graphLayout's stylesheet) and no smaller, so the drawing is never shrunk.
 */
const READABLE_MIN_ZOOM = 1;

/** How far under the top edge a readable drawing that runs downwards puts the node it frames. */
const READABLE_TOP_INSET = 12;

/** What the toolbar can ask of the drawing. */
export interface GraphCanvasHandle {
  /** Multiplies the zoom about the centre of the viewport. */
  zoomBy(factor: number): void;
  /** Fits every element into the viewport. */
  fit(): void;
}

export interface GraphCanvasProps {
  /** The elements to draw. The layout is recomputed whenever they change. */
  elements: readonly GraphElement[];
  /** The selected node id. Highlighting swaps a class and does not relayout. */
  selectedId: string | null;
  /** The palette to draw with. Changing it restyles in place and does not relayout. */
  theme: ThemeName;
  onSelectNode: (id: string) => void;
  /** Reports whether a layout pass is in flight, so the editor can say so. */
  onLayoutRunning: (running: boolean) => void;
  /** Which way the layers run. The call graph runs left to right; a narrow pane runs downwards. */
  direction?: GraphDirection;
  /** What the drawing is called to a screen reader. The call graph's own name when left out. */
  label?: string;
  /**
   * Shows the whole drawing where it fits with its labels still readable; otherwise keeps the labels
   * at their own size and brings the selected node to the middle, or to the top of a drawing that
   * runs downwards. The layout leaves the view alone and this component frames it. For a small pane.
   */
  readable?: boolean;
  ref?: Ref<GraphCanvasHandle>;
}

/**
 * The cytoscape drawing surface. What to draw, how it looks and how it is laid out are all decided
 * by the pure model; this component owns only the core's life cycle, the element swap, the highlight
 * and the selection callback.
 *
 * A node on a canvas cannot be reached with the keyboard, which is why the execution-order tree
 * beside it carries the same selection and is the keyboard route into the graph.
 */
export function GraphCanvas({
  elements,
  selectedId,
  theme,
  onSelectNode,
  onLayoutRunning,
  direction = "RIGHT",
  label = text.graph.canvas,
  readable = false,
  ref,
}: GraphCanvasProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const coreRef = useRef<cytoscape.Core | null>(null);
  const selectRef = useRef(onSelectNode);
  selectRef.current = onSelectNode;
  const layoutRef = useRef(onLayoutRunning);
  layoutRef.current = onLayoutRunning;
  const themeRef = useRef(theme);
  themeRef.current = theme;
  const selectedRef = useRef(selectedId);
  selectedRef.current = selectedId;
  const readableRef = useRef(readable);
  readableRef.current = readable;
  const directionRef = useRef(direction);
  directionRef.current = direction;
  /**
   * What waits for the layout in flight. The layout is computed off the main thread and places the
   * nodes when it comes back; elements removed in between, or a core destroyed in between, are no
   * longer there for it to place. So the next set of elements, and the destruction of the core,
   * wait until it has stopped.
   */
  const pendingRef = useRef<{ running: boolean; next: (() => void) | null; destroy: (() => void) | null }>({
    running: false,
    next: null,
    destroy: null,
  });
  /**
   * The view the drawing was last shown whole in, or, for a readable drawing, last framed in. While
   * the view is still that one, a change of the container's size fits or frames the drawing again; a
   * view the user has zoomed or moved is theirs and stays.
   */
  const wholeRef = useRef<Viewport | null>(null);
  /**
   * What the drawing was last asked to show: its elements, direction and mode. Elements read again
   * unchanged (a save that left the graph as it was) are not laid out again, so the reader's zoom and
   * pan stay.
   */
  const shownRef = useRef("");

  useImperativeHandle(ref, () => ({
    zoomBy: (factor: number): void => {
      const core = coreRef.current;
      if (core !== null) {
        core.zoom({ level: core.zoom() * factor, renderedPosition: centreOf(core) });
      }
    },
    fit: (): void => {
      const core = coreRef.current;
      if (core !== null) {
        core.fit(undefined, 24);
        wholeRef.current = viewportOf(core);
      }
    },
  }));

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    // Each core draws into an element of its own and has its own pending state: destroying a core
    // empties the element it was given, and one torn down late must leave the next one's drawing.
    const host = document.createElement("div");
    host.className = "ci-graph__host";
    container.append(host);
    const core = graphLibrary()(graphCoreOptions(host, themeRef.current));
    const pending: typeof pendingRef.current = { running: false, next: null, destroy: null };
    pendingRef.current = pending;
    // A new core starts empty, whatever the one before it showed.
    shownRef.current = "";
    core.on("tap", "node", (event: cytoscape.EventObjectNode) => {
      selectRef.current(event.target.id());
    });
    coreRef.current = core;
    const dispose = (): void => {
      core.destroy();
      host.remove();
    };
    // The canvas keeps the size it was created with until it is told the container has changed.
    // A drawing still in the view it was last fitted or framed in is fitted or framed again for the
    // new size, so a pane opening beside it cuts nothing off. While a layout runs nothing is fitted:
    // the nodes are yet to be placed, and the layout frames the drawing when it stops.
    const resized =
      typeof ResizeObserver === "undefined"
        ? null
        : new ResizeObserver(() => {
            core.resize();
            if (pending.running || !sameViewport(wholeRef.current, viewportOf(core))) {
              return;
            }
            if (readableRef.current) {
              frameReadable(core, selectedRef.current, directionRef.current);
            } else {
              core.fit(undefined, 24);
            }
            wholeRef.current = viewportOf(core);
          });
    resized?.observe(container);
    return () => {
      resized?.disconnect();
      coreRef.current = null;
      pending.next = null;
      if (pending.running) {
        host.style.visibility = "hidden";
        pending.destroy = dispose;
      } else {
        dispose();
      }
    };
  }, []);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    const shown = JSON.stringify([elements, direction, readable]);
    if (shown === shownRef.current) {
      return;
    }
    shownRef.current = shown;
    const pending = pendingRef.current;
    const draw = (): void => {
      core.batch(() => {
        core.elements().remove();
        core.add([...elements] as cytoscape.ElementDefinition[]);
      });
      if (selectedRef.current !== null) {
        core.getElementById(selectedRef.current).addClass(SELECTED_NODE_CLASS);
      }
      const layout = core.layout(
        graphLayoutOptions(direction, readable) as unknown as cytoscape.LayoutOptions,
      );
      pending.running = true;
      // The layout library announces its start only when the positions arrive, so the start is
      // reported here, where the computation begins.
      if (pendingRef.current === pending) {
        layoutRef.current(true);
      }
      let settled = false;
      /** What follows a layout, whether it stopped, failed to start, or never answered. */
      const settle = (laidOut: boolean): void => {
        if (settled) {
          return;
        }
        settled = true;
        window.clearTimeout(watchdog);
        pending.running = false;
        if (pendingRef.current === pending) {
          layoutRef.current(false);
        }
        if (pending.destroy !== null) {
          pending.destroy();
        } else if (pending.next !== null) {
          const next = pending.next;
          pending.next = null;
          next();
        } else if (laidOut && readable) {
          frameReadable(core, selectedRef.current, direction);
          wholeRef.current = viewportOf(core);
        } else if (laidOut) {
          // The layout fits the drawing itself; this is the view it left.
          wholeRef.current = viewportOf(core);
        }
      };
      // A computation that is rejected never reports a stop; nothing may wait for it for good. The
      // layout is told to stop when the watchdog fires, and whatever it reports later is ignored.
      const watchdog = window.setTimeout(() => {
        try {
          layout.stop();
        } catch (error) {
          console.error(error);
        }
        settle(false);
      }, LAYOUT_TIMEOUT_MS);
      layout.one("layoutstop", () => {
        // Once the event has been delivered, never inside it: what follows removes what it reports on.
        window.setTimeout(() => settle(true), 0);
      });
      try {
        layout.run();
      } catch (error) {
        // A layout that failed to start will never stop. The drawing stays as it was added, and
        // the failure is left in the console for whoever looks into it.
        console.error(error);
        settle(false);
      }
    };
    if (pending.running) {
      pending.next = draw;
    } else {
      draw();
    }
  }, [elements, direction, readable]);

  useEffect(() => {
    coreRef.current?.style(graphStylesheet(theme));
  }, [theme]);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    core.nodes().removeClass(SELECTED_NODE_CLASS);
    if (selectedId !== null) {
      const selected = core.getElementById(selectedId);
      selected.addClass(SELECTED_NODE_CLASS);
      // While a layout runs the nodes have yet to be placed; the layout frames the drawing when it stops.
      if (readable && selected.nonempty() && !pendingRef.current.running) {
        frameReadable(core, selectedId, directionRef.current);
        wholeRef.current = viewportOf(core);
      }
    }
  }, [selectedId, readable]);

  return (
    <div
      className="ci-graph__canvas"
      ref={containerRef}
      data-testid="graph-canvas"
      role="application"
      aria-label={label}
    />
  );
}

/**
 * Frames a readable drawing: the whole of it where that keeps the labels readable, and otherwise the
 * selected node, or the first one, at the labels' own size. In a drawing that runs downwards the node
 * is put at the top, so the room under it shows where its flows lead.
 */
function frameReadable(
  core: cytoscape.Core,
  selectedId: string | null,
  direction: GraphDirection,
): void {
  if (core.nodes().empty()) {
    return;
  }
  core.fit(undefined, 12);
  if (core.zoom() >= READABLE_MIN_ZOOM) {
    if (core.zoom() > 1) {
      core.zoom(1);
      core.center();
    }
    return;
  }
  core.zoom(1);
  const selected = selectedId === null ? null : core.getElementById(selectedId);
  const node = selected !== null && selected.nonempty() ? selected : core.nodes().first();
  core.center(node);
  if (direction === "DOWN") {
    core.panBy({ x: 0, y: READABLE_TOP_INSET - node.renderedBoundingBox().y1 });
  }
}

interface Viewport {
  readonly zoom: number;
  readonly x: number;
  readonly y: number;
}

function viewportOf(core: cytoscape.Core): Viewport {
  const pan = core.pan();
  return { zoom: core.zoom(), x: pan.x, y: pan.y };
}

function sameViewport(one: Viewport | null, other: Viewport): boolean {
  return one !== null && one.zoom === other.zoom && one.x === other.x && one.y === other.y;
}

/** The centre of the viewport, so zooming keeps what is in view in view. */
function centreOf(core: cytoscape.Core): cytoscape.Position {
  return { x: core.width() / 2, y: core.height() / 2 };
}
