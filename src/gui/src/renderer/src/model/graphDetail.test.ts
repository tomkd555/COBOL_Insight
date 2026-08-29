import { describe, expect, it } from "vitest";
import { nodeAssetPath, nodeDetail } from "./graphDetail";
import { GRAPH, INVENTORY, JOB_ID, STEP010_ID, SYK001_ID, SYK002_ID } from "./graphFixture";

describe("nodeDetail", () => {
  it("lists the calls in and out with their execution order and calling line", () => {
    const detail = nodeDetail(GRAPH, String(STEP010_ID), INVENTORY);
    expect(detail?.incoming).toEqual([
      { peerId: String(JOB_ID), peerLabel: "SYKD010", kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 14 },
    ]);
    expect(detail?.outgoing.map((edge) => [edge.peerLabel, edge.seq, edge.line])).toEqual([
      ["SYK001", 1, 14],
      ["SYKT.ORDER.DAILY", 0, 16],
    ]);
  });

  it("returns null for an id that names no node", () => {
    expect(nodeDetail(GRAPH, "999999", INVENTORY)).toBeNull();
  });

  it("names the asset of a node whose id is an asset id", () => {
    expect(nodeDetail(GRAPH, String(SYK002_ID), INVENTORY)?.path).toBe("cobol/SYK002.cbl");
  });
});

describe("nodeAssetPath", () => {
  it("matches a graph-layer node to its asset by name", () => {
    const job = GRAPH.nodes.find((node) => node.id === JOB_ID);
    expect(nodeAssetPath(job!, INVENTORY)).toBe("jcl/SYKD010.jcl");
  });

  it("names nothing when two assets share the name", () => {
    const job = GRAPH.nodes.find((node) => node.id === JOB_ID);
    const ambiguous = [
      ...INVENTORY,
      { id: 9, path: "old/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: null, byteSize: 1, findingCount: 0 },
    ];
    expect(nodeAssetPath(job!, ambiguous)).toBeNull();
  });

  it("files a qualified BMS map name under its map set", () => {
    const map = { id: 7, type: "BMS_MAP", label: "SYKMAP1.SYKM01" };
    const inventory = [
      { id: 8, path: "bms/SYKMAP1.bms", name: "SYKMAP1.bms", type: "BMS", codepage: null, byteSize: 1, findingCount: 0 },
    ];
    expect(nodeAssetPath(map, inventory)).toBe("bms/SYKMAP1.bms");
  });

  it("names nothing when the inventory holds no such asset", () => {
    const node = GRAPH.nodes.find((entry) => entry.id === SYK001_ID);
    expect(nodeAssetPath(node!, [])).toBeNull();
  });
});
