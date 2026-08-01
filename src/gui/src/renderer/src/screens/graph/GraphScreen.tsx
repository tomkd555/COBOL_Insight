import { useEffect, useMemo, useState, type CSSProperties, type ReactElement } from "react";
import type { CallGraphData } from "../../../../shared/engine-api";
import { SPLIT_PANES } from "../../state/appState";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SplitHandle } from "../../components/SplitHandle";
import { SCREEN_META } from "../screenMeta";
import { GraphToolbar } from "./GraphToolbar";
import { GraphCanvas } from "./GraphCanvas";
import { GraphDetail } from "./GraphDetail";
import { GraphNodeList } from "./GraphNodeList";
import {
  buildGraphElements,
  graphArtifactPaths,
  graphExitBanner,
  graphSourcePaths,
  graphWarning,
  nodeDetail,
  nodeKindCounts,
  nodeListItems,
  unanalyzableBanner,
  unanalyzableCount,
  visibleNodeIds,
  type AnyNodeKind,
} from "./graphModel";

/** 呼出関係を構築している間に提示する段(design scGraph の実行中表示の副見出し)。 */
const GRAPH_RUN_STAGES = ["CALL の解決根拠(定数由来 / データフロー由来 / 未解決)を判定中"];

/** グラフ未取得のときに用いる空グラフ。useMemo の依存を安定させるため定数で持つ。 */
const EMPTY_GRAPH: CallGraphData = { nodes: [], edges: [] };

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 呼出関係図(call-graph)。engine の call-graph サブコマンドが書いた JSON を唯一の供給源とし、
 * GUI は種別フィルタ・部分展開・詳細表示を担う。全ノードの一括描画は約 3200 ノードで
 * 劣化するため既定にせず、ジョブ・トランザクションを起点とする部分展開から始める。図の書出は
 * engine の call-graph --svg / --png を起動して行い、renderer からファイルは書かない。
 *
 * 4状態は実状態から導く。empty=解析未実行、running=解析実行中または call-graph 起動中、
 * results=グラフ取得済み、error=call-graph の起動または JSON 読取の失敗である。call-graph の
 * 非ゼロ終了(警告あり・エラーあり)はグラフ本体が書かれる部分的な失敗なので、図を隠さず警告で示す。
 * 構文解析に失敗した資産も「解析不能」ノードとして図に含め、隠さず件数を案内する。
 */
export function GraphScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.graph;
  const graph = state.graph;
  const { inputDir, dbPath, copybookPaths } = state.project;
  const paths = useMemo(() => graphArtifactPaths(dbPath), [dbPath]);
  const [exporting, setExporting] = useState(false);

  const analyzed = state.mode === "results" || state.mode === "error";
  const data = graph.status === "ready" ? graph.data : EMPTY_GRAPH;

  // 解析不能は AppState の graphTypes(GraphNodeKind の11種)に含めた種別のため、他の10種と
  // 同じ TOGGLE_GRAPH_KIND で切り替える。ローカル状態は持たない(タブ移動でも既定へ戻さない)。
  const kindFilters: Record<AnyNodeKind, boolean> = state.graphTypes;
  const visibleIds = useMemo(
    () => visibleNodeIds(data, { expanded: state.graphExpanded, kinds: kindFilters }),
    [data, state.graphExpanded, kindFilters],
  );
  const elements = useMemo(() => buildGraphElements(data, visibleIds), [data, visibleIds]);
  const nodeList = useMemo(() => nodeListItems(elements), [elements]);
  const counts = useMemo(() => nodeKindCounts(data), [data]);
  const detail = useMemo(
    () => (state.selectedNode === null ? null : nodeDetail(data, state.selectedNode, visibleIds)),
    [data, state.selectedNode, visibleIds],
  );

  // 解析済みでグラフが未取得なら、この画面が call-graph を起動して JSON を読む。
  useEffect(() => {
    if (!analyzed || inputDir === null || graph.status !== "none") {
      return;
    }
    void loadGraph(inputDir);
  }, [analyzed, inputDir, graph.status]);

  async function loadGraph(dir: string): Promise<void> {
    dispatch({ type: "SET_GRAPH", result: { status: "loading" } });
    try {
      const result = await window.cobolInsight.runCallgraph({
        inputDir: dir,
        copybookPaths,
        db: paths.db,
        jsonFile: paths.json,
      });
      const json = result.outputs.json;
      if (json === undefined) {
        throw new Error("呼出関係のデータの出力先を取得できなかった。");
      }
      const loaded = await window.cobolInsight.readCallgraphJson(json);
      dispatch({ type: "SET_GRAPH", result: { status: "ready", data: loaded, exitCode: result.exitCode } });
    } catch (error) {
      dispatch({ type: "SET_GRAPH", result: { status: "error", message: messageOf(error) } });
    }
  }

  /** 図を engine 側で書き出し、engine が書いたファイルのパスを利用者へ示す。 */
  async function exportImage(format: "svg" | "png"): Promise<void> {
    if (inputDir === null) {
      return;
    }
    setExporting(true);
    const label = format === "svg" ? "SVG" : "PNG";
    try {
      const result = await window.cobolInsight.runCallgraph({
        inputDir,
        copybookPaths,
        db: paths.db,
        ...(format === "svg" ? { svgFile: paths.svg } : { pngFile: paths.png }),
      });
      const written = format === "svg" ? result.outputs.svg : result.outputs.png;
      dispatch({
        type: "SHOW_TOAST",
        message:
          written === undefined
            ? `${label} の書出先を確認できませんでした。`
            : `呼出関係図を ${label} で書き出しました: ${written}`,
      });
    } catch (error) {
      dispatch({ type: "SHOW_TOAST", message: `${label} の書出に失敗しました。${messageOf(error)}` });
    } finally {
      setExporting(false);
    }
  }

  /** グラフを破棄して取り直す。取得の失敗からの復帰と、再解析後の更新に使う。 */
  function rebuild(): void {
    dispatch({ type: "SET_GRAPH", result: { status: "none" } });
  }

  /** 種別フィルタチップの切替。解析不能を含む11種のいずれも AppState の graphTypes を切り替える。 */
  function toggleKind(kind: AnyNodeKind): void {
    dispatch({ type: "TOGGLE_GRAPH_KIND", kind });
  }

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="解析結果がありません"
        description="資産を取り込んで解析を実行すると、ジョブ → プログラム → サブルーチン → データセットの呼出関係を表示する。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (state.mode === "running") {
    return <RunningIndicator title={meta.runningTitle} stages={GRAPH_RUN_STAGES} />;
  }
  if (inputDir === null) {
    return (
      <EmptyState
        title="資産フォルダが選ばれていません"
        description="資産一覧で資産フォルダを取り込むと、呼出関係図を構築できる。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (graph.status === "none" || graph.status === "loading") {
    return <RunningIndicator title={meta.runningTitle} stages={GRAPH_RUN_STAGES} />;
  }
  if (graph.status === "error") {
    return (
      <EmptyState
        icon="！"
        title="呼出関係図を取得できませんでした"
        description={`呼出関係の構築またはデータの読み取りに失敗した。${graph.message}`}
        actionLabel="再試行"
        onAction={rebuild}
      />
    );
  }

  const exitBanner = graphExitBanner(graph.exitCode);
  const unanalyzableNotice = unanalyzableBanner(unanalyzableCount(data));
  const warning = graphWarning(visibleIds.size);
  const inventory = state.inventory.status === "ready" ? state.inventory.items : [];
  const sourcePaths = detail === null ? [] : graphSourcePaths(detail.node, inventory);
  const detailWidth = state.paneWidths.graphDetail;
  const detailCollapsed = state.graphDetailCollapsed;
  // 詳細ペインの幅は CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  const paneStyle = { "--ci-graph-detail-w": `${detailWidth}px` } as CSSProperties;

  return (
    <div className="ci-graph" style={paneStyle}>
      <div className="ci-graph__main">
        <GraphToolbar
          kinds={kindFilters}
          counts={counts}
          onToggleKind={toggleKind}
          visibleCount={visibleIds.size}
          totalCount={data.nodes.length}
          onRebuild={rebuild}
          onExportSvg={() => void exportImage("svg")}
          onExportPng={() => void exportImage("png")}
          busy={exporting}
          detailCollapsed={detailCollapsed}
          onToggleDetail={() => dispatch({ type: "TOGGLE_GRAPH_DETAIL" })}
        />
        {exitBanner === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {exitBanner}
          </div>
        )}
        {unanalyzableNotice === null ? null : (
          <div className="ci-graph__warning" role="status">
            {unanalyzableNotice}
          </div>
        )}
        {warning === null ? null : (
          <div className="ci-graph__warning" role="status">
            {warning}
          </div>
        )}
        {data.nodes.length === 0 ? (
          <EmptyState
            title="呼出関係が検出されませんでした"
            description="解析した資産に、ジョブ・プログラム・データセットの呼出関係は見つからなかった。"
          />
        ) : visibleIds.size === 0 ? (
          <p className="ci-graph__notice">
            表示するノードがない。ノード種別フィルタで切った種別を戻すと表示できる。
          </p>
        ) : (
          <div className="ci-graph__figure">
            <GraphNodeList
              items={nodeList}
              selectedId={state.selectedNode}
              onSelect={(id) => dispatch({ type: "SELECT_GRAPH_NODE", id })}
            />
            <GraphCanvas
              elements={elements}
              selectedId={state.selectedNode}
              onSelectNode={(id) => dispatch({ type: "SELECT_GRAPH_NODE", id })}
            />
          </div>
        )}
      </div>
      {/* 畳んだときはハンドルもろとも出さない。ハンドルの下限は「ペインが見える最小の幅」である。 */}
      {detailCollapsed ? null : (
        <>
          <SplitHandle
            width={detailWidth}
            min={SPLIT_PANES.graphDetail.min}
            max={SPLIT_PANES.graphDetail.max}
            onWidthChange={(width) => dispatch({ type: "SET_PANE_WIDTH", pane: "graphDetail", width })}
            ariaLabel="ノード情報と凡例のペインの幅"
          />
          <GraphDetail
            detail={detail}
            expanded={state.selectedNode !== null && state.graphExpanded[state.selectedNode] === true}
            onToggleExpanded={() => {
              if (state.selectedNode !== null) {
                dispatch({ type: "TOGGLE_GRAPH_EXPANDED", id: state.selectedNode });
              }
            }}
            sources={sourcePaths}
            onOpenSource={(file) => dispatch({ type: "JUMP", file, line: null, from: "呼出関係図" })}
          />
        </>
      )}
    </div>
  );
}
