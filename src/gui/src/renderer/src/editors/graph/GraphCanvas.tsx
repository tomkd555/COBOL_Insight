import { useEffect, useImperativeHandle, useRef, type ReactElement, type Ref } from "react";
import type cytoscape from "cytoscape";
import { text } from "../../text";
import { graphLibrary } from "../../vendor/graphLibrary";
import {
  SELECTED_NODE_CLASS,
  graphCoreOptions,
  graphLayoutOptions,
  type GraphElement,
} from "../../model/graphLayout";

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
  onSelectNode: (id: string) => void;
  /** Reports whether a layout pass is in flight, so the editor can say so. */
  onLayoutRunning: (running: boolean) => void;
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
  onSelectNode,
  onLayoutRunning,
  ref,
}: GraphCanvasProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const coreRef = useRef<cytoscape.Core | null>(null);
  const selectRef = useRef(onSelectNode);
  selectRef.current = onSelectNode;
  const layoutRef = useRef(onLayoutRunning);
  layoutRef.current = onLayoutRunning;

  useImperativeHandle(ref, () => ({
    zoomBy: (factor: number): void => {
      const core = coreRef.current;
      if (core !== null) {
        core.zoom({ level: core.zoom() * factor, renderedPosition: centreOf(core) });
      }
    },
    fit: (): void => {
      coreRef.current?.fit(undefined, 24);
    },
  }));

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const core = graphLibrary()(graphCoreOptions(container));
    core.on("tap", "node", (event: cytoscape.EventObjectNode) => {
      selectRef.current(event.target.id());
    });
    core.on("layoutstart", () => layoutRef.current(true));
    core.on("layoutstop", () => layoutRef.current(false));
    coreRef.current = core;
    return () => {
      core.destroy();
      coreRef.current = null;
    };
  }, []);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    core.batch(() => {
      core.elements().remove();
      core.add([...elements] as cytoscape.ElementDefinition[]);
    });
    core.layout(graphLayoutOptions() as unknown as cytoscape.LayoutOptions).run();
  }, [elements]);

  useEffect(() => {
    const core = coreRef.current;
    if (core === null) {
      return;
    }
    core.nodes().removeClass(SELECTED_NODE_CLASS);
    if (selectedId !== null) {
      core.getElementById(selectedId).addClass(SELECTED_NODE_CLASS);
    }
  }, [selectedId]);

  return (
    <div
      className="ci-graph__canvas"
      ref={containerRef}
      data-testid="graph-canvas"
      role="application"
      aria-label={text.graph.canvas}
    />
  );
}

/** The centre of the viewport, so zooming keeps what is in view in view. */
function centreOf(core: cytoscape.Core): cytoscape.Position {
  return { x: core.width() / 2, y: core.height() / 2 };
}
