/**
 * The execution-order tree: the call graph rearranged into what runs after what, from a job down to
 * a paragraph.
 *
 * The order comes from the `seq` the engine recorded — the JCL step order, and the order the
 * statements appear in. Edges that share a seq keep the order they were read in, and edges whose
 * order could not be established (seq 0) go last.
 *
 * PERFORM returns, so it is followed into a nested level; a paragraph already on the path stops the
 * descent, so a paragraph that performs itself does not recurse forever. GOTO does not return and is
 * only marked, never followed.
 */

import type {
  AssetInventoryItem,
  GraphData,
  GraphEdge,
  GraphNode,
  GraphParagraph,
  GraphParagraphEdge,
} from "../../../shared/ipc";
import { isGraphNodeKind } from "./graphLayout";
import { nodeAssetPath } from "./graphDetail";

export type TraceKind = "job" | "step" | "program" | "paragraph" | "perform" | "goto" | "unresolved";

export interface TraceNode {
  /** Unique within the tree: the parent's key with this node's position appended. */
  readonly id: string;
  readonly kind: TraceKind;
  readonly label: string;
  /** The line to reveal when the row is opened, or null. */
  readonly line: number | null;
  /** The asset to open, relative to the asset folder; null when none can be named. */
  readonly path: string | null;
  /** The call-graph node this row stands for, so selecting the row can focus the canvas. */
  readonly graphId: string | null;
  readonly children: readonly TraceNode[];
  /** Whether the descent stopped here because the paragraph was already on the path. */
  readonly cyclic: boolean;
}

/** The seq of an edge whose execution order could not be established. It sorts last. */
const UNORDERED_SEQ = 0;

/** Sorts by execution order. Array#sort is stable, so equal seq keeps the recorded order. */
function bySeq<T extends { readonly seq: number }>(edges: readonly T[]): T[] {
  return [...edges].sort((left, right) => {
    const leftLast = left.seq === UNORDERED_SEQ ? 1 : 0;
    const rightLast = right.seq === UNORDERED_SEQ ? 1 : 0;
    return leftLast !== rightLast ? leftLast - rightLast : left.seq - right.seq;
  });
}

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

/**
 * Builds the tree. Its roots are the jobs, plus the programs no job and no other program starts —
 * an entry point the analysis found no caller for. Assets with no call relationship (copybooks, BMS
 * maps, assets that failed to parse) never start an execution and do not appear.
 */
export function buildTrace(
  graph: GraphData,
  inventory: readonly AssetInventoryItem[] = [],
): TraceNode[] {
  const nodes = graph.nodes.filter((node) => isGraphNodeKind(node.type));
  const nodeById = new Map(nodes.map((node) => [String(node.id), node]));

  const edgesFrom = new Map<string, GraphEdge[]>();
  const started = new Set<string>();
  for (const edge of graph.edges) {
    const from = String(edge.from);
    const to = String(edge.to);
    if (!nodeById.has(from) || !nodeById.has(to)) {
      continue;
    }
    pushInto(edgesFrom, from, edge);
    if (edge.kind === "EXECUTION" || edge.kind === "CALL") {
      started.add(to);
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
    } else if (node.type === "PROGRAM" && !started.has(id)) {
      roots.push(programNode(index, node, `entry:${id}`));
    }
  }
  return roots;
}

/** One job. Its children are the EXEC PGM steps, in the order the JCL writes them. */
function jobNode(index: TraceIndex, node: GraphNode): TraceNode {
  const id = String(node.id);
  const key = `job:${id}`;
  const path = nodeAssetPath(node, index.inventory);
  const steps = bySeq((index.edgesFrom.get(id) ?? []).filter((edge) => edge.kind === "EXECUTION"));
  return {
    id: key,
    kind: "job",
    label: node.label,
    line: null,
    path,
    graphId: id,
    cyclic: false,
    children: steps.map((edge, position) => stepNode(index, edge, `${key}/${position}`, path)),
  };
}

/** One step. Its line is the EXEC statement's line in the JCL. */
function stepNode(
  index: TraceIndex,
  edge: GraphEdge,
  id: string,
  jclPath: string | null,
): TraceNode {
  const target = String(edge.to);
  const node = index.nodeById.get(target);
  const executed = bySeq(
    (index.edgesFrom.get(target) ?? []).filter((next) => next.kind === "EXECUTION"),
  );
  return {
    id,
    kind: "step",
    label: node?.label ?? target,
    line: edge.line,
    path: jclPath,
    graphId: target,
    cyclic: false,
    children: executed.map((next, position) => {
      const program = index.nodeById.get(String(next.to));
      return program === undefined
        ? unknownNode(`${id}/${position}`, String(next.to))
        : programNode(index, program, `${id}/${position}`);
    }),
  };
}

/** A target the node table does not hold. It is shown as unresolved rather than dropped. */
function unknownNode(id: string, label: string): TraceNode {
  return {
    id,
    kind: "unresolved",
    label,
    line: null,
    path: null,
    graphId: null,
    children: [],
    cyclic: false,
  };
}

/** One program. Its children are the chain of its paragraphs. */
function programNode(index: TraceIndex, node: GraphNode, id: string): TraceNode {
  const nodeId = String(node.id);
  const path = nodeAssetPath(node, index.inventory);
  return {
    id,
    kind: "program",
    label: node.label,
    // The calling line belongs to the JCL; the program opens at its top and its paragraphs carry
    // the lines inside it.
    line: null,
    path,
    graphId: nodeId,
    cyclic: false,
    children: paragraphChain(index, nodeId, path, id),
  };
}

/**
 * The chain of paragraphs: from the first one, following FALLTHROUGH, then every paragraph the flow
 * never reached, in line order, so a paragraph whose flow the engine could not record is still
 * listed.
 *
 * Only FALLTHROUGH closes the chain. A paragraph reached earlier by a PERFORM is very often also the
 * physically next one, and stopping there would drop most of the program.
 */
function paragraphChain(
  index: TraceIndex,
  programNodeId: string,
  path: string | null,
  parentId: string,
): TraceNode[] {
  const paragraphs = index.paragraphsByProgram.get(programNodeId) ?? [];
  const listed = new Set<number>();
  const reached = new Set<number>();
  const chain: TraceNode[] = [];
  let current: GraphParagraph | undefined = paragraphs[0];
  while (current !== undefined && !listed.has(current.id)) {
    listed.add(current.id);
    chain.push(paragraphNode(index, current, path, parentId, chain.length, reached));
    const fallthrough: GraphParagraphEdge | undefined = bySeq(
      index.paragraphEdgesFrom.get(current.id) ?? [],
    ).find((edge) => edge.kind === "FALLTHROUGH");
    current =
      fallthrough === undefined || fallthrough.to === null
        ? undefined
        : index.paragraphById.get(fallthrough.to);
  }
  for (const paragraph of paragraphs) {
    if (listed.has(paragraph.id) || reached.has(paragraph.id)) {
      continue;
    }
    listed.add(paragraph.id);
    chain.push(paragraphNode(index, paragraph, path, parentId, chain.length, reached));
  }
  return chain;
}

function paragraphNode(
  index: TraceIndex,
  paragraph: GraphParagraph,
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
    graphId: null,
    cyclic: false,
    children: branchNodes(index, paragraph.id, path, id, new Set([paragraph.id]), reached),
  };
}

/**
 * The PERFORM and GOTO edges leaving one paragraph. PERFORM descends into its target; GOTO is only
 * marked. An edge whose target name could not be resolved is shown as unresolved.
 */
function branchNodes(
  index: TraceIndex,
  paragraphId: number,
  path: string | null,
  parentId: string,
  ancestors: ReadonlySet<number>,
  reached: Set<number>,
): TraceNode[] {
  const nodes: TraceNode[] = [];
  for (const edge of bySeq(index.paragraphEdgesFrom.get(paragraphId) ?? [])) {
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
        graphId: null,
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
        graphId: null,
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
      graphId: null,
      cyclic: repeated,
      children: repeated
        ? []
        : branchNodes(index, target.id, path, id, new Set([...ancestors, target.id]), reached),
    });
  }
  return nodes;
}

/** One row of the flattened tree. The nesting is carried by `depth`. */
export interface TraceRow {
  readonly node: TraceNode;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
}

/** Flattens the tree into rows, descending only through the nodes that are open. */
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

/** The roots open, so the first steps of every job are in view without a click. */
export function initialExpanded(roots: readonly TraceNode[]): Record<string, boolean> {
  return Object.fromEntries(roots.map((root) => [root.id, true]));
}
