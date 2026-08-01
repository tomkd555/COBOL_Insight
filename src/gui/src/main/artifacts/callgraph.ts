import type { CallGraphData, CallGraphEdge, CallGraphNode } from "../../shared/engine-api";

/**
 * call-graph サブコマンドの JSON(CallGraph.toJson)を、Cytoscape 等が消費できる形へ変換する
 * 純関数。nodes {id,kind,label,attributes}・edges {from,to,kind,resolution} を写す。attributes は
 * 省略時に空 Record とする。
 */
export function parseCallgraph(text: string): CallGraphData {
  const doc: unknown = JSON.parse(text);
  return {
    nodes: asArray(prop(doc, "nodes")).map(toNode),
    edges: asArray(prop(doc, "edges")).map(toEdge),
  };
}

function toNode(value: unknown): CallGraphNode {
  return {
    id: asString(prop(value, "id")) ?? "",
    kind: asString(prop(value, "kind")) ?? "",
    label: asString(prop(value, "label")) ?? "",
    attributes: toStringRecord(prop(value, "attributes")),
  };
}

function toEdge(value: unknown): CallGraphEdge {
  return {
    from: asString(prop(value, "from")) ?? "",
    to: asString(prop(value, "to")) ?? "",
    kind: asString(prop(value, "kind")) ?? "",
    resolution: asString(prop(value, "resolution")) ?? "",
  };
}

function toStringRecord(value: unknown): Record<string, string> {
  const record: Record<string, string> = {};
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
      const str = asString(raw);
      if (str !== undefined) {
        record[key] = str;
      }
    }
  }
  return record;
}

function prop(value: unknown, key: string): unknown {
  return value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}
