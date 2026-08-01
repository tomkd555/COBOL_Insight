import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseCallgraph } from "./callgraph";

const fixture = readFileSync(join(__dirname, "..", "__fixtures__", "callgraph.json"), "utf-8");

describe("parseCallgraph", () => {
  it("実 callgraph JSON からノードとエッジを取り出す", () => {
    const graph = parseCallgraph(fixture);
    expect(graph.nodes.length).toBeGreaterThan(0);
    const bmsMap = graph.nodes.find((n) => n.id === "bmsmap:SYKMAP1.SYKM01");
    expect(bmsMap).toBeDefined();
    expect(bmsMap?.kind).toBe("BMS_MAP");
    expect(bmsMap?.label).toBe("SYKMAP1.SYKM01");
    expect(bmsMap?.attributes).toEqual({});
  });

  it("エッジは from/to/kind/resolution を持つ", () => {
    const graph = parseCallgraph(fixture);
    expect(graph.edges.length).toBeGreaterThan(0);
    for (const edge of graph.edges) {
      expect(typeof edge.from).toBe("string");
      expect(typeof edge.to).toBe("string");
      expect(typeof edge.kind).toBe("string");
      expect(typeof edge.resolution).toBe("string");
    }
  });

  it("attributes を持つノードは Record として復元する", () => {
    const doc = JSON.stringify({
      nodes: [
        { id: "program:A", kind: "PROGRAM", label: "A", attributes: { srcFile: "a.cbl" } },
      ],
      edges: [{ from: "program:A", to: "program:B", kind: "CALL", resolution: "DATAFLOW" }],
    });
    const graph = parseCallgraph(doc);
    expect(graph.nodes[0].attributes).toEqual({ srcFile: "a.cbl" });
    expect(graph.edges[0].resolution).toBe("DATAFLOW");
  });

  it("nodes/edges 欠落でも空配列を返す", () => {
    expect(parseCallgraph("{}")).toEqual({ nodes: [], edges: [] });
  });
});
