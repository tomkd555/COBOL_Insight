import { describe, expect, it } from "vitest";
import { buildTrace, flattenTrace, initialExpanded } from "./traceTree";
import { GRAPH, INVENTORY, SYK001_ID } from "./graphFixture";

const ROOTS = buildTrace(GRAPH, INVENTORY);

describe("buildTrace", () => {
  it("has one root per job", () => {
    expect(ROOTS.map((root) => [root.kind, root.label])).toEqual([["job", "SYKD010"]]);
  });

  it("orders the steps by seq, not by the order the edges were read in", () => {
    expect(ROOTS[0].children.map((step) => step.label)).toEqual(["STEP010", "STEP020"]);
  });

  it("opens a step at the EXEC line of its JCL", () => {
    const step = ROOTS[0].children[0];
    expect(step.kind).toBe("step");
    expect(step.path).toBe("jcl/SYKD010.jcl");
    expect(step.line).toBe(14);
  });

  it("hangs the program the step runs under it", () => {
    const program = ROOTS[0].children[0].children[0];
    expect([program.kind, program.label, program.path]).toEqual([
      "program",
      "SYK001",
      "cobol/SYK001.cbl",
    ]);
  });

  it("chains the paragraphs by FALLTHROUGH and lists each one once", () => {
    const program = ROOTS[0].children[0].children[0];
    expect(program.children.map((paragraph) => paragraph.label)).toEqual([
      "MAIN-PROC",
      "READ-ORDER",
    ]);
  });

  it("orders the branches of a paragraph by seq and opens them at their target", () => {
    const main = ROOTS[0].children[0].children[0].children[0];
    expect(main.children.map((branch) => [branch.kind, branch.label, branch.line])).toEqual([
      ["perform", "READ-ORDER", 91],
      ["perform", "WRITE-ERROR", 100],
    ]);
  });

  it("makes a program nothing starts a root of its own", () => {
    const roots = buildTrace({ ...GRAPH, edges: [] }, INVENTORY);
    expect(roots.filter((root) => root.kind === "program").map((root) => root.label)).toEqual([
      "SYK001",
      "SYK002",
    ]);
  });

  it("stops where a paragraph performs itself", () => {
    const roots = buildTrace(
      {
        ...GRAPH,
        edges: [],
        paragraphs: [{ id: 31, programSourceId: SYK001_ID, name: "LOOP", startLine: 10, endLine: 20 }],
        paragraphEdges: [
          { programSourceId: SYK001_ID, from: 31, to: 31, toName: "LOOP", kind: "PERFORM", line: 12, seq: 1 },
        ],
      },
      INVENTORY,
    );
    const program = roots.find((root) => root.label === "SYK001");
    const repeat = program?.children[0].children[0];
    expect(repeat?.cyclic).toBe(true);
    expect(repeat?.children).toEqual([]);
  });

  it("marks a branch whose target could not be resolved rather than dropping it", () => {
    const roots = buildTrace(
      {
        ...GRAPH,
        edges: [],
        paragraphs: [{ id: 41, programSourceId: SYK001_ID, name: "MAIN", startLine: 10, endLine: 20 }],
        paragraphEdges: [
          { programSourceId: SYK001_ID, from: 41, to: null, toName: "WS-TARGET", kind: "GOTO", line: 12, seq: 1 },
        ],
      },
      INVENTORY,
    );
    const branch = roots.find((root) => root.label === "SYK001")?.children[0].children[0];
    expect([branch?.kind, branch?.label]).toEqual(["unresolved", "WS-TARGET"]);
  });
});

describe("flattenTrace", () => {
  it("descends only through the rows that are open", () => {
    const closed = flattenTrace(ROOTS, {});
    expect(closed).toHaveLength(1);
    expect(closed[0]).toMatchObject({ depth: 0, hasChildren: true, expanded: false });

    const open = flattenTrace(ROOTS, initialExpanded(ROOTS));
    expect(open.map((row) => [row.depth, row.node.label])).toEqual([
      [0, "SYKD010"],
      [1, "STEP010"],
      [1, "STEP020"],
    ]);
  });
});
