import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactElement,
} from "react";
import { EmptyState } from "../components/EmptyState";
import { RunningIndicator } from "../components/RunningIndicator";
import { SplitHandle } from "../components/SplitHandle";
import { GraphCanvas } from "../screens/graph/GraphCanvas";
import { GraphDetail } from "../screens/graph/GraphDetail";
import { GraphToolbar } from "../screens/graph/GraphToolbar";
import {
  buildGraphElements,
  graphArtifactPaths,
  graphSourcePaths,
  graphWarning,
  nodeDetail,
  nodeKindCounts,
  unanalyzableBanner,
  unanalyzableCount,
  visibleNodeIds,
} from "../screens/graph/graphModel";
import { messageOf } from "../services/analysis";
import { artifactItems, useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings } from "../state/settingsStore";
import { selectPath, toggleExpanded, toggleKind } from "./graphView";
import { TraceTree } from "./TraceTree";
import { buildTrace, flattenTrace, toCallGraphData } from "./traceModel";
import { sourceTab, useWorkbenchDispatch } from "../state/workbenchStore";
import type { CallGraphData } from "../../../shared/engine-api";

/** 読み出しは1回の実行で終わるため、段は示さない。 */
const NO_STAGES: readonly string[] = [];

/** 未取得のときに用いる空グラフ。useMemo の依存を安定させるため定数で持つ。 */
const EMPTY_GRAPH: CallGraphData = { nodes: [], edges: [] };

/** 実行順の一覧の寸法。min は一覧が役目を果たす最小、oppositeMin は図へ必ず残す最小である。 */
const TRACE_LIMITS = { initial: 320, min: 240, oppositeMin: 480 } as const;

/** ノード情報と凡例のペインの寸法。 */
const DETAIL_LIMITS = { initial: 300, min: 240, oppositeMin: 420 } as const;

/**
 * 呼出関係のタブ。左は実行順の一覧(ジョブ → ステップ → プログラム → 段落)、右は Cytoscape の図で
 * ある。どちらも走査済みプロジェクトファイルから読んだ同じグラフを見ており、一覧で選んだ経路は
 * 図の側でも展開して強調する。
 *
 * グラフは scan が書いた表をそのまま読む。call-graph サブコマンドを起こすのは SVG・PNG の
 * 書き出しだけであり、図を出すために engine を起動し直さない。
 */
export function GraphTab(): ReactElement {
  const project = useProject();
  const dispatch = useProjectDispatch();
  const workbenchDispatch = useWorkbenchDispatch();
  const settings = useSettings();
  const { inputDir, dbPath, graph, graphView } = project;

  const [exporting, setExporting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [traceWidth, setTraceWidth] = useState<number>(TRACE_LIMITS.initial);
  const [detailWidth, setDetailWidth] = useState<number>(DETAIL_LIMITS.initial);
  const [traceOpen, setTraceOpen] = useState<Record<string, boolean>>({});
  const [traceSelected, setTraceSelected] = useState("");

  const analyzed = project.mode === "results" || project.mode === "error";
  const inventory = artifactItems(project.inventory);

  const load = useCallback(
    async (db: string): Promise<void> => {
      dispatch({ type: "SET_GRAPH", graph: { status: "loading" } });
      try {
        const data = await window.cobolInsight.readGraph(db);
        dispatch({ type: "SET_GRAPH", graph: { status: "ready", data } });
      } catch (error) {
        dispatch({ type: "SET_GRAPH", graph: { status: "error", message: messageOf(error) } });
      }
    },
    [dispatch],
  );

  useEffect(() => {
    if (!analyzed || dbPath === null || graph.status !== "idle") {
      return;
    }
    void load(dbPath);
  }, [analyzed, dbPath, graph.status, load]);

  const data = useMemo(
    () => (graph.status === "ready" ? toCallGraphData(graph.data) : EMPTY_GRAPH),
    [graph],
  );
  const trace = useMemo(
    () => (graph.status === "ready" ? buildTrace(graph.data, inventory) : []),
    [graph, inventory],
  );
  const traceRows = useMemo(() => flattenTrace(trace, traceOpen), [trace, traceOpen]);

  const visibleIds = useMemo(
    () => visibleNodeIds(data, { expanded: { ...graphView.expanded }, kinds: { ...graphView.kinds } }),
    [data, graphView],
  );
  const elements = useMemo(() => buildGraphElements(data, visibleIds), [data, visibleIds]);
  const counts = useMemo(() => nodeKindCounts(data), [data]);
  const detail = useMemo(
    () =>
      graphView.selected === null ? null : nodeDetail(data, graphView.selected, visibleIds),
    [data, graphView.selected, visibleIds],
  );

  /** 図を engine 側で書き出し、engine が書いたファイルの位置を利用者へ示す。 */
  async function exportImage(format: "svg" | "png"): Promise<void> {
    if (inputDir === null) {
      return;
    }
    const paths = graphArtifactPaths(dbPath);
    const label = format === "svg" ? "SVG" : "PNG";
    setExporting(true);
    try {
      const result = await window.cobolInsight.runCallgraph({
        inputDir,
        copybookPaths: [...settings.copybookPaths],
        db: paths.db,
        ...(format === "svg" ? { svgFile: paths.svg } : { pngFile: paths.png }),
      });
      const written = format === "svg" ? result.outputs.svg : result.outputs.png;
      setNotice(
        written === undefined
          ? `${label} の書き出し先を確かめられませんでした。`
          : `呼出関係図を ${label} で書き出しました: ${written}`,
      );
    } catch (error) {
      setNotice(`${label} を書き出せませんでした。${messageOf(error)}`);
    } finally {
      setExporting(false);
    }
  }

  if (project.mode === "empty") {
    return (
      <EmptyState
        title="解析結果がありません"
        description="資産フォルダを解析すると表示します。"
      />
    );
  }
  if (project.mode === "running" || graph.status === "idle" || graph.status === "loading") {
    return <RunningIndicator title="呼出関係を読み出しています" stages={NO_STAGES} />;
  }
  if (dbPath === null) {
    return (
      <EmptyState
        title="解析結果の保存先が分かりません"
        description="エクスプローラーで資産フォルダを選び直して、もう一度解析してください。"
      />
    );
  }
  if (graph.status === "error") {
    return (
      <EmptyState
        icon="！"
        title="呼出関係を読み出せませんでした"
        description={`${graph.message} 解析結果の保存先を確かめてください。`}
        actionLabel="もう一度読み出す"
        onAction={() => dispatch({ type: "SET_GRAPH", graph: { status: "idle" } })}
      />
    );
  }

  const unanalyzable = unanalyzableBanner(unanalyzableCount(data));
  const warning = graphWarning(visibleIds.size);
  const sourcePaths = detail === null ? [] : graphSourcePaths(detail.node, inventory);
  // 各ペインの幅と、相手側へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側)。
  // 実行順の一覧は自分の相手側(図)の最小を見るため、図の領域で上書きする。
  const paneStyle = {
    "--ci-graph-detail-w": `${detailWidth}px`,
    "--ci-opposite-min": `${DETAIL_LIMITS.oppositeMin}px`,
  } as CSSProperties;
  const figureStyle = {
    "--ci-graph-nodes-w": `${traceWidth}px`,
    "--ci-opposite-min": `${TRACE_LIMITS.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-graph" style={paneStyle}>
      <div className="ci-graph__main">
        <GraphToolbar
          kinds={{ ...graphView.kinds }}
          counts={counts}
          onToggleKind={(kind) =>
            dispatch({ type: "SET_GRAPH_VIEW", view: toggleKind(graphView, kind) })
          }
          visibleCount={visibleIds.size}
          totalCount={data.nodes.length}
          onRebuild={() => dispatch({ type: "SET_GRAPH", graph: { status: "idle" } })}
          onExportSvg={() => void exportImage("svg")}
          onExportPng={() => void exportImage("png")}
          busy={exporting}
          detailCollapsed={graphView.detailCollapsed}
          onToggleDetail={() =>
            dispatch({
              type: "SET_GRAPH_VIEW",
              view: { ...graphView, detailCollapsed: !graphView.detailCollapsed },
            })
          }
        />
        {notice === null ? null : (
          <p className="ci-graph__notice" role="status">
            {notice}
          </p>
        )}
        {unanalyzable === null ? null : (
          <div className="ci-graph__warning" role="status">
            {unanalyzable}
          </div>
        )}
        {warning === null ? null : (
          <div className="ci-graph__warning" role="status">
            {warning}
          </div>
        )}
        {data.nodes.length === 0 ? (
          <EmptyState title="呼出関係が見つかりませんでした" />
        ) : (
          <div className="ci-graph__figure" style={figureStyle}>
            <TraceTree
              rows={traceRows}
              selectedId={traceSelected}
              onSelect={(node) => {
                setTraceSelected(node.id);
                dispatch({
                  type: "SET_GRAPH_VIEW",
                  view: selectPath(graphView, node.graphPath),
                });
              }}
              onToggle={(id) => setTraceOpen({ ...traceOpen, [id]: traceOpen[id] !== true })}
              onOpen={(path, line) =>
                workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(path, line) })
              }
            />
            <SplitHandle
              size={traceWidth}
              min={TRACE_LIMITS.min}
              oppositeMin={TRACE_LIMITS.oppositeMin}
              side="before"
              ariaLabel="実行順の一覧の幅"
              onSizeChange={setTraceWidth}
            />
            <GraphCanvas
              elements={elements}
              selectedId={graphView.selected}
              onSelectNode={(id) =>
                dispatch({ type: "SET_GRAPH_VIEW", view: { ...graphView, selected: id } })
              }
            />
          </div>
        )}
      </div>
      {/* 畳んだときはハンドルもろとも出さない。ハンドルの下限は「ペインが見える最小の幅」である。 */}
      {graphView.detailCollapsed ? null : (
        <>
          <SplitHandle
            size={detailWidth}
            min={DETAIL_LIMITS.min}
            oppositeMin={DETAIL_LIMITS.oppositeMin}
            ariaLabel="ノード情報と凡例のペインの幅"
            onSizeChange={setDetailWidth}
          />
          <GraphDetail
            detail={detail}
            expanded={
              graphView.selected !== null && graphView.expanded[graphView.selected] === true
            }
            onToggleExpanded={() => {
              if (graphView.selected !== null) {
                dispatch({
                  type: "SET_GRAPH_VIEW",
                  view: toggleExpanded(graphView, graphView.selected),
                });
              }
            }}
            sources={sourcePaths}
            onOpenSource={(path) =>
              workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(path) })
            }
          />
        </>
      )}
    </div>
  );
}
