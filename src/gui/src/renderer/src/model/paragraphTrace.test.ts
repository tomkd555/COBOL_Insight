import { describe, expect, it } from "vitest";
import type { FlowEdge, FlowParagraph, ProgramFlow } from "../../../shared/ipc";
import { buildParagraphTrace, flowParagraphOf, rowPathTo } from "./paragraphTrace";
import { TRACE_NODE_BUDGET, type TraceNode } from "./traceTree";

/** Paragraphs in definition order: `NAME`, `NAME@SECTION` for a paragraph of a section, `NAME!` for a section. */
function paragraphs(...names: string[]): FlowParagraph[] {
  return names.map((spec, index) => {
    const section = spec.endsWith("!");
    const [name, sectionName] = spec.replace("!", "").split("@");
    return {
      id: index + 1,
      name: name ?? "",
      kind: section ? "SECTION" : "PARAGRAPH",
      sectionName: sectionName ?? null,
      startLine: (index + 1) * 10,
      endLine: (index + 1) * 10 + 9,
    };
  });
}

function flowOf(list: FlowParagraph[], ...specs: string[]): ProgramFlow {
  const id = (name: string): number | null => list.find((each) => each.name === name)?.id ?? null;
  const seq = new Map<number, number>();
  const edges: FlowEdge[] = specs.map((spec) => {
    // "FROM KIND TO [THRU NAME]", e.g. "MAIN PERFORM A THRU C".
    const [from, kind, to, , thruName] = spec.split(" ");
    const fromId = id(from ?? "") ?? 0;
    const next = (seq.get(fromId) ?? 0) + 1;
    seq.set(fromId, next);
    const call = kind === "CALL" || kind === "XCTL";
    return {
      from: fromId,
      to: call ? null : id(to ?? ""),
      toName: to ?? "",
      kind: kind ?? "",
      thru: thruName === undefined ? null : id(thruName),
      thruName: thruName ?? null,
      line: fromId * 10 + next,
      seq: next,
      toPath: call && to === "SUBPGM" ? "cobol/SUBPGM.cbl" : null,
    };
  });
  return { paragraphs: list, edges };
}

/** The tree as indented lines: `kind label`, with `(cyclic)` where the descent stopped. */
function shape(nodes: readonly TraceNode[], depth = 0): string[] {
  return nodes.flatMap((node) => [
    `${"  ".repeat(depth)}${node.kind} ${node.label}${node.cyclic ? " (cyclic)" : ""}`,
    ...shape(node.children, depth + 1),
  ]);
}

describe("buildParagraphTrace", () => {
  it("starts at the first paragraph when no start is named, and is empty for no paragraphs", () => {
    const list = paragraphs("MAIN", "SUB");

    expect(shape(buildParagraphTrace(flowOf(list), null).roots)).toEqual(["paragraph MAIN"]);
    expect(shape(buildParagraphTrace(flowOf(list), 2).roots)).toEqual(["paragraph SUB"]);
    expect(buildParagraphTrace({ paragraphs: [], edges: [] }, null)).toEqual({
      roots: [],
      elements: [],
      truncated: false,
    });
  });

  it("descends into a PERFORM and carries on with what follows it", () => {
    const list = paragraphs("MAIN", "A", "B");
    const flow = flowOf(list, "MAIN PERFORM A", "MAIN PERFORM B", "A PERFORM B", "A FALLTHROUGH B");

    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform A",
      "    perform B",
      "  perform B",
    ]);
  });

  it("walks a THRU range paragraph by paragraph, and stops at its end", () => {
    const list = paragraphs("MAIN", "A", "B", "C", "D");
    const flow = flowOf(
      list,
      "MAIN PERFORM A THRU C",
      "A FALLTHROUGH B",
      "B PERFORM D",
      "B FALLTHROUGH C",
      "C FALLTHROUGH D",
    );

    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform A THRU C",
      "    fallthrough B",
      "      perform D",
      "    fallthrough C",
    ]);
  });

  it("walks a performed section through its last paragraph", () => {
    const list = paragraphs("MAIN", "S!", "P1@S", "P2@S", "T!", "Q@T");
    const flow = flowOf(
      list,
      "MAIN PERFORM S",
      "S FALLTHROUGH P1",
      "P1 FALLTHROUGH P2",
      "P2 FALLTHROUGH T",
      "T FALLTHROUGH Q",
    );

    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform S",
      "    fallthrough P1",
      "    fallthrough P2",
    ]);
  });

  it("shows a THRU name that cannot serve and takes the target alone", () => {
    const list = paragraphs("MAIN", "A", "B");
    const reversed = flowOf(list, "B PERFORM B THRU A", "A FALLTHROUGH B");
    const unknown = flowOf(list, "MAIN PERFORM A THRU NO-SUCH", "A FALLTHROUGH B");

    expect(shape(buildParagraphTrace(reversed, 3).roots)).toEqual([
      "paragraph B",
      "  perform B (cyclic)",
      "  unresolved THRU A",
    ]);
    expect(shape(buildParagraphTrace(unknown, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform A",
      "  unresolved THRU NO-SUCH",
    ]);
  });

  it("follows a GO TO, and draws a loop once", () => {
    const list = paragraphs("MAIN", "LOOP", "DONE");
    const flow = flowOf(list, "MAIN GOTO LOOP", "LOOP GOTO LOOP", "LOOP GOTO MAIN", "LOOP FALLTHROUGH DONE");

    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  goto LOOP",
      "    goto LOOP (cyclic)",
      "    goto MAIN (cyclic)",
      "    fallthrough DONE",
    ]);
  });

  it("keeps the end of the performed range after a GO TO inside it", () => {
    const list = paragraphs("MAIN", "A", "B", "C", "D");
    const flow = flowOf(
      list,
      "MAIN PERFORM A THRU C",
      "A GOTO C",
      "A FALLTHROUGH B",
      "B FALLTHROUGH C",
      "C FALLTHROUGH D",
    );

    // The GO TO lands on the range's last paragraph, so the flow under it ends there too.
    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform A THRU C",
      "    goto C",
      "    fallthrough B",
      "    fallthrough C",
    ]);
  });

  it("runs on to where the flow stops after a GO TO out of the performed range", () => {
    const list = paragraphs("MAIN", "A", "X", "Y");
    const flow = flowOf(list, "MAIN PERFORM A", "A GOTO X", "X FALLTHROUGH Y");

    expect(shape(buildParagraphTrace(flow, null).roots)).toEqual([
      "paragraph MAIN",
      "  perform A",
      "    goto X",
      "      fallthrough Y",
    ]);
  });

  it("shows a call of another program as a leaf that names its asset", () => {
    const list = paragraphs("MAIN");
    const trace = buildParagraphTrace(flowOf(list, "MAIN CALL SUBPGM", "MAIN XCTL W-PGM", "MAIN PERFORM GONE"), null);
    const [call, xctl, missing] = trace.roots[0]?.children ?? [];

    expect(call).toMatchObject({ kind: "call", label: "SUBPGM", path: "cobol/SUBPGM.cbl", line: 11 });
    expect(xctl).toMatchObject({ kind: "xctl", label: "W-PGM", path: null });
    expect(missing).toMatchObject({ kind: "unresolved", label: "GONE" });
  });

  it("stops at the row budget and says so", () => {
    // Every paragraph performs the two after it: the tree doubles at every level.
    const names = Array.from({ length: 40 }, (_, index) => `P${index}`);
    const list = paragraphs(...names);
    const specs = names.flatMap((name, index) =>
      [index + 1, index + 2].filter((to) => to < names.length).map((to) => `${name} PERFORM P${to}`),
    );
    const trace = buildParagraphTrace(flowOf(list, ...specs), null);
    const count = (nodes: readonly TraceNode[]): number =>
      nodes.reduce((sum, node) => sum + 1 + count(node.children), 0);

    expect(trace.truncated).toBe(true);
    expect(count(trace.roots)).toBeLessThanOrEqual(TRACE_NODE_BUDGET + 1);
  });
});

describe("the graph of a trace", () => {
  const list = paragraphs("MAIN", "S!", "P1@S");
  const flow = flowOf(
    list,
    "MAIN PERFORM S",
    "MAIN CALL SUBPGM",
    "MAIN CALL EXTPGM",
    "MAIN PERFORM S",
    "S FALLTHROUGH P1",
  );
  const trace = buildParagraphTrace(flow, null);

  it("holds one node per paragraph and program reached", () => {
    const nodes = trace.elements.filter((element) => element.group === "nodes");

    expect(nodes.map((node) => `${node.data.kind} ${node.data.label}`)).toEqual([
      "PARAGRAPH MAIN",
      "SECTION S",
      "PARAGRAPH P1",
      "PROGRAM SUBPGM",
      "UNRESOLVED EXTPGM",
    ]);
  });

  it("holds each flow once, numbered by the first row that took it", () => {
    const edges = trace.elements.filter((element) => element.group === "edges");

    expect(edges.map((edge) => `${edge.data.label} ${edge.data.source}>${edge.data.target} ${edge.data.kind}`)).toEqual([
      "1 1>2 PERFORM",
      "2 2>3 FALLTHROUGH",
      "3 1>program:SUBPGM CALL",
      "4 1>program:EXTPGM CALL",
    ]);
  });

  it("leads from a node to its first row", () => {
    expect(rowPathTo(trace.roots, "3")).toEqual(["start", "start/0", "start/0/0"]);
    expect(rowPathTo(trace.roots, "1")).toEqual(["start"]);
    expect(rowPathTo(trace.roots, "99")).toEqual([]);
  });
});

describe("flowParagraphOf", () => {
  const flow = flowOf(paragraphs("MAIN", "S1!", "P@S1", "S2!", "P@S2"));

  it("returns the paragraph of the named section, ignoring case", () => {
    expect(flowParagraphOf(flow, "p", "s2")?.id).toBe(5);
    expect(flowParagraphOf(flow, "P", "S1")?.id).toBe(3);
    expect(flowParagraphOf(flow, "S2", null)?.id).toBe(4);
  });

  it("returns null where the named section holds no paragraph of that name", () => {
    expect(flowParagraphOf(flow, "P", null)).toBeNull();
    expect(flowParagraphOf(flow, "P", "S3")).toBeNull();
    expect(flowParagraphOf(flow, "GONE", null)).toBeNull();
  });
});
