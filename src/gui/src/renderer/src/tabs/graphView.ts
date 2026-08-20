/**
 * 呼出関係タブの見え方。種別フィルタ・部分展開・選択中ノードを持つ。
 *
 * projectStore が値として保つため、この型と初期値は store から独立した場所に置く
 * (store が図の部品へ依存すると、状態の定義が描画の都合に引きずられる)。
 */

import { NODE_KINDS, type AnyNodeKind } from "../screens/graph/graphModel";

export interface GraphViewState {
  /** ノード種別ごとの表示・非表示。11 種すべてを鍵に持つ。 */
  readonly kinds: Readonly<Record<AnyNodeKind, boolean>>;
  /** 隣接を展開したノード ID。値が真のものだけを展開として扱う。 */
  readonly expanded: Readonly<Record<string, boolean>>;
  /** 選択中のノード ID。未選択は null。 */
  readonly selected: string | null;
  /** 右のノード情報と凡例のペインを畳んでいるか。 */
  readonly detailCollapsed: boolean;
}

/** 既定の見え方。全種別を表示し、展開は起点からの部分展開に任せる。 */
export const INITIAL_GRAPH_VIEW: GraphViewState = {
  kinds: Object.fromEntries(NODE_KINDS.map((kind) => [kind, true])) as Record<AnyNodeKind, boolean>,
  expanded: {},
  selected: null,
  detailCollapsed: false,
};

/** 種別1つの表示・非表示を反転する。 */
export function toggleKind(view: GraphViewState, kind: AnyNodeKind): GraphViewState {
  return { ...view, kinds: { ...view.kinds, [kind]: !view.kinds[kind] } };
}

/** ノード1つの展開を反転する。 */
export function toggleExpanded(view: GraphViewState, id: string): GraphViewState {
  return { ...view, expanded: { ...view.expanded, [id]: view.expanded[id] !== true } };
}

/**
 * 実行順の一覧で選んだ経路を図へ映す。経路の途中のノードをすべて展開してから末端を選ぶことで、
 * 選んだノードが図に現れないまま選択だけが変わる状態を避ける。
 */
export function selectPath(view: GraphViewState, path: readonly string[]): GraphViewState {
  if (path.length === 0) {
    return view;
  }
  const expanded: Record<string, boolean> = { ...view.expanded };
  for (const id of path.slice(0, -1)) {
    expanded[id] = true;
  }
  return { ...view, expanded, selected: path[path.length - 1] };
}
