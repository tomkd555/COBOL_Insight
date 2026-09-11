import { describe, expect, it } from "vitest";
import {
  DEPTH_LIMITS,
  INITIAL_GRAPH_FILTER,
  graphRootIds,
  indexGraph,
  nodeKindCounts,
  searchMatches,
  toggleKind,
  visibleNodeIds,
  withDepth,
} from "./graphFilter";
import {
  DATASET_ID,
  GRAPH,
  JOB_ID,
  STEP010_ID,
  STEP020_ID,
  SYK001_ID,
  SYK002_ID,
} from "./graphFixture";

const ids = (set: ReadonlySet<string>): string[] => [...set].sort();

describe("graphRootIds", () => {
  it("starts from the jobs", () => {
    expect(graphRootIds(indexGraph(GRAPH))).toEqual([String(JOB_ID)]);
  });

  it("falls back to the nodes nothing points at when there is no job", () => {
    const roots = graphRootIds(
      indexGraph({
        ...GRAPH,
        nodes: GRAPH.nodes.filter((node) => node.type !== "JOB" && node.type !== "STEP"),
        edges: GRAPH.edges.filter((edge) => edge.kind === "CALL"),
      }),
    );
    expect(roots).toEqual([String(SYK001_ID), String(DATASET_ID)]);
  });

  it("keeps a node with no edge at all, which nothing could ever reach", () => {
    const roots = graphRootIds(
      indexGraph({
        ...GRAPH,
        nodes: [...GRAPH.nodes, { id: 9, type: "UNANALYZABLE", label: "BROKEN" }],
      }),
    );
    expect(roots).toContain("9");
  });
});

describe("visibleNodeIds", () => {
  const index = indexGraph(GRAPH);

  it("reaches one hop out from the roots", () => {
    const visible = visibleNodeIds(index, { ...INITIAL_GRAPH_FILTER, depth: 1 });
    expect(ids(visible)).toEqual(ids(new Set([JOB_ID, STEP010_ID, STEP020_ID].map(String))));
  });

  it("reaches further as the depth rises", () => {
    const twoHops = visibleNodeIds(index, { ...INITIAL_GRAPH_FILTER, depth: 2 });
    expect(twoHops.has(String(SYK001_ID))).toBe(true);
    expect(twoHops.has(String(DATASET_ID))).toBe(true);
    // Three hops from the job: step, program, called program.
    expect(twoHops.has(String(SYK002_ID))).toBe(false);
    expect(visibleNodeIds(index, { ...INITIAL_GRAPH_FILTER, depth: 3 }).has(String(SYK002_ID))).toBe(
      true,
    );
  });

  it("leaves out the asset rows that are not call-graph nodes", () => {
    const visible = visibleNodeIds(index, { ...INITIAL_GRAPH_FILTER, depth: DEPTH_LIMITS.max });
    expect(visible.has("4")).toBe(false);
  });

  it("centres on the focus node instead of the roots", () => {
    const visible = visibleNodeIds(index, {
      ...INITIAL_GRAPH_FILTER,
      depth: 1,
      focusId: String(SYK001_ID),
    });
    expect(ids(visible)).toEqual(ids(new Set([SYK001_ID, SYK002_ID, STEP010_ID].map(String))));
  });

  it("walks outward against the direction of an edge as well as along it", () => {
    const visible = visibleNodeIds(index, {
      ...INITIAL_GRAPH_FILTER,
      depth: 1,
      focusId: String(DATASET_ID),
    });
    expect(visible.has(String(STEP010_ID))).toBe(true);
  });

  it("hides a kind that was switched off without cutting the path through it", () => {
    const filter = toggleKind({ ...INITIAL_GRAPH_FILTER, depth: 3 }, "STEP");
    const visible = visibleNodeIds(index, filter);
    expect(visible.has(String(STEP010_ID))).toBe(false);
    expect(visible.has(String(SYK001_ID))).toBe(true);
  });

  it("seeds from what the search matched", () => {
    const visible = visibleNodeIds(index, {
      ...INITIAL_GRAPH_FILTER,
      depth: 1,
      search: "syk002",
    });
    expect(ids(visible)).toEqual(ids(new Set([SYK002_ID, SYK001_ID].map(String))));
  });
});

describe("searchMatches", () => {
  it("matches labels case-insensitively and matches nothing when empty", () => {
    expect(searchMatches(GRAPH, "step0")).toEqual([String(STEP010_ID), String(STEP020_ID)]);
    expect(searchMatches(GRAPH, "  ")).toEqual([]);
  });
});

describe("withDepth", () => {
  it("clamps into the slider's range", () => {
    expect(withDepth(INITIAL_GRAPH_FILTER, 9).depth).toBe(DEPTH_LIMITS.max);
    expect(withDepth(INITIAL_GRAPH_FILTER, 0).depth).toBe(DEPTH_LIMITS.min);
  });
});

describe("nodeKindCounts", () => {
  const wholeGraph = withDepth(INITIAL_GRAPH_FILTER, DEPTH_LIMITS.max);

  it("counts what the walk reaches, by kind", () => {
    const counts = nodeKindCounts(indexGraph(GRAPH), wholeGraph);
    expect(counts.PROGRAM).toBe(2);
    expect(counts.STEP).toBe(2);
    expect(counts.JOB).toBe(1);
    expect(counts.PARAGRAPH).toBe(0);
  });

  it("stops at the walk's depth, same as visibleNodeIds", () => {
    // At the default depth SYK002 is a hop beyond what the walk from the job reaches.
    expect(nodeKindCounts(indexGraph(GRAPH), INITIAL_GRAPH_FILTER).PROGRAM).toBe(1);
  });

  it("does not drop to zero when the kind's own chip is switched off", () => {
    const index = indexGraph(GRAPH);
    const withProgramOff = toggleKind(wholeGraph, "PROGRAM");
    expect(nodeKindCounts(index, withProgramOff).PROGRAM).toBe(2);
  });
});
