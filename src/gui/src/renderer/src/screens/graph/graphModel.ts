/**
 * 呼出関係図の純ロジック。call-graph サブコマンドの JSON(CallGraphData)から、Cytoscape の要素・
 * スタイル・レイアウト指定・可視集合・詳細情報を導く。描画そのもの(canvas 操作)は GraphCanvas が
 * 担い、ここは DOM にも cytoscape の実行時 API にも触れない。
 *
 * ノード種別は engine の NodeKind 10 種に「解析不能」(構文解析に失敗した資産。engine の列挙には
 * 無く GUI 側で扱う第 11 の種別)を加えた 11 種、エッジ種別は EdgeKind 5 種、解決根拠は Resolution
 * 3 種と対応する。種別は色だけでなく形も変え、凡例と併せて色覚に依存せず区別できるようにする。
 * 解決根拠が DATAFLOW・UNRESOLVED のエッジは破線にして、確定した呼出と区別する。
 */

import type cytoscape from "cytoscape";
import type {
  AssetInventoryItem,
  CallGraphData,
  CallGraphEdge,
  CallGraphNode,
} from "../../../../shared/engine-api";

/** engine の NodeKind(10 種)。call-graph JSON のノードの kind をそのまま用いる。 */
export type GraphNodeKind =
  | "JOB"
  | "STEP"
  | "PROGRAM"
  | "PARAGRAPH"
  | "DATASET"
  | "DB2_TABLE"
  | "UNRESOLVED"
  | "EXTERNAL_UTILITY"
  | "TRANSACTION"
  | "BMS_MAP";

/**
 * ノード種別。engine の NodeKind(GraphNodeKind、10 種)に、構文解析に失敗した資産を表す
 * 「解析不能」(UNANALYZABLE)を加えた 11 種。UNANALYZABLE は engine の NodeKind 列挙には無く、
 * call-graph JSON のノードで kind が "UNANALYZABLE" のものを GUI 側だけで識別する。
 */
export type AnyNodeKind = GraphNodeKind | "UNANALYZABLE";

/** ノード種別ごとの見え方。形・地色・枠色・枠線種を種別ごとに変える。 */
export interface NodeKindStyle {
  readonly kind: AnyNodeKind;
  /** 凡例・詳細ペインに出す日本語表示名。 */
  readonly label: string;
  readonly shape: cytoscape.Css.NodeShape;
  /** 図中の図形の日本語名。凡例で色に頼らず形を言葉で示すために持つ。 */
  readonly shapeLabel: string;
  readonly background: string;
  readonly border: string;
  readonly borderStyle: "solid" | "dashed";
}

/**
 * ノード種別の並びと見え方。並びは凡例・フィルタチップの表示順であり、ジョブ→ステップ→
 * プログラム→段落→データセット→Db2表→トランザクション→BMSマップ→外部ユーティリティ→未解決→
 * 解析不能の順に、バッチの上流から下流、続いてオンライン、最後に外部・未解決・解析不能を置く。
 * 配色は design のノード種別(design の D.NTY)を踏襲する(解析不能は design に無いため GUI 側で定める)。
 *
 * 解析不能は「未解決」(動的 CALL の解決失敗。呼出先は分からないが呼出関係自体は図に現れる)とは
 * 別概念で、資産そのものの構文解析が失敗し呼出関係が一切分からないことを表す。混同しないよう、
 * 既存 10 種のどれとも重ならない形(星形)と配色を与える。
 */
export const NODE_KIND_STYLES: readonly NodeKindStyle[] = [
  { kind: "JOB", label: "ジョブ", shape: "hexagon", shapeLabel: "六角形", background: "#e3edf9", border: "#5b8bc4", borderStyle: "solid" },
  { kind: "STEP", label: "ステップ", shape: "round-rectangle", shapeLabel: "角丸長方形", background: "#eff4fa", border: "#93aecb", borderStyle: "solid" },
  { kind: "PROGRAM", label: "プログラム", shape: "rectangle", shapeLabel: "長方形", background: "#ffffff", border: "#5f6b7a", borderStyle: "solid" },
  { kind: "PARAGRAPH", label: "段落・節", shape: "ellipse", shapeLabel: "楕円", background: "#f7f7f9", border: "#9aa4b1", borderStyle: "solid" },
  { kind: "DATASET", label: "データセット", shape: "barrel", shapeLabel: "樽形", background: "#f7f3e8", border: "#b3a05f", borderStyle: "solid" },
  { kind: "DB2_TABLE", label: "Db2表", shape: "cut-rectangle", shapeLabel: "隅切り長方形", background: "#eaf4ee", border: "#5f9e7c", borderStyle: "solid" },
  { kind: "TRANSACTION", label: "トランザクション", shape: "octagon", shapeLabel: "八角形", background: "#f1ebf8", border: "#8e6fb8", borderStyle: "solid" },
  { kind: "BMS_MAP", label: "BMSマップ", shape: "rhomboid", shapeLabel: "平行四辺形", background: "#fbf1e4", border: "#c0913f", borderStyle: "solid" },
  { kind: "EXTERNAL_UTILITY", label: "外部ユーティリティ", shape: "tag", shapeLabel: "タグ形", background: "#f2f2f2", border: "#8a8a8a", borderStyle: "solid" },
  { kind: "UNRESOLVED", label: "未解決", shape: "diamond", shapeLabel: "ひし形(破線)", background: "#fdeded", border: "#c50f1f", borderStyle: "dashed" },
  { kind: "UNANALYZABLE", label: "解析不能", shape: "star", shapeLabel: "星形", background: "#dde1e4", border: "#3a4048", borderStyle: "solid" },
];

/** 種別フィルタが扱うノード種別の並び(NODE_KIND_STYLES と同順)。 */
export const NODE_KINDS: readonly AnyNodeKind[] = NODE_KIND_STYLES.map((style) => style.kind);

const NODE_KIND_STYLE_BY_KIND = new Map<string, NodeKindStyle>(
  NODE_KIND_STYLES.map((style) => [style.kind, style]),
);

/**
 * 種別ごとの見え方を返す。engine の列挙に無い種別が来た場合もノードを隠さず、種別名をそのまま
 * 表示名にした灰色の代替で描く(解析結果を隠さないことを図でも保つ)。
 */
export function nodeKindStyle(kind: string): NodeKindStyle {
  const style = NODE_KIND_STYLE_BY_KIND.get(kind);
  if (style !== undefined) {
    return style;
  }
  return {
    kind: "UNRESOLVED",
    label: "不明な種別",
    shape: "rectangle",
    shapeLabel: "長方形(破線)",
    background: "#f2f2f2",
    border: "#8a8a8a",
    borderStyle: "dashed",
  };
}

/** 既知のノード種別(フィルタのキーになる 11 種)か。 */
export function isGraphNodeKind(kind: string): kind is AnyNodeKind {
  return NODE_KIND_STYLE_BY_KIND.has(kind);
}

/** エッジ種別ごとの見え方。色と矢頭形状の二重符号化で、色覚に依存せず種別を区別できるようにする。 */
export interface EdgeKindStyle {
  readonly kind: string;
  readonly label: string;
  readonly color: string;
  /** 矢頭の形(Cytoscape の target-arrow-shape)。 */
  readonly arrowShape: cytoscape.Css.ArrowShape;
  /** 矢頭の日本語名。凡例で形を言葉で示すために持つ。 */
  readonly arrowLabel: string;
}

/**
 * エッジ種別の並びと見え方。配色は design のエッジ種別(design の D.EK)を踏襲し、
 * 矢頭形状を種別ごとに変える。線種は解決根拠(破線=DATAFLOW・UNRESOLVED)専用であり、
 * 種別の区別には用いない。
 */
export const EDGE_KIND_STYLES: readonly EdgeKindStyle[] = [
  { kind: "EXECUTION", label: "実行(EXEC PGM)", color: "#5f6b7a", arrowShape: "triangle", arrowLabel: "三角" },
  { kind: "CALL", label: "呼出(CALL・PERFORM)", color: "#005fb8", arrowShape: "vee", arrowLabel: "V字" },
  { kind: "REFERENCE", label: "参照(データセット・Db2表)", color: "#9c8a4e", arrowShape: "square", arrowLabel: "四角" },
  { kind: "TRANSACTION_TRANSITION", label: "トランザクション遷移", color: "#8e6fb8", arrowShape: "diamond", arrowLabel: "ひし形" },
  { kind: "MAP_REFERENCE", label: "BMSマップ参照", color: "#c0913f", arrowShape: "circle", arrowLabel: "丸" },
];

const EDGE_KIND_STYLE_BY_KIND = new Map<string, EdgeKindStyle>(
  EDGE_KIND_STYLES.map((style) => [style.kind, style]),
);

export function edgeKindStyle(kind: string): EdgeKindStyle {
  return (
    EDGE_KIND_STYLE_BY_KIND.get(kind) ?? {
      kind,
      label: kind,
      color: "#8a8a8a",
      arrowShape: "tee",
      arrowLabel: "横棒",
    }
  );
}

/**
 * 破線で描くエッジか。データフロー由来(DATAFLOW)と未解決(UNRESOLVED)を破線にし、
 * 定数由来(CONSTANT)と区別する。完全解決を装わないための表現である。
 */
export function isDashedEdge(resolution: string): boolean {
  return resolution === "DATAFLOW" || resolution === "UNRESOLVED";
}

/** 解決根拠の日本語表示名。 */
export function resolutionLabel(resolution: string): string {
  if (resolution === "CONSTANT") return "定数由来";
  if (resolution === "DATAFLOW") return "データフロー由来";
  if (resolution === "UNRESOLVED") return "未解決";
  return resolution;
}

/** 破線エッジ・選択ノードに付ける Cytoscape クラス。 */
export const DASHED_EDGE_CLASS = "ci-edge-dashed";
export const SELECTED_NODE_CLASS = "ci-node-selected";

/**
 * 部分展開の起点ノード ID。ジョブとトランザクションを起点とする。どちらも無いグラフ
 * (COBOL だけを解析した場合など)では入次数 0 のノードを起点とし、循環しかない場合は
 * 全ノードを起点として、起点が無くなって何も表示できない状態を避ける。
 *
 * エッジを一切持たない孤立ノード(解析不能ノードなど)は、展開による推移到達では永久に
 * 表示に加わらないため、ジョブ・トランザクションが起点になる場合でも常に起点集合へ加える。
 */
export function graphRootIds(data: CallGraphData): string[] {
  const roots = data.nodes
    .filter((node) => node.kind === "JOB" || node.kind === "TRANSACTION")
    .map((node) => node.id);
  if (roots.length > 0) {
    const rootSet = new Set(roots);
    return [...roots, ...isolatedNodeIds(data).filter((id) => !rootSet.has(id))];
  }
  const hasIncoming = new Set(data.edges.map((edge) => edge.to));
  const sources = data.nodes.filter((node) => !hasIncoming.has(node.id)).map((node) => node.id);
  return sources.length > 0 ? sources : data.nodes.map((node) => node.id);
}

/** 可視集合の決定に用いる、画面の操作状態。 */
export interface GraphVisibility {
  /** 展開したノード ID(値が真のものだけを展開として扱う)。 */
  readonly expanded: Record<string, boolean>;
  /** ノード種別フィルタの ON/OFF。 */
  readonly kinds: Record<AnyNodeKind, boolean>;
}

/** ノード ID から、エッジで隣接するノード ID の集合を引く索引を作る(向きは問わない)。 */
function adjacencyIndex(data: CallGraphData): Map<string, Set<string>> {
  const index = new Map<string, Set<string>>();
  const add = (from: string, to: string): void => {
    const set = index.get(from);
    if (set === undefined) index.set(from, new Set([to]));
    else set.add(to);
  };
  for (const edge of data.edges) {
    add(edge.from, edge.to);
    add(edge.to, edge.from);
  }
  return index;
}

/** エッジを一切持たないノード(孤立ノード)の ID。解析不能ノードは呼出関係が分からず孤立して来る。 */
function isolatedNodeIds(data: CallGraphData): string[] {
  const adjacency = adjacencyIndex(data);
  return data.nodes.filter((node) => (adjacency.get(node.id)?.size ?? 0) === 0).map((node) => node.id);
}

/**
 * 表示するノード ID の集合。起点から始め、展開済みノードの隣接を不動点まで加える。
 * 展開は推移する(展開済みノードが表示に入れば、その隣接も表示に入る)。エッジの向きは問わず、
 * データセットからステップへ向かうエッジのような入力方向の隣接も展開の対象にする。
 * 最後にノード種別フィルタで絞る。全ノード一括描画は約 3200 ノードで劣化するため、既定は起点のみとする。
 */
export function visibleNodeIds(data: CallGraphData, visibility: GraphVisibility): Set<string> {
  const known = new Set(data.nodes.map((node) => node.id));
  const adjacency = adjacencyIndex(data);
  const reached = new Set<string>(graphRootIds(data));
  const queue = [...reached].filter((id) => visibility.expanded[id] === true);
  while (queue.length > 0) {
    const id = queue.shift() as string;
    for (const neighbor of adjacency.get(id) ?? []) {
      if (!known.has(neighbor) || reached.has(neighbor)) {
        continue;
      }
      reached.add(neighbor);
      if (visibility.expanded[neighbor] === true) {
        queue.push(neighbor);
      }
    }
  }
  const visible = new Set<string>();
  for (const node of data.nodes) {
    if (!reached.has(node.id)) {
      continue;
    }
    if (isGraphNodeKind(node.kind) && !visibility.kinds[node.kind]) {
      continue;
    }
    visible.add(node.id);
  }
  return visible;
}

/** Cytoscape のノード要素が持つデータ。 */
export interface GraphNodeElementData {
  readonly id: string;
  readonly label: string;
  readonly kind: string;
  /** 種別の日本語表示名(ノード内の小見出しに使う)。 */
  readonly kindLabel: string;
}

/** Cytoscape のエッジ要素が持つデータ。 */
export interface GraphEdgeElementData {
  readonly id: string;
  readonly source: string;
  readonly target: string;
  readonly kind: string;
  readonly resolution: string;
}

export interface GraphNodeElement {
  readonly group: "nodes";
  readonly data: GraphNodeElementData;
  readonly classes?: string;
}

export interface GraphEdgeElement {
  readonly group: "edges";
  readonly data: GraphEdgeElementData;
  readonly classes?: string;
}

export type GraphElement = GraphNodeElement | GraphEdgeElement;

/** エッジ要素の ID。engine は (from,to,kind,resolution) で一意なため、この 4 つを連結する。 */
function edgeElementId(edge: CallGraphEdge): string {
  return `${edge.from}|${edge.to}|${edge.kind}|${edge.resolution}`;
}

/**
 * 可視集合から Cytoscape の要素配列を作る。エッジは両端が可視のものだけを含める。
 * 破線は解決根拠で決め、クラスで表す(スタイル側の selector と対応する)。
 */
export function buildGraphElements(data: CallGraphData, visibleIds: ReadonlySet<string>): GraphElement[] {
  const elements: GraphElement[] = [];
  for (const node of data.nodes) {
    if (!visibleIds.has(node.id)) {
      continue;
    }
    elements.push({
      group: "nodes",
      data: {
        id: node.id,
        label: node.label,
        kind: node.kind,
        kindLabel: nodeKindStyle(node.kind).label,
      },
    });
  }
  for (const edge of data.edges) {
    if (!visibleIds.has(edge.from) || !visibleIds.has(edge.to)) {
      continue;
    }
    const element: GraphEdgeElement = {
      group: "edges",
      data: {
        id: edgeElementId(edge),
        source: edge.from,
        target: edge.to,
        kind: edge.kind,
        resolution: edge.resolution,
      },
      ...(isDashedEdge(edge.resolution) ? { classes: DASHED_EDGE_CLASS } : {}),
    };
    elements.push(element);
  }
  return elements;
}

/**
 * Cytoscape のスタイル定義。基底のノード様式に続けて種別ごとの形・配色、エッジ種別ごとの色と
 * 矢頭形状、破線、選択強調を重ねる。値はデザイントークンと同じ色を用いるが、Cytoscape は
 * canvas 描画で CSS 変数を解釈しないため実値で書く。
 */
export function graphStylesheet(): cytoscape.StylesheetJsonBlock[] {
  const base: cytoscape.StylesheetJsonBlock[] = [
    {
      selector: "node",
      style: {
        shape: "rectangle",
        "background-color": "#f2f2f2",
        "border-width": 1.5,
        "border-color": "#8a8a8a",
        "border-style": "dashed",
        label: "data(label)",
        "font-family": "'BIZ UDGothic','MS Gothic',monospace",
        "font-size": 11,
        "font-weight": "bold",
        color: "#1f2328",
        "text-valign": "center",
        "text-halign": "center",
        "text-wrap": "ellipsis",
        "text-max-width": "150px",
        width: "label",
        height: 34,
        padding: "8px",
      },
    },
  ];
  const byKind: cytoscape.StylesheetJsonBlock[] = NODE_KIND_STYLES.map((style) => ({
    selector: `node[kind="${style.kind}"]`,
    style: {
      shape: style.shape,
      "background-color": style.background,
      "border-color": style.border,
      "border-style": style.borderStyle,
    },
  }));
  const edges: cytoscape.StylesheetJsonBlock[] = [
    {
      selector: "edge",
      style: {
        width: 1.4,
        "line-color": "#8a8a8a",
        "target-arrow-color": "#8a8a8a",
        "target-arrow-shape": "triangle",
        "arrow-scale": 0.8,
        "curve-style": "bezier",
        opacity: 0.85,
      },
    },
    ...EDGE_KIND_STYLES.map((style): cytoscape.StylesheetJsonBlock => ({
      selector: `edge[kind="${style.kind}"]`,
      style: {
        "line-color": style.color,
        "target-arrow-color": style.color,
        "target-arrow-shape": style.arrowShape,
      },
    })),
    { selector: `edge.${DASHED_EDGE_CLASS}`, style: { "line-style": "dashed" } },
  ];
  const selected: cytoscape.StylesheetJsonBlock[] = [
    {
      selector: `node.${SELECTED_NODE_CLASS}`,
      style: { "border-width": 3.5, "border-color": "#005fb8", "border-style": "solid" },
    },
  ];
  return [...base, ...byKind, ...edges, ...selected];
}

/**
 * fit による自動ズームの上限倍率。Cytoscape の maxZoom はコア(cytoscape() の引数)の設定であり、
 * レイアウト指定へ書いても無視される。graphCoreOptions を通してコアへ渡すこと。
 */
export const GRAPH_MAX_ZOOM = 1;

/** Cytoscape のコア生成時の指定。 */
export interface GraphCoreOptions {
  readonly container: HTMLElement;
  readonly style: cytoscape.StylesheetJsonBlock[];
  readonly autoungrabify: boolean;
  readonly maxZoom: number;
}

/**
 * コアの指定。ノード数が少ない図(起点だけの初期表示など)で 1 ノードが画面の大半を占めるほど
 * 拡大されることを maxZoom で防ぐ。レイアウトが決めた層の並びを保つため、ノードのドラッグ移動は
 * 許さない。
 */
export function graphCoreOptions(container: HTMLElement): GraphCoreOptions {
  return {
    container,
    style: graphStylesheet(),
    autoungrabify: true,
    maxZoom: GRAPH_MAX_ZOOM,
  };
}

/** ELK レイアウトの指定。cytoscape-elk 経由で elkjs が層化レイアウトを計算する。 */
export interface GraphLayoutOptions {
  readonly name: "elk";
  readonly nodeDimensionsIncludeLabels: boolean;
  readonly fit: boolean;
  readonly padding: number;
  readonly elk: Readonly<Record<string, string | number>>;
}

/**
 * 層化レイアウトの指定。ジョブ→ステップ→プログラム→データセットの流れを左から右へ置く
 * (design の呼出関係図の並びと同じ向き)。
 */
export function graphLayoutOptions(): GraphLayoutOptions {
  return {
    name: "elk",
    nodeDimensionsIncludeLabels: true,
    fit: true,
    padding: 24,
    elk: {
      algorithm: "layered",
      "elk.direction": "RIGHT",
      "elk.spacing.nodeNode": 24,
      "elk.layered.spacing.nodeNodeBetweenLayers": 64,
      "elk.edgeRouting": "SPLINES",
    },
  };
}

/** 種別ごとのノード件数(グラフ全体)。フィルタチップの件数表示に使う。 */
export function nodeKindCounts(data: CallGraphData): Record<AnyNodeKind, number> {
  const counts = Object.fromEntries(NODE_KINDS.map((kind) => [kind, 0])) as Record<AnyNodeKind, number>;
  for (const node of data.nodes) {
    if (isGraphNodeKind(node.kind)) {
      counts[node.kind] += 1;
    }
  }
  return counts;
}

/**
 * 一度に描く表示ノード数の上限目安。この値を超えたら警告するだけで、描画は打ち切らない。
 *
 * 値は実測に基づく。合成グラフ(N 個の起点ノードを1本に連ねた N-1 本の辺)を偽 preload から与え、
 * 呼出関係図のタブを押してから canvas へ画素が乗るまでの時間を Electron の offscreen 描画で測った
 * 結果は、50 件=167ms・300 件=301ms・1,000 件=623ms・2,000 件=1,102ms・4,000 件=2,014ms であり、
 * 特定の件数で急に落ちる点は無く、件数へおおむね比例した。操作の流れが途切れない上限を 1 秒と置くと
 * この合成グラフでは約 2,000 件に当たるが、実資産のグラフは辺が密でレイアウトの費用がこれより高い。
 * そのため半分の 1,000 件を目安とする。合成グラフは辺が疎であり、測定値は下限として読む。
 */
export const VISIBLE_NODE_WARNING_THRESHOLD = 1000;

/** 表示ノード数が閾値を超えたときの警告文。閾値以下は null。 */
export function graphWarning(visibleCount: number): string | null {
  if (visibleCount <= VISIBLE_NODE_WARNING_THRESHOLD) {
    return null;
  }
  return (
    `表示ノードが ${visibleCount} 件で、快適に描ける目安の ${VISIBLE_NODE_WARNING_THRESHOLD} 件を超えている。` +
    "ノード種別フィルタで種別を絞るか、展開したノードを畳んで表示数を減らす。全体像は SVG／PNG 出力で確認する。"
  );
}

/** SQLite の位置が未確定なときに使う既定名(engine CLI の --db の既定値と同じ)。 */
export const DEFAULT_DB_FILE = "cobol-insight.db";

/** call-graph の成果物パス一式。 */
export interface GraphArtifactPaths {
  readonly db: string;
  readonly json: string;
  readonly svg: string;
  readonly png: string;
}

/**
 * call-graph へ渡す成果物パスを、解析で使う SQLite と同じフォルダへそろえて導く。
 * SQLite の位置が未確定(未解析)なら、engine CLI の既定と同じ作業フォルダ直下の相対名を使う。
 */
export function graphArtifactPaths(dbPath: string | null): GraphArtifactPaths {
  const db = dbPath ?? DEFAULT_DB_FILE;
  const separator = Math.max(db.lastIndexOf("\\"), db.lastIndexOf("/"));
  const dir = separator < 0 ? "" : db.slice(0, separator + 1);
  return { db, json: `${dir}callgraph.json`, svg: `${dir}callgraph.svg`, png: `${dir}callgraph.png` };
}

/** 詳細ペインに出す 1 本のエッジ。相手側ノードの表示名を添える。 */
export interface GraphEdgeDetail {
  readonly peerId: string;
  readonly peerLabel: string;
  readonly kindLabel: string;
  readonly resolutionLabel: string;
  readonly dashed: boolean;
}

/** 詳細ペインに出す選択ノードの情報。 */
export interface GraphNodeDetail {
  readonly node: CallGraphNode;
  readonly kindStyle: NodeKindStyle;
  readonly attributes: readonly { readonly key: string; readonly value: string }[];
  readonly incoming: readonly GraphEdgeDetail[];
  readonly outgoing: readonly GraphEdgeDetail[];
  /** 隣接ノードを 1 件以上持つか(部分展開の対象になるか)。 */
  readonly expandable: boolean;
  /** 隣接のうち、まだ表示されていない件数。 */
  readonly hiddenNeighborCount: number;
}

function edgeDetail(peerId: string, label: string, edge: CallGraphEdge): GraphEdgeDetail {
  return {
    peerId,
    peerLabel: label,
    kindLabel: edgeKindStyle(edge.kind).label,
    resolutionLabel: resolutionLabel(edge.resolution),
    dashed: isDashedEdge(edge.resolution),
  };
}

/**
 * 選択ノードの詳細。入出力エッジは engine の並び(from,to,kind,resolution 昇順)をそのまま保ち、
 * 相手側ノードの表示名を添える。相手が未知の ID の場合は ID をそのまま表示名にする。
 */
export function nodeDetail(
  data: CallGraphData,
  id: string,
  visibleIds: ReadonlySet<string>,
): GraphNodeDetail | null {
  const node = data.nodes.find((entry) => entry.id === id);
  if (node === undefined) {
    return null;
  }
  const labelById = new Map(data.nodes.map((entry) => [entry.id, entry.label]));
  const labelOf = (peerId: string): string => labelById.get(peerId) ?? peerId;
  const incoming: GraphEdgeDetail[] = [];
  const outgoing: GraphEdgeDetail[] = [];
  const neighbors = new Set<string>();
  for (const edge of data.edges) {
    if (edge.to === id) {
      incoming.push(edgeDetail(edge.from, labelOf(edge.from), edge));
      neighbors.add(edge.from);
    }
    if (edge.from === id) {
      outgoing.push(edgeDetail(edge.to, labelOf(edge.to), edge));
      neighbors.add(edge.to);
    }
  }
  neighbors.delete(id);
  let hiddenNeighborCount = 0;
  for (const neighbor of neighbors) {
    if (!visibleIds.has(neighbor)) {
      hiddenNeighborCount += 1;
    }
  }
  return {
    node,
    kindStyle: nodeKindStyle(node.kind),
    attributes: Object.entries(node.attributes).map(([key, value]) => ({ key, value })),
    incoming,
    outgoing,
    expandable: neighbors.size > 0,
    hiddenNeighborCount,
  };
}

/**
 * ノードに対応するソースの相対パス。call-graph JSON はノードにソースの位置を持たないため、
 * 資産一覧(scan が SQLite へ書いた SOURCE)の側から名前で対応づける。プログラムは PROGRAM-ID、
 * ジョブはジョブ名、BMS マップはマップセット名(修飾名の先頭要素)をファイル名の主部と照合する。
 *
 * 同名のファイルが別フォルダにある場合はどれが対応か決められないため、候補を1件へ絞らず
 * 相対パス昇順で全件返す。画面は相対パス全体を添えて開く先を選ばせ、先頭の候補を黙って開かない。
 * 対応が見つからないノードは空を返し、画面はソースを開く操作を出さない。
 */
export function graphSourcePaths(
  node: CallGraphNode,
  inventory: readonly AssetInventoryItem[],
): string[] {
  const name = sourceNameCandidate(node);
  if (name === null) {
    return [];
  }
  const target = name.toUpperCase();
  return inventory
    .filter((item) => baseName(item.name).toUpperCase() === target)
    .map((item) => item.path)
    .sort((left, right) => (left < right ? -1 : left > right ? 1 : 0));
}

function sourceNameCandidate(node: CallGraphNode): string | null {
  if (node.kind === "PROGRAM" || node.kind === "JOB") {
    return node.label;
  }
  if (node.kind === "BMS_MAP") {
    const separator = node.label.indexOf(".");
    return separator < 0 ? node.label : node.label.slice(0, separator);
  }
  return null;
}

/** ファイル名から拡張子を除いた主部。 */
function baseName(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot <= 0 ? fileName : fileName.slice(0, dot);
}

/** 解析不能ノードの件数。構文解析に失敗した資産の数を表す。 */
export function unanalyzableCount(data: CallGraphData): number {
  return data.nodes.filter((node) => node.kind === "UNANALYZABLE").length;
}

/**
 * 解析不能ノードが1件以上あるときに示す案内文。解析不能ノードは呼出関係が分からず孤立して
 * 図に現れるため、その旨と件数を利用者へ伝えて図が全体像でないことを正直に示す。0件は null。
 */
export function unanalyzableBanner(count: number): string | null {
  if (count <= 0) {
    return null;
  }
  return (
    `構文解析に失敗した資産が ${count} 件ある。呼出関係が分からないため、` +
    "図には「解析不能」ノードとして孤立させて示す。"
  );
}
