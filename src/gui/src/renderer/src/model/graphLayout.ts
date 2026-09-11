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
import { CODE_FONT, type ThemeName } from "../vendor/monarch";

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

/** A colour pair: the dark theme's value first, then the light theme's. */
type Pair = readonly [dark: string, light: string];

/** The node kinds in legend and filter order: batch upstream to downstream, then online, then ends. */
const NODE_KIND_SHAPES: readonly {
  kind: GraphNodeKind;
  shape: cytoscape.Css.NodeShape;
  fill: Pair;
  line: Pair;
  borderStyle: "solid" | "dashed";
}[] = [
  { kind: "JOB", shape: "hexagon", fill: ["#243347", "#dbe7fb"], line: ["#6aa7ff", "#1f4fa8"], borderStyle: "solid" },
  { kind: "STEP", shape: "round-rectangle", fill: ["#22303f", "#e3ecfa"], line: ["#4f8fe6", "#3565b3"], borderStyle: "solid" },
  { kind: "PROGRAM", shape: "rectangle", fill: ["#26332f", "#dcf1ea"], line: ["#4ec9b0", "#176f5d"], borderStyle: "solid" },
  { kind: "PARAGRAPH", shape: "ellipse", fill: ["#2b2f38", "#eceef2"], line: ["#a3a8b3", "#666c78"], borderStyle: "solid" },
  { kind: "DATASET", shape: "barrel", fill: ["#33301f", "#f4efd6"], line: ["#dcdcaa", "#6b5e12"], borderStyle: "solid" },
  { kind: "DB2_TABLE", shape: "cut-rectangle", fill: ["#1f3327", "#dff1e3"], line: ["#6fc28b", "#236c3d"], borderStyle: "solid" },
  { kind: "TRANSACTION", shape: "octagon", fill: ["#312a3a", "#efe3f3"], line: ["#c586c0", "#6d2f80"], borderStyle: "solid" },
  { kind: "BMS_MAP", shape: "rhomboid", fill: ["#22303a", "#dfeef8"], line: ["#9cdcfe", "#17567c"], borderStyle: "solid" },
  { kind: "EXTERNAL_UTILITY", shape: "tag", fill: ["#33291f", "#f5e6d8"], line: ["#e0955a", "#8c4a1a"], borderStyle: "solid" },
  { kind: "UNRESOLVED", shape: "diamond", fill: ["#3a2323", "#fbe0dd"], line: ["#ff6b5e", "#a82e24"], borderStyle: "dashed" },
  { kind: "UNANALYZABLE", shape: "star", fill: ["#332b1f", "#f7ead2"], line: ["#e8b04a", "#8a5c14"], borderStyle: "solid" },
];

function pick(pair: Pair, theme: ThemeName): string {
  return theme === "dark" ? pair[0] : pair[1];
}

/**
 * The node kinds in legend and filter order, coloured for the theme. Cytoscape draws to a canvas
 * and cannot read CSS custom properties, so the two palettes of tokens.json are written out here.
 */
export function nodeKindStyles(theme: ThemeName): NodeKindStyle[] {
  return NODE_KIND_SHAPES.map((entry) => ({
    kind: entry.kind,
    shape: entry.shape,
    background: pick(entry.fill, theme),
    border: pick(entry.line, theme),
    borderStyle: entry.borderStyle,
  }));
}

/** The node kinds in legend and filter order. */
export const NODE_KINDS: readonly GraphNodeKind[] = NODE_KIND_SHAPES.map((entry) => entry.kind);

const NODE_KIND_SET = new Set<string>(NODE_KINDS);

/** Whether the NODE.type is one the graph draws. Asset rows (JCL, COPYBOOK, BMS) are not. */
export function isGraphNodeKind(type: string): type is GraphNodeKind {
  return NODE_KIND_SET.has(type);
}

/** How one edge kind is drawn: colour plus arrowhead, so the kind survives a colour-blind reading. */
export interface EdgeKindStyle {
  readonly kind: string;
  readonly color: string;
  readonly arrowShape: cytoscape.Css.ArrowShape;
}

/** The edge kinds the engine records (EdgeKind), in legend order. */
const EDGE_KIND_SHAPES: readonly { kind: string; color: Pair; arrowShape: cytoscape.Css.ArrowShape }[] = [
  { kind: "EXECUTION", color: ["#a3a8b3", "#666c78"], arrowShape: "triangle" },
  { kind: "CALL", color: ["#6aa7ff", "#1f4fa8"], arrowShape: "vee" },
  { kind: "REFERENCE", color: ["#dcdcaa", "#6b5e12"], arrowShape: "square" },
  { kind: "TRANSACTION_TRANSITION", color: ["#c586c0", "#6d2f80"], arrowShape: "diamond" },
  { kind: "MAP_REFERENCE", color: ["#9cdcfe", "#17567c"], arrowShape: "circle" },
];

/** The colour of a line that carries no kind: the theme's muted foreground. */
const PLAIN_EDGE: Pair = ["#a3a8b3", "#666c78"];

/** The edge kinds in legend order, coloured for the theme. */
export function edgeKindStyles(theme: ThemeName): EdgeKindStyle[] {
  return EDGE_KIND_SHAPES.map((entry) => ({
    kind: entry.kind,
    color: pick(entry.color, theme),
    arrowShape: entry.arrowShape,
  }));
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
 * The cytoscape stylesheet for the theme. Cytoscape draws to a canvas and does not resolve CSS
 * custom properties, so the token values are written out here.
 */
export function graphStylesheet(theme: ThemeName): cytoscape.StylesheetJsonBlock[] {
  const dark = theme === "dark";
  const plain = pick(PLAIN_EDGE, theme);
  return [
    {
      selector: "node",
      style: {
        shape: "rectangle",
        "background-color": dark ? "#2b2f38" : "#eceef2",
        "border-width": 1.5,
        "border-color": plain,
        "border-style": "dashed",
        label: "data(label)",
        "font-family": CODE_FONT.fontFamily,
        // A node label may be a Japanese name, and 11px was the smallest type in the product.
        "font-size": 12,
        "font-weight": "bold",
        color: dark ? "#e3e5ea" : "#2b2f38",
        "text-valign": "center",
        "text-halign": "center",
        "text-wrap": "ellipsis",
        "text-max-width": "150px",
        width: "label",
        height: 36,
        padding: "8px",
      },
    },
    ...nodeKindStyles(theme).map(
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
        "line-color": plain,
        "target-arrow-color": plain,
        "target-arrow-shape": "triangle",
        "arrow-scale": 0.8,
        "curve-style": "bezier",
        opacity: 0.9,
      },
    },
    ...edgeKindStyles(theme).map(
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
      style: {
        "overlay-color": dark ? "#6aa7ff" : "#2a66d0",
        "overlay-padding": 5,
        "overlay-opacity": 0.35,
      },
    },
  ];
}

/**
 * The ceiling on the zoom `fit` may reach. maxZoom belongs to the core, not to the layout: a graph
 * of two nodes would otherwise be blown up until one node filled the viewport.
 */
export const GRAPH_MAX_ZOOM = 1.6;

export interface GraphCoreOptions {
  readonly container: HTMLElement;
  readonly style: cytoscape.StylesheetJsonBlock[];
  readonly autoungrabify: boolean;
  readonly maxZoom: number;
}

/** The core options. Dragging a node is not allowed, so the layers the layout chose keep their order. */
export function graphCoreOptions(container: HTMLElement, theme: ThemeName): GraphCoreOptions {
  return {
    container,
    style: graphStylesheet(theme),
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
