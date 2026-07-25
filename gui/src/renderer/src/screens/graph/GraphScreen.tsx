import { useEffect, useMemo, useState, type ReactElement } from "react";
import type { CallGraphData } from "../../../../shared/engine-api";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
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
  visibleNodeIds,
} from "./graphModel";

/** 呼出関係を構築している間に提示する段(design:196 の副見出し)。 */
const GRAPH_RUN_STAGES = ["CALL の解決根拠(定数由来 / データフロー由来 / 未解決)を判定中"];

/** グラフ未取得のときに用いる空グラフ。useMemo の依存を安定させるため定数で持つ。 */
const EMPTY_GRAPH: CallGraphData = { nodes: [], edges: [] };

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 呼出関係図(callgraph)。engine の callgraph サブコマンドが書いた JSON を唯一の供給源とし、
 * GUI は種別フィルタ・部分展開・詳細表示を担う(裁定 A3)。全ノードの一括描画は約 3200 ノードで
 * 劣化するため既定にせず、ジョブ・トランザクションを起点とする部分展開から始める。図の書出は
 * engine の callgraph --svg / --png を起動して行い、renderer からファイルは書かない(裁定 A7)。
 *
 * 4状態は実状態から導く。empty=解析未実行、running=解析実行中または callgraph 起動中、
 * results=グラフ取得済み、error=callgraph の起動または JSON 読取の失敗である。callgraph の
 * 非ゼロ終了(警告あり・エラーあり)はグラフ本体が書かれる部分的な失敗なので、図を隠さず警告で示す。
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

  const visibleIds = useMemo(
    () => visibleNodeIds(data, { expanded: state.graphExpanded, kinds: state.graphTypes }),
    [data, state.graphExpanded, state.graphTypes],
  );
  const elements = useMemo(() => buildGraphElements(data, visibleIds), [data, visibleIds]);
  const nodeList = useMemo(() => nodeListItems(elements), [elements]);
  const counts = useMemo(() => nodeKindCounts(data), [data]);
  const detail = useMemo(
    () => (state.selectedNode === null ? null : nodeDetail(data, state.selectedNode, visibleIds)),
    [data, state.selectedNode, visibleIds],
  );

  // 解析済みでグラフが未取得なら、この画面が callgraph を起動して JSON を読む。
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
        throw new Error("callgraph が JSON の出力先を返しませんでした。");
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

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="解析結果がありません"
        description="資産をインポートして解析を実行すると、ジョブ → プログラム → サブルーチン → データセットの呼出関係を表示する。"
        actionLabel="資産エクスプローラーへ"
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
        description="資産エクスプローラーで資産フォルダをインポートすると、呼出関係図を構築できる。"
        actionLabel="資産エクスプローラーへ"
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
        description={`callgraph の実行または JSON の読取に失敗しました。${graph.message}`}
        actionLabel="再試行"
        onAction={rebuild}
      />
    );
  }

  const exitBanner = graphExitBanner(graph.exitCode);
  const warning = graphWarning(visibleIds.size);
  const inventory = state.inventory.status === "ready" ? state.inventory.items : [];
  const sourcePaths = detail === null ? [] : graphSourcePaths(detail.node, inventory);

  return (
    <div className="ci-graph">
      <div className="ci-graph__main">
        <GraphToolbar
          kinds={state.graphTypes}
          counts={counts}
          onToggleKind={(kind) => dispatch({ type: "TOGGLE_GRAPH_KIND", kind })}
          visibleCount={visibleIds.size}
          totalCount={data.nodes.length}
          onRebuild={rebuild}
          onExportSvg={() => void exportImage("svg")}
          onExportPng={() => void exportImage("png")}
          busy={exporting}
        />
        {exitBanner === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {exitBanner}
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
            description="解析した資産に、ジョブ・プログラム・データセットの呼出関係は見つかりませんでした。"
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
    </div>
  );
}
