/**
 * Turning the call graph into cytoscape elements, styles and layout options.
 *
 * Node kinds are distinguished by shape as well as colour, so the graph stays readable without
 * colour vision; edges whose target was resolved by dataflow, or not resolved at all, are dashed so
 * a guess is never drawn as a certainty. Nothing here touches the DOM or the cytoscape runtime: the
 * canvas component owns those.
 */

import type cytoscape from "cytoscape";
import type { GraphData, GraphEdge } from "../../../shared/ipc";

/**
 * The node kinds the graph draws: the engine's ten NodeKind values plus UNANALYZABLE, which the
 * engine's enum does not carry and which marks an asset whose parse failed.
 *
 * NODE also holds rows for the assets themselves (JCL, COPYBOOK, BMS). Those are not call-graph
 * nodes and are left out of the drawing; `isGraphNodeKind` is the test.
 */
export type GraphNodeKind =
  | "JOB"
  | "STEP"
  | "PROGRAM"
  | "PARAGRAPH"
  | "DATASET"
  | "DB2_TABLE"
  | "TRANSACTION"
  | "BMS_MAP"
  | "EXTERNAL_UTILITY"
  | "UNRESOLVED"
  | "UNANALYZABLE";

/** How one node kind is drawn. */
export interface NodeKindStyle {
  readonly kind: GraphNodeKind;
  readonly shape: cytoscape.Css.NodeShape;
  readonly background: string;
  readonly border: string;
  readonly borderStyle: "solid" | "dashed";
}

/**
 * The node kinds in legend and filter order: batch from upstream to downstream, then online, then
 * the external, unresolved and unanalysable ends. The colours are the dark theme's kind palette.
 */
export const NODE_KIND_STYLES: readonly NodeKindStyle[] = [
  { kind: "JOB", shape: "hexagon", background: "#243347", border: "#4daafc", borderStyle: "solid" },
  { kind: "STEP", shape: "round-rectangle", background: "#22303f", border: "#3794ff", borderStyle: "solid" },
  { kind: "PROGRAM", shape: "rectangle", background: "#26332f", border: "#4ec9b0", borderStyle: "solid" },
  { kind: "PARAGRAPH", shape: "ellipse", background: "#2b2b2b", border: "#8b8b8b", borderStyle: "solid" },
  { kind: "DATASET", shape: "barrel", background: "#33301f", border: "#dcdcaa", borderStyle: "solid" },
  { kind: "DB2_TABLE", shape: "cut-rectangle", background: "#1f3327", border: "#6fc28b", borderStyle: "solid" },
  { kind: "TRANSACTION", shape: "octagon", background: "#312a3a", border: "#c586c0", borderStyle: "solid" },
  { kind: "BMS_MAP", shape: "rhomboid", background: "#22303a", border: "#9cdcfe", borderStyle: "solid" },
  { kind: "EXTERNAL_UTILITY", shape: "tag", background: "#2b2b2b", border: "#b0b0b0", borderStyle: "solid" },
  { kind: "UNRESOLVED", shape: "diamond", background: "#3a2323", border: "#f14c4c", borderStyle: "dashed" },
  { kind: "UNANALYZABLE", shape: "star", background: "#332b1f", border: "#cca700", borderStyle: "solid" },
];

/** The node kinds in legend and filter order. */
export const NODE_KINDS: readonly GraphNodeKind[] = NODE_KIND_STYLES.map((style) => style.kind);

const NODE_KIND_STYLE_BY_KIND = new Map<string, NodeKindStyle>(
  NODE_KIND_STYLES.map((style) => [style.kind, style]),
);

/** Whether the NODE.type is one the graph draws. Asset rows (JCL, COPYBOOK, BMS) are not. */
export function isGraphNodeKind(type: string): type is GraphNodeKind {
  return NODE_KIND_STYLE_BY_KIND.has(type);
}

/** How one edge kind is drawn: colour plus arrowhead, so the kind survives a colour-blind reading. */
export interface EdgeKindStyle {
  readonly kind: string;
  readonly color: string;
  readonly arrowShape: cytoscape.Css.ArrowShape;
}

/** The edge kinds the engine records (EdgeKind), in legend order. */
export const EDGE_KIND_STYLES: readonly EdgeKindStyle[] = [
  { kind: "EXECUTION", color: "#8b8b8b", arrowShape: "triangle" },
  { kind: "CALL", color: "#4daafc", arrowShape: "vee" },
  { kind: "REFERENCE", color: "#dcdcaa", arrowShape: "square" },
  { kind: "TRANSACTION_TRANSITION", color: "#c586c0", arrowShape: "diamond" },
  { kind: "MAP_REFERENCE", color: "#9cdcfe", arrowShape: "circle" },
];

const EDGE_KIND_STYLE_BY_KIND = new Map<string, EdgeKindStyle>(
  EDGE_KIND_STYLES.map((style) => [style.kind, style]),
);

/** An unknown edge kind is drawn in grey rather than dropped: no result is hidden. */
export function edgeKindStyle(kind: string): EdgeKindStyle {
  return EDGE_KIND_STYLE_BY_KIND.get(kind) ?? { kind, color: "#8b8b8b", arrowShape: "tee" };
}

/** Whether the edge is drawn dashed: a dataflow-derived or unresolved target is not a certainty. */
export function isDashedEdge(resolution: string | null): boolean {
  return resolution === "DATAFLOW" || resolution === "UNRESOLVED";
}

/** The cytoscape classes the stylesheet keys off. */
export const DASHED_EDGE_CLASS = "ci-edge-dashed";
export const SELECTED_NODE_CLASS = "ci-node-selected";

export interface GraphNodeElement {
  readonly group: "nodes";
  readonly data: {
    readonly id: string;
    readonly label: string;
    readonly kind: string;
  };
}

export interface GraphEdgeElement {
  readonly group: "edges";
  readonly data: {
    readonly id: string;
    readonly source: string;
    readonly target: string;
    readonly kind: string;
  };
  readonly classes?: string;
}

export type GraphElement = GraphNodeElement | GraphEdgeElement;

/** The element id of an edge. The engine keeps at most one edge per (from, to, kind, resolution). */
function edgeElementId(edge: GraphEdge): string {
  return `${edge.from}|${edge.to}|${edge.kind}|${edge.resolution ?? ""}`;
}

/**
 * The cytoscape elements for the visible node set. An edge is kept only when both of its ends are
 * visible, and repeated edges collapse into one line: the execution order of the repeats is what the
 * execution-order tree and the detail pane show, not the drawing.
 */
export function buildGraphElements(
  data: GraphData,
  visibleIds: ReadonlySet<string>,
): GraphElement[] {
  const elements: GraphElement[] = [];
  for (const node of data.nodes) {
    const id = String(node.id);
    if (!visibleIds.has(id) || !isGraphNodeKind(node.type)) {
      continue;
    }
    elements.push({ group: "nodes", data: { id, label: node.label, kind: node.type } });
  }
  const seen = new Set<string>();
  for (const edge of data.edges) {
    const source = String(edge.from);
    const target = String(edge.to);
    if (!visibleIds.has(source) || !visibleIds.has(target)) {
      continue;
    }
    const id = edgeElementId(edge);
    if (seen.has(id)) {
      continue;
    }
    seen.add(id);
    elements.push({
      group: "edges",
      data: { id, source, target, kind: edge.kind },
      ...(isDashedEdge(edge.resolution) ? { classes: DASHED_EDGE_CLASS } : {}),
    });
  }
  return elements;
}

/**
 * The cytoscape stylesheet. Cytoscape draws to a canvas and does not resolve CSS custom properties,
 * so the token values are written out here.
 */
export function graphStylesheet(): cytoscape.StylesheetJsonBlock[] {
  return [
    {
      selector: "node",
      style: {
        shape: "rectangle",
        "background-color": "#2b2b2b",
        "border-width": 1.5,
        "border-color": "#8b8b8b",
        "border-style": "dashed",
        label: "data(label)",
        "font-family": "'Cascadia Mono','Consolas','MS Gothic',monospace",
        "font-size": 11,
        "font-weight": "bold",
        color: "#e6e6e6",
        "text-valign": "center",
        "text-halign": "center",
        "text-wrap": "ellipsis",
        "text-max-width": "150px",
        width: "label",
        height: 34,
        padding: "8px",
      },
    },
    ...NODE_KIND_STYLES.map(
      (style): cytoscape.StylesheetJsonBlock => ({
        selector: `node[kind="${style.kind}"]`,
        style: {
          shape: style.shape,
          "background-color": style.background,
          "border-color": style.border,
          "border-style": style.borderStyle,
        },
      }),
    ),
    {
      selector: "edge",
      style: {
        width: 1.4,
        "line-color": "#8b8b8b",
        "target-arrow-color": "#8b8b8b",
        "target-arrow-shape": "triangle",
        "arrow-scale": 0.8,
        "curve-style": "bezier",
        opacity: 0.9,
      },
    },
    ...EDGE_KIND_STYLES.map(
      (style): cytoscape.StylesheetJsonBlock => ({
        selector: `edge[kind="${style.kind}"]`,
        style: {
          "line-color": style.color,
          "target-arrow-color": style.color,
          "target-arrow-shape": style.arrowShape,
        },
      }),
    ),
    { selector: `edge.${DASHED_EDGE_CLASS}`, style: { "line-style": "dashed" } },
    {
      selector: `node.${SELECTED_NODE_CLASS}`,
      style: { "border-width": 3.5, "border-color": "#0078d4", "border-style": "solid" },
    },
  ];
}

/**
 * The ceiling on the zoom `fit` may reach. maxZoom belongs to the core, not to the layout: a graph
 * of two nodes would otherwise be blown up until one node filled the viewport.
 */
export const GRAPH_MAX_ZOOM = 1;

export interface GraphCoreOptions {
  readonly container: HTMLElement;
  readonly style: cytoscape.StylesheetJsonBlock[];
  readonly autoungrabify: boolean;
  readonly maxZoom: number;
}

/** The core options. Dragging a node is not allowed, so the layers the layout chose keep their order. */
export function graphCoreOptions(container: HTMLElement): GraphCoreOptions {
  return {
    container,
    style: graphStylesheet(),
    autoungrabify: true,
    maxZoom: GRAPH_MAX_ZOOM,
  };
}

export interface GraphLayoutOptions {
  readonly name: "elk";
  readonly nodeDimensionsIncludeLabels: boolean;
  readonly fit: boolean;
  readonly padding: number;
  readonly elk: Readonly<Record<string, string | number>>;
}

/**
 * The layered layout: job to step to program to dataset, laid out left to right. The same options
 * are handed to elkjs directly by the layout timing test.
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
