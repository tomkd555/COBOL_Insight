/**
 * Which nodes the call graph draws: the kind filters, the depth around a focus node, and the search.
 *
 * Drawing every node at once degrades badly on a real asset folder, so the graph starts from a set
 * of roots (the jobs and transactions) and reaches outward by a bounded number of hops. Edge
 * direction is ignored while reaching out: a dataset that feeds a step is as much a neighbour as one
 * the step writes.
 */

import type { GraphData } from "../../../shared/ipc";
import { NODE_KINDS, isGraphNodeKind, type GraphNodeKind } from "./graphLayout";

/** The depth slider's range. One hop is the focus and its immediate neighbours. */
export const DEPTH_LIMITS = { min: 1, max: 5, initial: 2 } as const;

export interface GraphFilter {
  /** Whether each node kind is drawn. */
  readonly kinds: Readonly<Record<GraphNodeKind, boolean>>;
  /** How many hops out from the focus (or the roots) are drawn. */
  readonly depth: number;
  /** The node the graph is centred on, or null for the roots. */
  readonly focusId: string | null;
  /** The label search. A non-empty search replaces the roots with what it matched. */
  readonly search: string;
}

export const INITIAL_GRAPH_FILTER: GraphFilter = {
  kinds: Object.fromEntries(NODE_KINDS.map((kind) => [kind, true])) as Record<
    GraphNodeKind,
    boolean
  >,
  depth: DEPTH_LIMITS.initial,
  focusId: null,
  search: "",
};

/** Flips one kind on or off. */
export function toggleKind(filter: GraphFilter, kind: GraphNodeKind): GraphFilter {
  return { ...filter, kinds: { ...filter.kinds, [kind]: !filter.kinds[kind] } };
}

/** Clamps the depth into the slider's range. */
export function withDepth(filter: GraphFilter, depth: number): GraphFilter {
  return { ...filter, depth: Math.min(DEPTH_LIMITS.max, Math.max(DEPTH_LIMITS.min, depth)) };
}

/** The nodes the graph draws at all, as ids. Asset rows are not call-graph nodes. */
function drawableIds(data: GraphData): Set<string> {
  return new Set(
    data.nodes.filter((node) => isGraphNodeKind(node.type)).map((node) => String(node.id)),
  );
}

/** Node id to the ids it touches, in either direction. */
function adjacency(data: GraphData, known: ReadonlySet<string>): Map<string, Set<string>> {
  const index = new Map<string, Set<string>>();
  const link = (from: string, to: string): void => {
    const set = index.get(from);
    if (set === undefined) {
      index.set(from, new Set([to]));
    } else {
      set.add(to);
    }
  };
  for (const edge of data.edges) {
    const from = String(edge.from);
    const to = String(edge.to);
    if (!known.has(from) || !known.has(to)) {
      continue;
    }
    link(from, to);
    link(to, from);
  }
  return index;
}

/**
 * Where the graph starts when nothing is focused: the jobs and the transactions. A folder holding
 * only COBOL has neither, so the nodes nothing points at stand in; a graph that is all cycles falls
 * back to every node, rather than drawing nothing at all.
 *
 * Nodes with no edge (an unanalysable asset, say) can never be reached by walking outward, so they
 * are always roots.
 */
export function graphRootIds(data: GraphData): string[] {
  const known = drawableIds(data);
  const index = adjacency(data, known);
  const isolated = [...known].filter((id) => (index.get(id)?.size ?? 0) === 0);
  const roots = data.nodes
    .filter((node) => node.type === "JOB" || node.type === "TRANSACTION")
    .map((node) => String(node.id))
    .filter((id) => known.has(id));
  if (roots.length > 0) {
    const chosen = new Set(roots);
    return [...roots, ...isolated.filter((id) => !chosen.has(id))];
  }
  const hasIncoming = new Set(
    data.edges.map((edge) => String(edge.to)).filter((id) => known.has(id)),
  );
  const sources = [...known].filter((id) => !hasIncoming.has(id));
  return sources.length > 0 ? sources : [...known];
}

/** The ids whose label contains the search text, case-insensitively. Empty search matches nothing. */
export function searchMatches(data: GraphData, search: string): string[] {
  const needle = search.trim().toLowerCase();
  if (needle === "") {
    return [];
  }
  return data.nodes
    .filter((node) => isGraphNodeKind(node.type) && node.label.toLowerCase().includes(needle))
    .map((node) => String(node.id));
}

/**
 * The ids to draw: a breadth-first walk of at most `depth` hops from the seeds, then the kind
 * filters. The seeds are the focus node when one is chosen, the search hits when something is being
 * searched for, and the roots otherwise.
 *
 * The kind filters are applied after the walk, so hiding a kind hides those nodes without cutting
 * the path through them.
 */
export function visibleNodeIds(data: GraphData, filter: GraphFilter): Set<string> {
  const known = drawableIds(data);
  const index = adjacency(data, known);
  const matches = searchMatches(data, filter.search);
  const seeds =
    filter.focusId !== null && known.has(filter.focusId)
      ? [filter.focusId]
      : matches.length > 0
        ? matches
        : graphRootIds(data);

  const reached = new Set<string>(seeds);
  let frontier = seeds;
  for (let hop = 0; hop < filter.depth && frontier.length > 0; hop += 1) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const neighbour of index.get(id) ?? []) {
        if (!reached.has(neighbour)) {
          reached.add(neighbour);
          next.push(neighbour);
        }
      }
    }
    frontier = next;
  }

  const visible = new Set<string>();
  for (const node of data.nodes) {
    const id = String(node.id);
    if (!reached.has(id) || !isGraphNodeKind(node.type) || !filter.kinds[node.type]) {
      continue;
    }
    visible.add(id);
  }
  return visible;
}

/** How many nodes of each kind the whole graph holds. The filter chips show these counts. */
export function nodeKindCounts(data: GraphData): Record<GraphNodeKind, number> {
  const counts = Object.fromEntries(NODE_KINDS.map((kind) => [kind, 0])) as Record<
    GraphNodeKind,
    number
  >;
  for (const node of data.nodes) {
    if (isGraphNodeKind(node.type)) {
      counts[node.type] += 1;
    }
  }
  return counts;
}
