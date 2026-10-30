import { memo, useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type { FlowParagraph } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { outlineKey, type CobolOutline, type OutlineNode } from "../../model/cobolOutline";
import { FLOW_LINE_STYLES, edgeKindStyles } from "../../model/graphLayout";
import { buildParagraphTrace, flowParagraphOf, rowPathTo } from "../../model/paragraphTrace";
import {
  TRACE_NODE_BUDGET,
  flattenTrace,
  initialExpanded,
  type TraceRow,
} from "../../model/traceTree";
import { useProgramFlow } from "../../state/useProgramFlow";
import { useResolvedTheme } from "../../state/useTheme";
import type { ParagraphChoice } from "../../state/workbenchStore";
import { SplitHandle } from "../../ui/SplitHandle";
import { GraphCanvas } from "../graph/GraphCanvas";
import { TraceTree } from "../graph/TraceTree";
import { ParagraphPeek } from "./ParagraphPeek";

export interface ParagraphPaneProps {
  /** The source tab the pane belongs to. */
  tabId: string;
  /** The asset's path, relative to the asset folder. */
  path: string;
  /** The outline of the text as it now stands in the editor. */
  outline: CobolOutline;
  choice: ParagraphChoice;
  onChoose: (next: { root?: string | null; shown?: string | null }) => void;
  /** Moves the main editor to a line. */
  onReveal: (line: number) => void;
  /** Opens another asset: the program a call names. */
  onOpenAsset: (path: string) => void;
  /**
   * Changes with every paragraph chosen in the code, the same one chosen again included, so the
   * list of paragraphs moves its Tab stop to it each time.
   */
  selectionKey?: number;
}

type PaneView = "paragraphs" | "trace";

const VIEWS: readonly { view: PaneView; label: string }[] = [
  { view: "paragraphs", label: text.sourceView.paragraphs },
  { view: "trace", label: text.sourceView.trace },
];

/** The dash pattern of each flow in the legend, matching the canvas's line styles. */
const LEGEND_DASH: Readonly<Record<string, string | undefined>> = {
  solid: undefined,
  dashed: "6 3",
  dotted: "1.5 3",
};

const NO_ROWS: readonly TraceRow[] = [];

/** The least height at which a region of the pane still shows a row or two of what it holds. */
const REGION_MIN = 72;

/** The height of one boundary between two regions (styles/app-tokens.css, --ci-split-size). */
const BOUNDARY = 1;

interface RegionHeights {
  readonly peek: number;
  readonly graph: number;
}

/**
 * The heights the user gave the two lower regions, kept while the application runs so every source
 * tab opens with them. Null until a boundary has been moved: the three regions then share the pane.
 */
let chosenHeights: RegionHeights | null = null;

/**
 * The heights the two lower regions are drawn at in a pane of the given height: the chosen ones where
 * the pane has room for them, each region kept to the least height, and an even share where nothing
 * was chosen. A pane too short for three regions of the least height shares what it has evenly. The
 * chosen heights stay as they were asked for, so a pane that grows again gets them back.
 */
export function regionHeights(
  paneHeight: number,
  chosen: RegionHeights | null,
  drawn = true,
): RegionHeights & { readonly least: number } {
  const room = Math.max(0, paneHeight - 2 * BOUNDARY);
  const share = Math.floor(room / 3);
  const least = Math.min(REGION_MIN, share);
  if (chosen === null) {
    return { peek: share, graph: share, least };
  }
  const within = (value: number, most: number): number => Math.max(least, Math.min(value, most));
  const graph = within(chosen.graph, room - 2 * least);
  // Until there is a trace to draw, the drawing's region is absent and the text can have its room.
  return { peek: within(chosen.peek, room - least - (drawn ? graph : 0)), graph, least };
}

function ignore(): void {
  /* the outline has nothing to open or to fold */
}

/** The outline key (cobolOutline) of a paragraph as the engine recorded it. */
function outlineKeyOf(paragraph: FlowParagraph): string {
  return outlineKey(
    paragraph.name.toUpperCase(),
    paragraph.kind === "SECTION" ? null : (paragraph.sectionName?.toUpperCase() ?? null),
  );
}

/** The name the engine knows an outline node by. Statements ahead of any paragraph go by the division's. */
function engineNameOf(node: OutlineNode): string {
  return node.kind === "division" ? node.key : node.name;
}

/**
 * The pane beside the code: the paragraphs of the program and the trace from one of them, the text
 * of the paragraph in question, and the graph of the trace.
 *
 * The outline comes from the text in the editor and follows every edit. The trace comes from the
 * last scan, so it follows a save; until the scan has read the save, the pane says so.
 */
export const ParagraphPane = memo(function ParagraphPane({
  tabId,
  path,
  outline,
  choice,
  onChoose,
  onReveal,
  onOpenAsset,
  selectionKey = 0,
}: ParagraphPaneProps): ReactElement {
  const theme = useResolvedTheme();
  const [view, setView] = useState<PaneView>("paragraphs");
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [selectedRowId, setSelectedRowId] = useState<string | null>(null);
  /** Counts the nodes pressed in the drawing: a selection the two lists did not make themselves. */
  const [graphPresses, setGraphPresses] = useState(0);

  const paneRef = useRef<HTMLElement | null>(null);
  const [paneHeight, setPaneHeight] = useState(0);
  const [heights, setHeights] = useState(chosenHeights);
  useEffect(() => {
    const pane = paneRef.current;
    // jsdom has no ResizeObserver; with no height measured the regions share the pane through CSS.
    if (pane === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(() => setPaneHeight(pane.clientHeight));
    observer.observe(pane);
    return () => observer.disconnect();
  }, []);
  const resize = (next: RegionHeights): void => {
    chosenHeights = next;
    setHeights(next);
  };
  /** The height a lower region is given once the pane has been measured. */
  const sized = (height: number): { flex: string } | undefined =>
    paneHeight > 0 ? { flex: `0 0 ${height}px` } : undefined;

  const flowView = useProgramFlow(path);
  const flow = flowView.state.status === "ready" ? flowView.state.flow : null;

  // A paragraph chosen earlier whose header has since left the text gives way to the default.
  const rootChosen = choice.root !== null && outline.byKey.has(choice.root);
  const rootKey = rootChosen ? choice.root : outline.defaultRoot;
  const rootNode = rootKey === null ? undefined : outline.byKey.get(rootKey);
  const shownKey = choice.shown ?? rootKey;
  const shownNode = shownKey === null ? undefined : outline.byKey.get(shownKey);

  const outlineRows = useMemo(
    (): TraceRow[] =>
      outline.nodes
        // Where two headers share a key, the first is the one a reference resolves to.
        .filter((node) => outline.byKey.get(node.key) === node)
        .map((node) => ({
          node: {
            id: node.key,
            kind: node.kind,
            label: engineNameOf(node),
            line: node.startLine,
            path: null,
            graphId: null,
            children: [],
            cyclic: false,
          },
          depth: node.depth,
          hasChildren: false,
          expanded: false,
        })),
    [outline],
  );

  const rootName = rootNode === undefined ? null : engineNameOf(rootNode);
  const rootSection = rootNode?.section ?? null;
  const rootLine = rootNode?.startLine ?? null;
  /** The engine's paragraph the trace starts from; null where the last scan holds none for it. */
  const start = useMemo(() => {
    if (flow === null || rootName === null) {
      return null;
    }
    const named = flowParagraphOf(flow, rootName, rootSection);
    if (named !== null || rootChosen || rootLine === null) {
      return named;
    }
    // Where the program opens with statements ahead of its first paragraph, the trace starts at the
    // first paragraph from that line on.
    return flow.paragraphs.find((each) => each.startLine >= rootLine) ?? null;
  }, [flow, rootName, rootSection, rootChosen, rootLine]);
  /** A paragraph chosen in the text that the last scan has yet to read: there is no flow to show. */
  const rootMissing = flow !== null && rootChosen && rootName !== null && start === null;
  const trace = useMemo(() => {
    if (flow === null || rootMissing) {
      return null;
    }
    return buildParagraphTrace(flow, start?.id ?? null);
  }, [flow, start, rootMissing]);

  useEffect(() => {
    setExpanded(trace === null ? {} : initialExpanded(trace.roots));
    setSelectedRowId(null);
  }, [trace]);

  const traceRows = useMemo(
    () => (trace === null ? NO_ROWS : flattenTrace(trace.roots, expanded)),
    [trace, expanded],
  );

  /** The graph node of the paragraph on show, so the drawing marks what the text beside it is. */
  const shownGraphId = useMemo(() => {
    if (flow === null || shownNode === undefined) {
      return null;
    }
    const paragraph = flowParagraphOf(flow, engineNameOf(shownNode), shownNode.section);
    return paragraph === null ? null : String(paragraph.id);
  }, [flow, shownNode]);

  /** Shows the paragraph a row or a graph node stands for, where the text still holds it. */
  const showGraphNode = (graphId: string | null): void => {
    const paragraph = flow?.paragraphs.find((each) => String(each.id) === graphId);
    if (paragraph !== undefined && outline.byKey.has(outlineKeyOf(paragraph))) {
      onChoose({ shown: outlineKeyOf(paragraph) });
    }
  };

  const selectGraphNode = (graphId: string): void => {
    setGraphPresses((count) => count + 1);
    const rowPath = trace === null ? [] : rowPathTo(trace.roots, graphId);
    if (rowPath.length > 0) {
      setExpanded((current) => ({
        ...current,
        ...Object.fromEntries(rowPath.slice(0, -1).map((id) => [id, true])),
      }));
      setSelectedRowId(rowPath[rowPath.length - 1]);
    }
    showGraphNode(graphId);
  };

  const drawable = trace !== null && trace.roots.length > 0;
  const { peek: peekHeight, graph: graphHeight, least } = regionHeights(paneHeight, heights, drawable);
  const listHeight = Math.max(0, paneHeight - 2 * BOUNDARY - peekHeight - graphHeight);
  const callColor = edgeKindStyles(theme).find((style) => style.kind === "CALL")?.color;

  return (
    <aside
      ref={paneRef}
      className="ci-source__side"
      aria-label={text.sourceView.pane}
      data-testid="paragraph-pane"
    >
      <section className="ci-pane__region">
        <div
          className="ci-panel__tabs"
          role="tablist"
          aria-label={text.sourceView.pane}
          onKeyDown={(event) => {
            if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
              event.preventDefault();
              const next = view === "paragraphs" ? "trace" : "paragraphs";
              setView(next);
              // The focus goes with the selection: the tab left behind is out of the Tab order.
              event.currentTarget.querySelector<HTMLElement>(`#panetab-${next}`)?.focus();
            }
          }}
        >
          {VIEWS.map((each) => {
            const selected = view === each.view;
            return (
              <button
                key={each.view}
                type="button"
                role="tab"
                id={`panetab-${each.view}`}
                aria-selected={selected}
                // Only the panel of the selected tab is in the document.
                aria-controls={selected ? `paneview-${each.view}` : undefined}
                tabIndex={selected ? 0 : -1}
                className={`ci-panel__tab${selected ? " ci-panel__tab--active" : ""}`}
                onClick={() => setView(each.view)}
                data-testid={`pane-tab-${each.view}`}
              >
                {each.label}
              </button>
            );
          })}
        </div>
        {/* The trace and the drawing both come from the last scan, so the note stands over both views. */}
        {flowView.stale === null ? null : (
          <p className="ci-pane__note" role="status">
            {flowView.stale === "reading" ? text.sourceView.flowStale : text.sourceView.flowUnread}
          </p>
        )}
        <div
          className="ci-pane__body"
          role="tabpanel"
          id={`paneview-${view}`}
          aria-labelledby={`panetab-${view}`}
        >
          {view === "paragraphs" ? (
            outlineRows.length === 0 ? (
              <p className="ci-pane__state">{text.sourceView.noParagraphs}</p>
            ) : (
              <TraceTree
                rows={outlineRows}
                selectedId={shownKey}
                selectionKey={selectionKey + graphPresses}
                label={text.sourceView.paragraphs}
                testId="outline"
                onSelect={(node) => {
                  const picked = outline.byKey.get(node.id);
                  // A section or paragraph of the procedure division is also where the trace starts.
                  onChoose(picked?.procedure === true ? { root: node.id, shown: node.id } : { shown: node.id });
                }}
                onToggle={ignore}
                onOpen={ignore}
              />
            )
          ) : (
            <>
              {flowView.state.status === "error" ? (
                <p className="ci-pane__state ci-pane__state--error" role="alert">
                  {text.sourceView.flowError}
                  <span className="ci-source__reason">{flowView.state.message}</span>
                </p>
              ) : flowView.state.status === "none" ? (
                <p className="ci-pane__state">{text.empty.notAnalysed}</p>
              ) : rootMissing ? (
                <p className="ci-pane__state">{text.sourceView.rootMissing}</p>
              ) : trace === null ? (
                <p className="ci-pane__state">{text.sourceView.flowLoading}</p>
              ) : trace.roots.length === 0 ? (
                <p className="ci-pane__state">{text.sourceView.noParagraphs}</p>
              ) : (
                <TraceTree
                  rows={traceRows}
                  selectedId={selectedRowId}
                  selectionKey={graphPresses}
                  label={text.sourceView.trace}
                  testId="paragraph-trace"
                  onSelect={(node) => {
                    setSelectedRowId(node.id);
                    showGraphNode(node.graphId);
                  }}
                  onToggle={(id) => setExpanded((current) => ({ ...current, [id]: !current[id] }))}
                  onOpen={(asset) => onOpenAsset(asset)}
                />
              )}
              {trace?.truncated === true ? (
                <p className="ci-pane__note">{text.sourceView.truncated(TRACE_NODE_BUDGET)}</p>
              ) : null}
            </>
          )}
        </div>
      </section>

      <SplitHandle
        orientation="horizontal"
        size={peekHeight}
        min={least}
        // What must stay for the list, with the drawing and the other boundary under the text.
        oppositeMin={drawable ? least + graphHeight + BOUNDARY : least}
        onSizeChange={(peek) => resize({ peek, graph: graphHeight })}
        ariaLabel={text.sourceView.peekHeight}
      />
      <section className="ci-pane__region" style={sized(peekHeight)} aria-label={text.sourceView.peek}>
        {shownNode === undefined ? null : (
          <>
            <div className="ci-pane__head">
              <span className="ci-pane__name">{engineNameOf(shownNode)}</span>
              <button
                type="button"
                className="ci-button"
                onClick={() => onReveal(shownNode.startLine)}
                data-testid="pane-reveal"
              >
                {text.sourceView.reveal}
              </button>
            </div>
            <ParagraphPeek tabId={tabId} range={{ start: shownNode.startLine, end: shownNode.endLine }} />
          </>
        )}
      </section>

      {/* The drawing's region is there once there is a trace to draw; until then the list above
          has its height, and the trace tab says what is being waited for. */}
      {trace !== null && drawable ? (
        <>
          <SplitHandle
            orientation="horizontal"
            size={graphHeight}
            min={least}
            // This boundary lies between the text and the drawing, so what one gives the other takes
            // and the list above keeps its height: the text can give all but its least height.
            oppositeMin={listHeight + BOUNDARY + least}
            onSizeChange={(graph) => resize({ peek: peekHeight + graphHeight - graph, graph })}
            ariaLabel={text.sourceView.graphHeight}
          />
          <section className="ci-pane__region" style={sized(graphHeight)}>
            {/* The call graph's legend, in its shape: closed until asked for. */}
            <details className="ci-graph__legend ci-pane__legend">
              <summary className="ci-graph__subtitle">{text.graph.legend}</summary>
              <ul className="ci-graph__legend-list" aria-label={text.sourceView.flowKinds}>
                {FLOW_LINE_STYLES.map((style) => (
                  <li key={style.kind}>
                    <FlowLine dash={LEGEND_DASH[style.lineStyle]} />
                    {text.sourceView.flowKind[style.kind as keyof typeof text.sourceView.flowKind]}
                  </li>
                ))}
                <li>
                  <FlowLine color={callColor} />
                  {text.sourceView.flowKind.CALL}
                </li>
              </ul>
            </details>
            <GraphCanvas
              elements={trace.elements}
              selectedId={shownGraphId}
              theme={theme}
              direction="DOWN"
              readable
              label={text.sourceView.flowCanvas}
              onSelectNode={selectGraphNode}
              onLayoutRunning={ignore}
            />
          </section>
        </>
      ) : null}
    </aside>
  );
});

/** A sample of one line of the graph, for the legend. */
function FlowLine({ dash, color }: { dash?: string; color?: string }): ReactElement {
  return (
    <svg className="ci-pane__line" viewBox="0 0 28 6" aria-hidden="true" style={{ color }}>
      <line x1="0" y1="3" x2="28" y2="3" stroke="currentColor" strokeWidth="1.5" strokeDasharray={dash} />
    </svg>
  );
}
