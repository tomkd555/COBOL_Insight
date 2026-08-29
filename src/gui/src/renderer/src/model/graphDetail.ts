/**
 * What the detail pane shows for the selected node: its incoming and outgoing edges with the
 * execution order (`seq`) and the calling line (`line`) the engine recorded, and the asset the node
 * corresponds to, when one can be named.
 *
 * The edges keep the order they were read in, which is already ordered by seq within each origin;
 * this layer does not reorder them.
 */

import type { AssetInventoryItem, GraphData, GraphEdge, GraphNode } from "../../../shared/ipc";

/** One edge as the detail pane lists it, from the selected node's point of view. */
export interface GraphEdgeDetail {
  readonly peerId: string;
  readonly peerLabel: string;
  readonly kind: string;
  readonly resolution: string | null;
  /** Execution order among the edges leaving the origin; 0 when no order applies. */
  readonly seq: number;
  readonly line: number | null;
}

export interface GraphNodeDetail {
  readonly id: string;
  readonly node: GraphNode;
  readonly incoming: readonly GraphEdgeDetail[];
  readonly outgoing: readonly GraphEdgeDetail[];
  /** The asset to open for this node, relative to the asset folder; null when none can be named. */
  readonly path: string | null;
}

/** A file name without its extension. */
function baseName(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot <= 0 ? fileName : fileName.slice(0, dot);
}

/**
 * The asset a node stands for. A node whose id is an asset id (NODE.id = SOURCE.id) names it
 * outright; a graph-layer node (a job, a BMS map) is matched by label against the file name. Two
 * assets of the same name in different folders cannot be told apart, so neither is opened.
 */
export function nodeAssetPath(
  node: GraphNode,
  inventory: readonly AssetInventoryItem[],
): string | null {
  const byId = inventory.find((item) => item.id === node.id);
  if (byId !== undefined) {
    return byId.path;
  }
  // A qualified BMS map name (MAPSET.MAP) is filed under its map set.
  const separator = node.label.indexOf(".");
  const name = separator < 0 ? node.label : node.label.slice(0, separator);
  const target = name.toUpperCase();
  const byName = inventory.filter((item) => baseName(item.name).toUpperCase() === target);
  return byName.length === 1 ? byName[0].path : null;
}

function edgeDetail(peerId: string, peerLabel: string, edge: GraphEdge): GraphEdgeDetail {
  return {
    peerId,
    peerLabel,
    kind: edge.kind,
    resolution: edge.resolution,
    seq: edge.seq,
    line: edge.line,
  };
}

/** The selected node's detail, or null when the id names no node. */
export function nodeDetail(
  data: GraphData,
  id: string,
  inventory: readonly AssetInventoryItem[] = [],
): GraphNodeDetail | null {
  const node = data.nodes.find((candidate) => String(candidate.id) === id);
  if (node === undefined) {
    return null;
  }
  const labelById = new Map(data.nodes.map((entry) => [String(entry.id), entry.label]));
  const labelOf = (peerId: string): string => labelById.get(peerId) ?? peerId;
  const incoming: GraphEdgeDetail[] = [];
  const outgoing: GraphEdgeDetail[] = [];
  for (const edge of data.edges) {
    const from = String(edge.from);
    const to = String(edge.to);
    if (to === id) {
      incoming.push(edgeDetail(from, labelOf(from), edge));
    }
    if (from === id) {
      outgoing.push(edgeDetail(to, labelOf(to), edge));
    }
  }
  return { id, node, incoming, outgoing, path: nodeAssetPath(node, inventory) };
}
