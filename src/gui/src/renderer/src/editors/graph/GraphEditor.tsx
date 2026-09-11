import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type { GraphData } from "../../../../shared/ipc";
import { api, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { artifactItems, useProject } from "../../state/projectStore";
import { sourceTab, useWorkbenchDispatch } from "../../state/workbenchStore";
import {
  DEPTH_LIMITS,
  INITIAL_GRAPH_FILTER,
  indexGraph,
  nodeKindCounts,
  toggleKind,
  visibleNodeIds,
  withDepth,
  type GraphFilter,
} from "../../model/graphFilter";
import {
  buildGraphElements,
  NODE_KINDS,
  isGraphNodeKind,
  nodeKindStyles,
} from "../../model/graphLayout";
import { useResolvedTheme } from "../../state/useTheme";
import { nodeDetail } from "../../model/graphDetail";
import { buildTrace, flattenTrace, initialExpanded, type TraceNode } from "../../model/traceTree";
import { GraphCanvas, type GraphCanvasHandle } from "./GraphCanvas";
import { GraphDetailPane, NodeSwatch } from "./GraphDetailPane";
import { TraceTree } from "./TraceTree";

/** How the call graph stands. "error" is kept apart from an empty graph on purpose. */
type GraphState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly data: GraphData }
  | { readonly status: "error"; readonly message: string };

export interface GraphEditorProps {
  /** The label of the node to centre on, or null to open on the graph's roots. */
  focusLabel: string | null;
}

const EMPTY_GRAPH: GraphData = { nodes: [], edges: [], paragraphs: [], paragraphEdges: [] };

/**
 * The call-graph editor: the execution-order tree on the left, the drawing in the middle and the
 * selected node's calls on the right.
 *
 * The data is what scan already wrote into the project file; this view launches no subcommand of
 * its own. Selection is shared between the tree and the canvas in both directions, so the keyboard
 * reaches everything the mouse does.
 */
export function GraphEditor({ focusLabel }: GraphEditorProps): ReactElement {
  const theme = useResolvedTheme();
  const project = useProject();
  const dispatch = useWorkbenchDispatch();
  const canvasRef = useRef<GraphCanvasHandle>(null);

  const [state, setState] = useState<GraphState>({ status: "idle" });
  const [filter, setFilter] = useState<GraphFilter>(INITIAL_GRAPH_FILTER);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [layoutRunning, setLayoutRunning] = useState(false);
  // Typing moves this at once; the filter (and the walk it triggers) follows 150ms later, so a fast
  // typist does not re-walk the graph on every keystroke.
  const [searchInput, setSearchInput] = useState(INITIAL_GRAPH_FILTER.search);

  const dbPath = project.dbPath;
  const inputDir = project.inputDir;
  // The project file holds every folder ever scanned, so the graph is read for this one alone; and
  // it is read again after each run, which is when the folder's own graph has changed.
  const runId = project.runId;
  useEffect(() => {
    if (dbPath === null || inputDir === null) {
      setState({ status: "idle" });
      return;
    }
    let cancelled = false;
    setState({ status: "loading" });
    api()
      .readGraph(dbPath, inputDir)
      .then((data) => {
        if (!cancelled) {
          setState({ status: "ready", data });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState({ status: "error", message: errorMessage(error) });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [dbPath, inputDir, runId]);

  const data = state.status === "ready" ? state.data : EMPTY_GRAPH;
  const inventory = artifactItems(project.inventory);

  // The tab can be opened on a node named elsewhere — the program of a problems row, say.
  useEffect(() => {
    if (focusLabel === null || state.status !== "ready") {
      return;
    }
    const target = focusLabel.toUpperCase();
    const node = data.nodes.find(
      (candidate) => isGraphNodeKind(candidate.type) && candidate.label.toUpperCase() === target,
    );
    if (node !== undefined) {
      const id = String(node.id);
      setFilter((current) => ({ ...current, focusId: id }));
      setSelectedNodeId(id);
    }
  }, [focusLabel, data, state.status]);

  useEffect(() => {
    const id = window.setTimeout(() => {
      setFilter((current) => (current.search === searchInput ? current : { ...current, search: searchInput }));
    }, 150);
    return () => window.clearTimeout(id);
  }, [searchInput]);

  const roots = useMemo(() => buildTrace(data, inventory), [data, inventory]);
  useEffect(() => {
    setExpanded(initialExpanded(roots));
  }, [roots]);

  const rows = useMemo(() => flattenTrace(roots, expanded), [roots, expanded]);
  const graphIndex = useMemo(() => indexGraph(data), [data]);
  const visibleIds = useMemo(() => visibleNodeIds(graphIndex, filter), [graphIndex, filter]);
  const elements = useMemo(() => buildGraphElements(data, visibleIds), [data, visibleIds]);
  const counts = useMemo(() => nodeKindCounts(graphIndex, filter), [graphIndex, filter]);
  const kindStyles = useMemo(() => nodeKindStyles(theme), [theme]);
  const detail = useMemo(
    () => (selectedNodeId === null ? null : nodeDetail(data, selectedNodeId, inventory)),
    [data, selectedNodeId, inventory],
  );
  const drawableCount = useMemo(
    () => data.nodes.filter((node) => isGraphNodeKind(node.type)).length,
    [data],
  );

  /** The focused node's label, so the "clear focus" button can name what it would clear. */
  const focusedLabel = useMemo(
    () => data.nodes.find((node) => String(node.id) === filter.focusId)?.label ?? null,
    [data, filter.focusId],
  );
  const [lastFocusLabel, setLastFocusLabel] = useState<string | null>(null);
  useEffect(() => {
    if (focusedLabel !== null) {
      setLastFocusLabel(focusedLabel);
    }
  }, [focusedLabel]);
  const clearFocusLabel = focusedLabel ?? lastFocusLabel;

  const openAsset = (path: string, line: number | null): void => {
    dispatch({ type: "OPEN_TAB", tab: sourceTab(path, line) });
  };

  /** A row of the tree was chosen: centre the drawing on the node it stands for. */
  const selectTraceNode = (node: TraceNode): void => {
    setSelectedRowId(node.id);
    if (node.graphId !== null) {
      setSelectedNodeId(node.graphId);
      setFilter((current) => ({ ...current, focusId: node.graphId }));
    }
  };

  /** A node on the canvas was tapped: move the tree's selection to the row that stands for it. */
  const selectGraphNode = (id: string): void => {
    setSelectedNodeId(id);
    setSelectedRowId(rows.find((row) => row.node.graphId === id)?.node.id ?? null);
  };

  if (state.status === "idle") {
    return (
      <p className="ci-graph__state">
        {project.inputDir === null ? text.empty.noFolder : text.empty.notAnalysed}
      </p>
    );
  }
  if (state.status === "loading") {
    return <p className="ci-graph__state">{text.graph.loading}</p>;
  }
  if (state.status === "error") {
    return (
      <p className="ci-graph__state ci-graph__state--error" role="alert">
        {text.graph.error}
        <span className="ci-graph__reason">{state.message}</span>
      </p>
    );
  }
  if (drawableCount === 0) {
    return <p className="ci-graph__state">{text.graph.noNodes}</p>;
  }

  return (
    <div className="ci-graph" data-testid="graph-editor">
      <div className="ci-graph__toolbar">
        <span className="ci-graph__group">
          <input
            type="search"
            className="ci-input"
            placeholder={text.graph.search}
            aria-label={text.graph.search}
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            data-testid="graph-search"
          />
        </span>
        <span className="ci-graph__group">
          <label className="ci-graph__depth">
            {text.graph.depth}
            <input
              type="range"
              min={DEPTH_LIMITS.min}
              max={DEPTH_LIMITS.max}
              step={1}
              value={filter.depth}
              aria-label={text.graph.depthLabel}
              onChange={(event) => setFilter(withDepth(filter, Number(event.target.value)))}
              data-testid="graph-depth"
            />
            <span>
              {filter.depth}/{DEPTH_LIMITS.max}
            </span>
          </label>
          <button
            type="button"
            className="ci-button"
            disabled={filter.focusId === null}
            onClick={() => {
              setFilter({ ...filter, focusId: null });
              setSelectedNodeId(null);
              setSelectedRowId(null);
            }}
            data-testid="graph-recenter"
          >
            {clearFocusLabel === null
              ? text.graph.clearFocusNone
              : text.graph.clearFocus(clearFocusLabel)}
          </button>
        </span>
        <span className="ci-graph__group">
          <button
            type="button"
            className="ci-button ci-button--quiet"
            aria-label={text.graph.zoomOut}
            title={text.graph.zoomOut}
            onClick={() => canvasRef.current?.zoomBy(1 / 1.2)}
            data-testid="graph-zoom-out"
          >
            <span className="codicon codicon-zoom-out" aria-hidden="true" />
          </button>
          <button
            type="button"
            className="ci-button ci-button--quiet"
            aria-label={text.graph.zoomIn}
            title={text.graph.zoomIn}
            onClick={() => canvasRef.current?.zoomBy(1.2)}
            data-testid="graph-zoom-in"
          >
            <span className="codicon codicon-zoom-in" aria-hidden="true" />
          </button>
          <button
            type="button"
            className="ci-button"
            onClick={() => canvasRef.current?.fit()}
            data-testid="graph-fit"
          >
            {text.graph.fit}
          </button>
        </span>
        <span className="ci-graph__group">
          <span className="ci-graph__count" data-testid="graph-count">
            {text.graph.nodeCount(visibleIds.size, drawableCount)}
          </span>
        </span>
      </div>

      {/* A kind the project has none of is not offered. Its swatch is the legend for the kind. */}
      <div className="ci-chips" role="group" aria-label={text.graph.kinds}>
        {NODE_KINDS.filter((kind) => counts[kind] > 0).map((kind) => {
          const style = kindStyles.find((candidate) => candidate.kind === kind);
          return (
            <button
              key={kind}
              type="button"
              className={`ci-chip${filter.kinds[kind] ? " ci-chip--on" : ""}`}
              aria-pressed={filter.kinds[kind]}
              onClick={() => setFilter(toggleKind(filter, kind))}
              data-testid={`graph-kind-${kind}`}
            >
              {style === undefined ? null : <NodeSwatch style={style} />}
              {text.graph.nodeKind[kind]}
              <span className="ci-chip__count">{counts[kind]}</span>
            </button>
          );
        })}
      </div>

      <div className="ci-graph__body">
        <TraceTree
          rows={rows}
          selectedId={selectedRowId}
          onSelect={selectTraceNode}
          onToggle={(id) => setExpanded({ ...expanded, [id]: expanded[id] !== true })}
          onOpen={openAsset}
        />
        <div className="ci-graph__stage">
          {layoutRunning ? (
            <p className="ci-graph__laying" data-testid="graph-laying">
              {text.graph.laying}
            </p>
          ) : null}
          <GraphCanvas
            ref={canvasRef}
            elements={elements}
            selectedId={selectedNodeId}
            theme={theme}
            onSelectNode={selectGraphNode}
            onLayoutRunning={setLayoutRunning}
          />
        </div>
        <GraphDetailPane detail={detail} theme={theme} onOpenAsset={openAsset} />
      </div>
    </div>
  );
}
