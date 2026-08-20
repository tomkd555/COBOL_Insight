/**
 * 実行順の一覧の純ロジック。走査済みプロジェクトファイルから読んだ呼出関係(GraphData)を、
 * ジョブから段落までを実行の順に辿れる木へ組み替える。
 *
 * 並びの根拠は engine が記録した seq である。同じ seq の辺は記録された並びのまま残し、この層で
 * 並べ替えない(順序を決められない辺の seq は 0 であり、順序の付いた辺の後ろへ回す)。
 *
 * GOTO は飛び先を示す印だけを置いて辿らない。PERFORM は戻る呼出なので入れ子に辿るが、経路上に
 * 同じ段落が現れたらそこで止める(自身を PERFORM する定義で無限に深くならないようにする)。
 *
 * 描画・DOM には触れない。図(Cytoscape)側へ渡す形への変換もここが担う。
 */

import type {
  AssetInventoryItem,
  CallGraphData,
  CallGraphEdge,
  CallGraphNode,
  GraphData,
  GraphEdge,
  GraphNode,
  GraphParagraph,
  GraphParagraphEdge,
} from "../../../shared/engine-api";
import { isGraphNodeKind } from "../screens/graph/graphModel";

/** 木の節の種類。 */
export type TraceKind =
  | "job"
  | "step"
  | "program"
  | "paragraph"
  | "perform"
  | "goto"
  | "unresolved";

export interface TraceNode {
  /** 木の中で一意な鍵。親の鍵に自分の位置を継ぎ足して作る。 */
  readonly id: string;
  readonly kind: TraceKind;
  readonly label: string;
  /** 開いたときに見せる行。分からなければ null。 */
  readonly line: number | null;
  /** 開く資産の相対パス。対応する資産を特定できなければ null。 */
  readonly path: string | null;
  /** 図で辿る NODE.id の並び(根からこの節まで)。段落の節は属するプログラムまでを持つ。 */
  readonly graphPath: readonly string[];
  readonly children: readonly TraceNode[];
  /** 経路上に同じ段落が再び現れたため、そこで辿るのをやめた節か。 */
  readonly cyclic: boolean;
}

/** 節の種類ごとの表示名。 */
export const TRACE_KIND_LABELS: Readonly<Record<TraceKind, string>> = {
  job: "ジョブ",
  step: "ステップ",
  program: "プログラム",
  paragraph: "段落",
  perform: "PERFORM",
  goto: "GOTO",
  unresolved: "未解決",
};

/** 実行順を決められない辺の seq。順序の付いた辺の後ろへ回す。 */
const UNORDERED_SEQ = 0;

/** 実行順で並べ替える。seq が同じ辺は元の並びのまま残す(Array#sort は安定である)。 */
function bySeq<T extends { readonly seq: number }>(edges: readonly T[]): T[] {
  return [...edges].sort((left, right) => {
    const leftUnordered = left.seq === UNORDERED_SEQ ? 1 : 0;
    const rightUnordered = right.seq === UNORDERED_SEQ ? 1 : 0;
    return leftUnordered !== rightUnordered
      ? leftUnordered - rightUnordered
      : left.seq - right.seq;
  });
}

/** 木を組む間だけ持つ索引。 */
interface TraceIndex {
  readonly nodeById: ReadonlyMap<string, GraphNode>;
  readonly edgesFrom: ReadonlyMap<string, readonly GraphEdge[]>;
  readonly paragraphsByProgram: ReadonlyMap<string, readonly GraphParagraph[]>;
  readonly paragraphById: ReadonlyMap<number, GraphParagraph>;
  readonly paragraphEdgesFrom: ReadonlyMap<number, readonly GraphParagraphEdge[]>;
  readonly inventory: readonly AssetInventoryItem[];
}

function pushInto<K, V>(map: Map<K, V[]>, key: K, value: V): void {
  const list = map.get(key);
  if (list === undefined) {
    map.set(key, [value]);
  } else {
    list.push(value);
  }
}

/** 拡張子を除いたファイル名。 */
function baseName(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot <= 0 ? fileName : fileName.slice(0, dot);
}

/**
 * 節に対応する資産の相対パス。ノード ID が資産の ID そのもの(NODE.id = SOURCE.id)であればそれを
 * 使い、そうでないグラフ層のノード(ジョブなど)は表示名をファイル名の主部と突き合わせる。
 * 同名の資産が複数あるときは、どれを指すか決められないため null を返す。
 */
function assetPathOf(index: TraceIndex, nodeId: string, label: string): string | null {
  const numericId = Number(nodeId);
  const byId = index.inventory.find((item) => item.id === numericId);
  if (byId !== undefined) {
    return byId.path;
  }
  const target = label.toUpperCase();
  const byName = index.inventory.filter(
    (item) => baseName(item.name).toUpperCase() === target,
  );
  return byName.length === 1 ? byName[0].path : null;
}

/**
 * 実行順の木を組む。根はジョブと、どのジョブ・どのプログラムからも呼ばれないプログラムである。
 * 呼出関係を持たない資産(コピー句・BMS・構文解析に失敗した資産)は実行の起点にならないため、
 * ここには現れない。
 */
export function buildTrace(
  graph: GraphData,
  inventory: readonly AssetInventoryItem[] = [],
): TraceNode[] {
  const nodes = graph.nodes.filter((node) => isGraphNodeKind(node.type));
  const nodeById = new Map(nodes.map((node) => [String(node.id), node]));

  const edgesFrom = new Map<string, GraphEdge[]>();
  const executedOrCalled = new Set<string>();
  for (const edge of graph.edges) {
    const from = String(edge.from);
    const to = String(edge.to);
    if (!nodeById.has(from) || !nodeById.has(to)) {
      continue;
    }
    pushInto(edgesFrom, from, edge);
    if (edge.kind === "EXECUTION" || edge.kind === "CALL") {
      executedOrCalled.add(to);
    }
  }

  const paragraphsByProgram = new Map<string, GraphParagraph[]>();
  for (const paragraph of graph.paragraphs) {
    pushInto(paragraphsByProgram, String(paragraph.programSourceId), paragraph);
  }
  for (const list of paragraphsByProgram.values()) {
    list.sort((left, right) => left.startLine - right.startLine);
  }
  const paragraphEdgesFrom = new Map<number, GraphParagraphEdge[]>();
  for (const edge of graph.paragraphEdges) {
    pushInto(paragraphEdgesFrom, edge.from, edge);
  }

  const index: TraceIndex = {
    nodeById,
    edgesFrom,
    paragraphsByProgram,
    paragraphById: new Map(graph.paragraphs.map((paragraph) => [paragraph.id, paragraph])),
    paragraphEdgesFrom,
    inventory,
  };

  const roots: TraceNode[] = [];
  for (const node of nodes) {
    const id = String(node.id);
    if (node.type === "JOB") {
      roots.push(jobNode(index, node));
    } else if (node.type === "PROGRAM" && !executedOrCalled.has(id)) {
      roots.push(programNode(index, node, `entry:${id}`, []));
    }
  }
  return roots;
}

/** ジョブ1件。子は EXEC PGM= のステップで、JCL に書かれた順に並ぶ。 */
function jobNode(index: TraceIndex, node: GraphNode): TraceNode {
  const id = String(node.id);
  const jobId = `job:${id}`;
  const path = assetPathOf(index, id, node.label);
  const steps = bySeq(
    (index.edgesFrom.get(id) ?? []).filter((edge) => edge.kind === "EXECUTION"),
  );
  return {
    id: jobId,
    kind: "job",
    label: node.label,
    line: null,
    path,
    graphPath: [id],
    cyclic: false,
    children: steps.map((edge, position) =>
      stepNode(index, edge, `${jobId}/${position}`, [id], path),
    ),
  };
}

/** ステップ1件。開く先は、そのステップを書いた JCL の行である。 */
function stepNode(
  index: TraceIndex,
  edge: GraphEdge,
  id: string,
  graphPath: readonly string[],
  jclPath: string | null,
): TraceNode {
  const target = String(edge.to);
  const node = index.nodeById.get(target);
  const nextGraphPath = [...graphPath, target];
  const executed = bySeq(
    (index.edgesFrom.get(target) ?? []).filter((next) => next.kind === "EXECUTION"),
  );
  return {
    id,
    kind: "step",
    label: node?.label ?? target,
    line: edge.line,
    path: jclPath,
    graphPath: nextGraphPath,
    cyclic: false,
    children: executed.map((next, position) => {
      const program = index.nodeById.get(String(next.to));
      return program === undefined
        ? unknownNode(`${id}/${position}`, String(next.to), nextGraphPath)
        : programNode(index, program, `${id}/${position}`, nextGraphPath);
    }),
  };
}

/** ステップの呼出先が一覧に無いとき。図に無いものを黙って消さず、未解決として示す。 */
function unknownNode(id: string, label: string, graphPath: readonly string[]): TraceNode {
  return {
    id,
    kind: "unresolved",
    label,
    line: null,
    path: null,
    graphPath,
    children: [],
    cyclic: false,
  };
}

/** プログラム1件。子は段落の連なりである。 */
function programNode(
  index: TraceIndex,
  node: GraphNode,
  id: string,
  graphPath: readonly string[],
): TraceNode {
  const nodeId = String(node.id);
  const path = assetPathOf(index, nodeId, node.label);
  return {
    id,
    kind: "program",
    label: node.label,
    // 呼び出した行は JCL 側の行であり、開く先はこのプログラムの原本である。行は段落が持つ。
    line: null,
    path,
    graphPath: [...graphPath, nodeId],
    cyclic: false,
    children: paragraphChain(index, nodeId, [...graphPath, nodeId], path, id),
  };
}

/**
 * 段落の連なり。先頭の段落から FALLTHROUGH を辿って並べ、流れのどこにも現れなかった段落は
 * 開始行の順で後ろへ続ける(engine が流れを記録できなかった段落を一覧から落とさないため)。
 *
 * 連なりの打ち切りは FALLTHROUGH で戻ってきた段落だけを見る。PERFORM で先に現れた段落が
 * 物理的な次の段落でもある形はよくあり、それで連なりを止めると本体の大半が消える。
 */
function paragraphChain(
  index: TraceIndex,
  programNodeId: string,
  graphPath: readonly string[],
  path: string | null,
  parentId: string,
): TraceNode[] {
  const paragraphs = index.paragraphsByProgram.get(programNodeId) ?? [];
  const chainVisited = new Set<number>();
  const reached = new Set<number>();
  const chain: TraceNode[] = [];
  let current: GraphParagraph | undefined = paragraphs[0];
  while (current !== undefined && !chainVisited.has(current.id)) {
    chainVisited.add(current.id);
    chain.push(paragraphNode(index, current, graphPath, path, parentId, chain.length, reached));
    const fallthrough: GraphParagraphEdge | undefined = bySeq(
      index.paragraphEdgesFrom.get(current.id) ?? [],
    ).find((edge) => edge.kind === "FALLTHROUGH");
    current =
      fallthrough === undefined || fallthrough.to === null
        ? undefined
        : index.paragraphById.get(fallthrough.to);
  }
  for (const paragraph of paragraphs) {
    if (chainVisited.has(paragraph.id) || reached.has(paragraph.id)) {
      continue;
    }
    chainVisited.add(paragraph.id);
    chain.push(paragraphNode(index, paragraph, graphPath, path, parentId, chain.length, reached));
  }
  return chain;
}

function paragraphNode(
  index: TraceIndex,
  paragraph: GraphParagraph,
  graphPath: readonly string[],
  path: string | null,
  parentId: string,
  position: number,
  reached: Set<number>,
): TraceNode {
  const id = `${parentId}/p${position}`;
  reached.add(paragraph.id);
  return {
    id,
    kind: "paragraph",
    label: paragraph.name,
    line: paragraph.startLine,
    path,
    graphPath,
    cyclic: false,
    children: branchNodes(
      index,
      paragraph.id,
      graphPath,
      path,
      id,
      new Set([paragraph.id]),
      reached,
    ),
  };
}

/**
 * 段落から出る PERFORM と GOTO。PERFORM は行き先の段落を入れ子に辿り、GOTO は飛び先の印だけを
 * 置く。行き先を名前で解決できなかった辺は未解決として示す。
 */
function branchNodes(
  index: TraceIndex,
  paragraphId: number,
  graphPath: readonly string[],
  path: string | null,
  parentId: string,
  ancestors: ReadonlySet<number>,
  reached: Set<number>,
): TraceNode[] {
  const nodes: TraceNode[] = [];
  const edges = bySeq(index.paragraphEdgesFrom.get(paragraphId) ?? []);
  for (const edge of edges) {
    if (edge.kind === "FALLTHROUGH") {
      continue;
    }
    const id = `${parentId}/${nodes.length}`;
    const target = edge.to === null ? undefined : index.paragraphById.get(edge.to);
    if (target === undefined) {
      nodes.push({
        id,
        kind: "unresolved",
        label: edge.toName,
        line: edge.line,
        path,
        graphPath,
        children: [],
        cyclic: false,
      });
      continue;
    }
    reached.add(target.id);
    if (edge.kind === "GOTO") {
      nodes.push({
        id,
        kind: "goto",
        label: target.name,
        line: target.startLine,
        path,
        graphPath,
        children: [],
        cyclic: false,
      });
      continue;
    }
    const repeated = ancestors.has(target.id);
    nodes.push({
      id,
      kind: "perform",
      label: target.name,
      line: target.startLine,
      path,
      graphPath,
      cyclic: repeated,
      children: repeated
        ? []
        : branchNodes(
            index,
            target.id,
            graphPath,
            path,
            id,
            new Set([...ancestors, target.id]),
            reached,
          ),
    });
  }
  return nodes;
}

/** 一覧へ並べる1行。木の入れ子は depth が表す。 */
export interface TraceRow {
  readonly node: TraceNode;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
}

/** 開いている節の下だけを辿って、木を行の並びへ均す。 */
export function flattenTrace(
  roots: readonly TraceNode[],
  expanded: Readonly<Record<string, boolean>>,
): TraceRow[] {
  const rows: TraceRow[] = [];
  const walk = (nodes: readonly TraceNode[], depth: number): void => {
    for (const node of nodes) {
      const hasChildren = node.children.length > 0;
      const open = hasChildren && expanded[node.id] === true;
      rows.push({ node, depth, hasChildren, expanded: open });
      if (open) {
        walk(node.children, depth + 1);
      }
    }
  };
  walk(roots, 0);
  return rows;
}

/**
 * 図(Cytoscape)へ渡す形。図はノード ID を文字列で扱うため、NODE.id を文字列へ置き換える。
 * 資産そのものを表す行(コピー句・JCL・BMS)は呼出関係のノードではないため外す。
 * 記録の無い解決根拠は、破線にしないよう定数由来として扱う。
 */
export function toCallGraphData(graph: GraphData): CallGraphData {
  const kept = new Set<string>();
  const nodes: CallGraphNode[] = [];
  for (const node of graph.nodes) {
    if (!isGraphNodeKind(node.type)) {
      continue;
    }
    kept.add(String(node.id));
    nodes.push({ id: String(node.id), kind: node.type, label: node.label, attributes: {} });
  }
  const seen = new Set<string>();
  const edges: CallGraphEdge[] = [];
  for (const edge of graph.edges) {
    const from = String(edge.from);
    const to = String(edge.to);
    if (!kept.has(from) || !kept.has(to)) {
      continue;
    }
    const resolution = edge.resolution ?? "CONSTANT";
    // 同じ組の辺は図では 1 本である(実行順の違いは実行順の一覧が示す)。
    const key = `${from}|${to}|${edge.kind}|${resolution}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    edges.push({ from, to, kind: edge.kind, resolution });
  }
  return { nodes, edges };
}
