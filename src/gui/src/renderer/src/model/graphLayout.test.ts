import { describe, expect, it } from "vitest";
import {
  DASHED_EDGE_CLASS,
  NODE_KINDS,
  buildGraphElements,
  edgeKindStyle,
  graphLayoutOptions,
  graphStylesheet,
  isDashedEdge,
  isGraphNodeKind,
} from "./graphLayout";
import { GRAPH, JOB_ID, STEP010_ID, SYK001_ID, SYK002_ID } from "./graphFixture";

const ALL_IDS = new Set(GRAPH.nodes.map((node) => String(node.id)));

describe("buildGraphElements", () => {
  it("draws only the visible nodes", () => {
    const elements = buildGraphElements(GRAPH, new Set([String(JOB_ID), String(STEP010_ID)]));
    expect(elements.filter((element) => element.group === "nodes")).toHaveLength(2);
  });

  it("keeps an edge only when both of its ends are visible", () => {
    const elements = buildGraphElements(GRAPH, new Set([String(JOB_ID), String(STEP010_ID)]));
    const edges = elements.filter((element) => element.group === "edges");
    expect(edges).toHaveLength(1);
    expect(edges[0].data).toMatchObject({ source: String(JOB_ID), target: String(STEP010_ID) });
  });

  it("leaves out the asset rows that are not call-graph nodes", () => {
    const elements = buildGraphElements(GRAPH, ALL_IDS);
    expect(elements.some((element) => element.data.id === "4")).toBe(false);
  });

  it("dashes an edge whose target was not resolved from a constant", () => {
    const elements = buildGraphElements(GRAPH, ALL_IDS);
    const call = elements.find(
      (element) => element.data.id === `${SYK001_ID}|${SYK002_ID}|CALL|DATAFLOW`,
    );
    expect(call?.group).toBe("edges");
    expect((call as { classes?: string }).classes).toBe(DASHED_EDGE_CLASS);
  });

  it("collapses repeated edges into one line", () => {
    const twice = {
      ...GRAPH,
      edges: [...GRAPH.edges, { ...GRAPH.edges[4], seq: 2, line: 140 }],
    };
    const elements = buildGraphElements(twice, ALL_IDS);
    const calls = elements.filter((element) => element.data.id.includes("|CALL|"));
    expect(calls).toHaveLength(1);
  });
});

describe("kind lookups", () => {
  it("knows the eleven kinds the graph draws", () => {
    expect(NODE_KINDS).toHaveLength(11);
    expect(isGraphNodeKind("PROGRAM")).toBe(true);
    expect(isGraphNodeKind("COPYBOOK")).toBe(false);
  });

  it("falls back to grey for an edge kind this build does not know", () => {
    expect(edgeKindStyle("CALL").arrowShape).toBe("vee");
    expect(edgeKindStyle("SOMETHING_NEW").color).toBe("#8b8b8b");
  });

  it("dashes dataflow-derived and unresolved edges only", () => {
    expect(isDashedEdge("DATAFLOW")).toBe(true);
    expect(isDashedEdge("UNRESOLVED")).toBe(true);
    expect(isDashedEdge("CONSTANT")).toBe(false);
    expect(isDashedEdge(null)).toBe(false);
  });
});

describe("stylesheet and layout", () => {
  it("gives every node kind its own block", () => {
    const selectors = graphStylesheet().map((block) => block.selector);
    for (const kind of NODE_KINDS) {
      expect(selectors).toContain(`node[kind="${kind}"]`);
    }
  });

  it("lays the layers out from left to right", () => {
    const options = graphLayoutOptions();
    expect(options.name).toBe("elk");
    expect(options.elk["algorithm"]).toBe("layered");
    expect(options.elk["elk.direction"]).toBe("RIGHT");
  });
});
