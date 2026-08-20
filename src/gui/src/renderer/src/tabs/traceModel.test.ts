import { describe, it, expect } from "vitest";
import type { AssetInventoryItem, GraphData } from "../../../shared/engine-api";
import { buildTrace, toCallGraphData, type TraceNode } from "./traceModel";

/** グラフ層の ID 下限(engine の ScanRunner.GRAPH_ID_BASE)。 */
const GRAPH = 1_000_000_000_000;

const JOB = GRAPH + 1;
const STEP1 = GRAPH + 2;
const STEP2 = GRAPH + 3;
const DATASET = GRAPH + 4;

/** 資産に対応するノードの ID は SOURCE.id と同じ値である。 */
const JCL_SOURCE = 1;
const SYK001 = 2;
const SYK002 = 3;
const COPYBOOK = 4;

/**
 * ジョブ SYKD010 が STEP010 → STEP020 の順に SYK001・SYK002 を実行し、SYK001 は
 * 初期処理 → 主処理 → 終了処理 と落ちながら、主処理から明細処理を PERFORM し、
 * 終了処理から解決できない段落へ GOTO する。
 */
const SAMPLE: GraphData = {
  nodes: [
    { id: JCL_SOURCE, type: "JCL", label: "SYKD010.jcl" },
    { id: SYK001, type: "PROGRAM", label: "SYK001" },
    { id: SYK002, type: "PROGRAM", label: "SYK002" },
    { id: COPYBOOK, type: "COPYBOOK", label: "SYKCOM" },
    { id: JOB, type: "JOB", label: "SYKD010" },
    { id: STEP1, type: "STEP", label: "STEP010" },
    { id: STEP2, type: "STEP", label: "STEP020" },
    { id: DATASET, type: "DATASET", label: "SYKT.ORDER.DAILY" },
  ],
  edges: [
    // 実行順を投入順とわざとずらす。並べ替えがこの層で起きることを確かめる。
    { from: JOB, to: STEP2, kind: "EXECUTION", resolution: "CONSTANT", seq: 2, line: 40 },
    { from: JOB, to: STEP1, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: STEP1, to: SYK001, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
    { from: STEP1, to: DATASET, kind: "REFERENCE", resolution: "CONSTANT", seq: 2, line: 22 },
    { from: STEP2, to: SYK002, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 40 },
  ],
  paragraphs: [
    { id: 11, programSourceId: SYK001, name: "初期処理", startLine: 30, endLine: 60 },
    { id: 12, programSourceId: SYK001, name: "主処理", startLine: 61, endLine: 120 },
    { id: 13, programSourceId: SYK001, name: "明細処理", startLine: 121, endLine: 160 },
    { id: 14, programSourceId: SYK001, name: "終了処理", startLine: 161, endLine: 180 },
  ],
  paragraphEdges: [
    { programSourceId: SYK001, from: 11, to: 12, toName: "主処理", kind: "FALLTHROUGH", line: 60, seq: 1 },
    { programSourceId: SYK001, from: 12, to: 13, toName: "明細処理", kind: "PERFORM", line: 70, seq: 1 },
    { programSourceId: SYK001, from: 12, to: 14, toName: "終了処理", kind: "FALLTHROUGH", line: 120, seq: 2 },
    { programSourceId: SYK001, from: 14, to: null, toName: "存在しない段落", kind: "GOTO", line: 170, seq: 1 },
  ],
};

const INVENTORY: readonly AssetInventoryItem[] = [
  { id: JCL_SOURCE, path: "jcl/SYKD010.jcl", name: "SYKD010.jcl", type: "JCL", codepage: "Shift_JIS", byteSize: 400, findingCount: 0 },
  { id: SYK001, path: "cobol/SYK001.cbl", name: "SYK001.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 900, findingCount: 2 },
  { id: SYK002, path: "cobol/SYK002.cbl", name: "SYK002.cbl", type: "PROGRAM", codepage: "Shift_JIS", byteSize: 700, findingCount: 0 },
];

/** 木の中から最初に見つかる、その表示名の節。 */
function find(nodes: readonly TraceNode[], label: string): TraceNode {
  for (const node of nodes) {
    if (node.label === label) {
      return node;
    }
    const hit = findOrNull(node.children, label);
    if (hit !== null) {
      return hit;
    }
  }
  throw new Error(`節が見つからない: ${label}`);
}

function findOrNull(nodes: readonly TraceNode[], label: string): TraceNode | null {
  for (const node of nodes) {
    if (node.label === label) {
      return node;
    }
    const hit = findOrNull(node.children, label);
    if (hit !== null) {
      return hit;
    }
  }
  return null;
}

describe("実行順の木の根", () => {
  it("ジョブと、どこからも呼ばれないプログラムを根に置く", () => {
    const roots = buildTrace(SAMPLE, INVENTORY);
    expect(roots.map((node) => `${node.kind}:${node.label}`)).toEqual(["job:SYKD010"]);
  });

  it("ジョブから実行されないプログラムを起点として拾う", () => {
    const withEntry: GraphData = {
      ...SAMPLE,
      edges: SAMPLE.edges.filter((edge) => edge.to !== SYK002),
    };
    const roots = buildTrace(withEntry, INVENTORY);
    expect(roots.map((node) => node.label)).toEqual(["SYK002", "SYKD010"]);
    expect(roots[0].kind).toBe("program");
  });

  it("呼出関係を持たない資産の行(コピー句・JCL)は根に現れない", () => {
    const labels = buildTrace(SAMPLE, INVENTORY).map((node) => node.label);
    expect(labels).not.toContain("SYKCOM");
    expect(labels).not.toContain("SYKD010.jcl");
  });
});

describe("ジョブからプログラムまで", () => {
  const roots = buildTrace(SAMPLE, INVENTORY);
  const job = roots[0];

  it("ステップを実行順に並べる(記録の並びではない)", () => {
    expect(job.children.map((step) => step.label)).toEqual(["STEP010", "STEP020"]);
  });

  it("ステップは JCL の該当行を開く", () => {
    expect(job.children[0].path).toBe("jcl/SYKD010.jcl");
    expect(job.children[0].line).toBe(20);
    expect(job.children[1].line).toBe(40);
  });

  it("ステップの下にはプログラムだけを置き、データセットは図へ任せる", () => {
    expect(job.children[0].children.map((node) => node.label)).toEqual(["SYK001"]);
  });

  it("プログラムは自分の原本を開く", () => {
    expect(find(roots, "SYK001").path).toBe("cobol/SYK001.cbl");
  });
});

describe("段落の連なり", () => {
  const roots = buildTrace(SAMPLE, INVENTORY);
  const program = find(roots, "SYK001");

  it("先頭の段落から FALLTHROUGH を辿って並べる", () => {
    expect(program.children.map((node) => node.label)).toEqual([
      "初期処理",
      "主処理",
      "終了処理",
    ]);
  });

  it("各段落はその開始行を持ち、プログラムの原本を開く", () => {
    expect(program.children[1].line).toBe(61);
    expect(program.children[1].path).toBe("cobol/SYK001.cbl");
  });

  it("PERFORM の行き先を入れ子の節として置く", () => {
    const main = program.children[1];
    expect(main.children).toHaveLength(1);
    expect(main.children[0].kind).toBe("perform");
    expect(main.children[0].label).toBe("明細処理");
    expect(main.children[0].line).toBe(121);
  });

  it("PERFORM だけで到達する段落を連なりへ重ねて出さない", () => {
    expect(program.children.map((node) => node.label)).not.toContain("明細処理");
  });

  it("解決できない行き先を未解決として示す", () => {
    const jump = program.children[2].children[0];
    expect(jump.kind).toBe("unresolved");
    expect(jump.label).toBe("存在しない段落");
    expect(jump.line).toBe(170);
  });
});

describe("循環と重複", () => {
  /** 主処理 → 明細処理 → 主処理 と PERFORM が巡る定義。 */
  const cyclic: GraphData = {
    ...SAMPLE,
    paragraphEdges: [
      ...SAMPLE.paragraphEdges,
      { programSourceId: SYK001, from: 13, to: 12, toName: "主処理", kind: "PERFORM", line: 130, seq: 1 },
    ],
  };

  it("経路上に現れた段落で辿るのをやめる", () => {
    const detail = find(buildTrace(cyclic, INVENTORY), "明細処理");
    expect(detail.children).toHaveLength(1);
    const back = detail.children[0];
    expect(back.label).toBe("主処理");
    expect(back.cyclic).toBe(true);
    expect(back.children).toHaveLength(0);
  });

  it("GOTO は飛び先を示すだけで辿らない", () => {
    const withGoto: GraphData = {
      ...SAMPLE,
      paragraphEdges: [
        ...SAMPLE.paragraphEdges.filter((edge) => edge.from !== 14),
        { programSourceId: SYK001, from: 14, to: 11, toName: "初期処理", kind: "GOTO", line: 170, seq: 1 },
      ],
    };
    const last = find(buildTrace(withGoto, INVENTORY), "終了処理");
    expect(last.children[0].kind).toBe("goto");
    expect(last.children[0].label).toBe("初期処理");
    expect(last.children[0].children).toHaveLength(0);
  });

  it("FALLTHROUGH が巡っても連なりを打ち切る", () => {
    const loop: GraphData = {
      ...SAMPLE,
      paragraphEdges: [
        ...SAMPLE.paragraphEdges,
        { programSourceId: SYK001, from: 14, to: 11, toName: "初期処理", kind: "FALLTHROUGH", line: 180, seq: 2 },
      ],
    };
    const program = find(buildTrace(loop, INVENTORY), "SYK001");
    expect(program.children.map((node) => node.label)).toEqual([
      "初期処理",
      "主処理",
      "終了処理",
    ]);
  });

  it("流れの記録が無い段落も落とさず後ろへ続ける", () => {
    const noFlow: GraphData = { ...SAMPLE, paragraphEdges: [] };
    const program = find(buildTrace(noFlow, INVENTORY), "SYK001");
    expect(program.children.map((node) => node.label)).toEqual([
      "初期処理",
      "主処理",
      "明細処理",
      "終了処理",
    ]);
  });
});

describe("実行順を決められない辺", () => {
  it("seq 0 の辺を順序の付いた辺の後ろへ回す", () => {
    const mixed: GraphData = {
      ...SAMPLE,
      edges: [
        { from: JOB, to: STEP1, kind: "EXECUTION", resolution: "CONSTANT", seq: 0, line: null },
        { from: JOB, to: STEP2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 40 },
      ],
    };
    expect(find(buildTrace(mixed, INVENTORY), "SYKD010").children.map((node) => node.label)).toEqual([
      "STEP020",
      "STEP010",
    ]);
  });

  it("同じ seq の辺は記録された並びのまま残す", () => {
    const tied: GraphData = {
      ...SAMPLE,
      edges: [
        { from: JOB, to: STEP2, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 40 },
        { from: JOB, to: STEP1, kind: "EXECUTION", resolution: "CONSTANT", seq: 1, line: 20 },
      ],
    };
    expect(find(buildTrace(tied, INVENTORY), "SYKD010").children.map((node) => node.label)).toEqual([
      "STEP020",
      "STEP010",
    ]);
  });
});

describe("図へ渡す形への変換", () => {
  it("呼出関係のノードだけを残し、資産そのものの行を外す", () => {
    const data = toCallGraphData(SAMPLE);
    expect(data.nodes.map((node) => node.label)).toEqual([
      "SYK001",
      "SYK002",
      "SYKD010",
      "STEP010",
      "STEP020",
      "SYKT.ORDER.DAILY",
    ]);
  });

  it("外したノードに繋がる辺を残さない", () => {
    const withCopybook: GraphData = {
      ...SAMPLE,
      edges: [
        ...SAMPLE.edges,
        { from: SYK001, to: COPYBOOK, kind: "REFERENCE", resolution: null, seq: 0, line: null },
      ],
    };
    expect(toCallGraphData(withCopybook).edges).toHaveLength(SAMPLE.edges.length);
  });

  it("同じ組の辺を1本へまとめ、記録の無い解決根拠を定数由来として扱う", () => {
    const duplicated: GraphData = {
      ...SAMPLE,
      edges: [
        { from: SYK001, to: SYK002, kind: "CALL", resolution: null, seq: 1, line: 60 },
        { from: SYK001, to: SYK002, kind: "CALL", resolution: null, seq: 2, line: 90 },
      ],
    };
    const edges = toCallGraphData(duplicated).edges;
    expect(edges).toHaveLength(1);
    expect(edges[0].resolution).toBe("CONSTANT");
  });
});
