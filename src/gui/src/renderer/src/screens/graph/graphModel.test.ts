import { describe, it, expect } from "vitest";
import type { CallGraphData } from "../../../../shared/engine-api";
import { SAMPLE_GRAPH, SAMPLE_GRAPH_WITH_UNANALYZABLE } from "./fixtures";
import { SAMPLE_INVENTORY } from "../../data/__fixtures__/samples";
import type { AnyNodeKind, GraphEdgeElement, GraphNodeElement } from "./graphModel";
import {
  DEFAULT_DB_FILE,
  EDGE_KIND_STYLES,
  NODE_KIND_STYLES,
  VISIBLE_NODE_WARNING_THRESHOLD,
  buildGraphElements,
  edgeKindStyle,
  graphArtifactPaths,
  graphExitBanner,
  graphCoreOptions,
  graphLayoutOptions,
  graphRootIds,
  graphSourcePaths,
  graphStylesheet,
  graphWarning,
  isDashedEdge,
  isGraphNodeKind,
  nodeDetail,
  nodeKindCounts,
  nodeKindStyle,
  unanalyzableBanner,
  unanalyzableCount,
  visibleNodeIds,
} from "./graphModel";

/** 既定のノード種別フィルタ(全種別を表示)。解析不能は AppState の graphTypes に無いため、ここで加える。 */
const ALL_KINDS: Record<AnyNodeKind, boolean> = {
  JOB: true,
  STEP: true,
  PROGRAM: true,
  PARAGRAPH: true,
  DATASET: true,
  DB2_TABLE: true,
  UNRESOLVED: true,
  EXTERNAL_UTILITY: true,
  TRANSACTION: true,
  BMS_MAP: true,
  UNANALYZABLE: true,
};

function visible(expanded: Record<string, boolean>, kinds = ALL_KINDS): string[] {
  return [...visibleNodeIds(SAMPLE_GRAPH, { expanded, kinds })].sort();
}

describe("ノード種別の見え方", () => {
  it("engine の NodeKind 10 種に解析不能を加えた 11 種をすべて持ち、形と配色が種別ごとに異なる", () => {
    const kinds = NODE_KIND_STYLES.map((style) => style.kind);
    expect(kinds).toEqual([
      "JOB",
      "STEP",
      "PROGRAM",
      "PARAGRAPH",
      "DATASET",
      "DB2_TABLE",
      "TRANSACTION",
      "BMS_MAP",
      "EXTERNAL_UTILITY",
      "UNRESOLVED",
      "UNANALYZABLE",
    ]);
    expect(new Set(NODE_KIND_STYLES.map((style) => style.shape)).size).toBe(11);
    expect(new Set(NODE_KIND_STYLES.map((style) => style.border)).size).toBe(11);
    expect(new Set(NODE_KIND_STYLES.map((style) => style.label)).size).toBe(11);
    expect(new Set(NODE_KIND_STYLES.map((style) => style.shapeLabel)).size).toBe(11);
  });

  it("未解決ノードは破線の枠で示す", () => {
    expect(nodeKindStyle("UNRESOLVED").borderStyle).toBe("dashed");
    expect(nodeKindStyle("PROGRAM").borderStyle).toBe("solid");
  });

  it("解析不能は未解決と異なる形・色を持ち、別概念であることを視覚的に区別する", () => {
    const unresolved = nodeKindStyle("UNRESOLVED");
    const unanalyzable = nodeKindStyle("UNANALYZABLE");
    expect(unanalyzable.label).toBe("解析不能");
    expect(unanalyzable.shape).not.toBe(unresolved.shape);
    expect(unanalyzable.background).not.toBe(unresolved.background);
    expect(unanalyzable.border).not.toBe(unresolved.border);
  });

  it("解析不能も既知の種別として扱う", () => {
    expect(isGraphNodeKind("UNANALYZABLE")).toBe(true);
  });

  it("列挙に無い種別は隠さず、「不明な種別」とした代替の見え方で扱う", () => {
    const style = nodeKindStyle("FUTURE_KIND");
    expect(style.label).toBe("不明な種別");
    expect(style.shape).toBe("rectangle");
  });
});

describe("エッジの見え方", () => {
  it("engine の EdgeKind 5 種をすべて持ち、色が種別ごとに異なる", () => {
    expect(EDGE_KIND_STYLES.map((style) => style.kind)).toEqual([
      "EXECUTION",
      "CALL",
      "REFERENCE",
      "TRANSACTION_TRANSITION",
      "MAP_REFERENCE",
    ]);
    expect(new Set(EDGE_KIND_STYLES.map((style) => style.color)).size).toBe(5);
  });

  it("色に加えて矢頭形状でも 5 種を区別し、凡例に出す形状名を持つ", () => {
    expect(new Set(EDGE_KIND_STYLES.map((style) => style.arrowShape)).size).toBe(5);
    expect(EDGE_KIND_STYLES.every((style) => style.arrowLabel !== "")).toBe(true);
  });

  it("スタイルは種別ごとに矢頭形状を指定する", () => {
    const styles = graphStylesheet();
    for (const style of EDGE_KIND_STYLES) {
      const block = styles.find((entry) => entry.selector === `edge[kind="${style.kind}"]`);
      const shape =
        block !== undefined && "style" in block && "target-arrow-shape" in block.style
          ? block.style["target-arrow-shape"]
          : undefined;
      expect(shape).toBe(style.arrowShape);
    }
  });

  it("解決根拠がデータフロー由来と未解決のときだけ破線にする", () => {
    expect(isDashedEdge("DATAFLOW")).toBe(true);
    expect(isDashedEdge("UNRESOLVED")).toBe(true);
    expect(isDashedEdge("CONSTANT")).toBe(false);
  });

  it("列挙に無いエッジ種別も代替の見え方で扱う", () => {
    expect(edgeKindStyle("FUTURE_EDGE").label).toBe("FUTURE_EDGE");
  });
});

describe("起点ノード", () => {
  it("ジョブとトランザクションを起点にする", () => {
    expect(graphRootIds(SAMPLE_GRAPH)).toEqual(["job:SYKD010", "job:SYKD020", "transaction:SYK8"]);
  });

  it("ジョブもトランザクションも無いときは入次数 0 のノードを起点にする", () => {
    const data: CallGraphData = {
      nodes: [
        { id: "program:A", kind: "PROGRAM", label: "A", attributes: {} },
        { id: "program:B", kind: "PROGRAM", label: "B", attributes: {} },
      ],
      edges: [{ from: "program:A", to: "program:B", kind: "CALL", resolution: "CONSTANT" }],
    };
    expect(graphRootIds(data)).toEqual(["program:A"]);
  });

  it("入次数 0 のノードも無いときは全ノードを起点にする", () => {
    const data: CallGraphData = {
      nodes: [
        { id: "program:A", kind: "PROGRAM", label: "A", attributes: {} },
        { id: "program:B", kind: "PROGRAM", label: "B", attributes: {} },
      ],
      edges: [
        { from: "program:A", to: "program:B", kind: "CALL", resolution: "CONSTANT" },
        { from: "program:B", to: "program:A", kind: "CALL", resolution: "CONSTANT" },
      ],
    };
    expect(graphRootIds(data)).toEqual(["program:A", "program:B"]);
  });

  it("エッジを持たない孤立ノード(解析不能ノードなど)は、ジョブ・トランザクションが起点でも常に起点集合へ加える", () => {
    // 孤立ノードは展開による推移到達では永久に表示に加わらないため、起点に含めないと図に一切現れない。
    expect(graphRootIds(SAMPLE_GRAPH_WITH_UNANALYZABLE)).toEqual([
      "job:SYKD010",
      "job:SYKD020",
      "transaction:SYK8",
      "unanalyzable:BROKEN1.cbl",
    ]);
  });
});

describe("部分展開の可視集合", () => {
  it("展開が無い初期状態は起点だけを表示する", () => {
    expect(visible({})).toEqual(["job:SYKD010", "job:SYKD020", "transaction:SYK8"]);
  });

  it("展開したノードの隣接を表示に加える", () => {
    expect(visible({ "job:SYKD010": true })).toEqual([
      "job:SYKD010",
      "job:SYKD020",
      "step:SYKD010.STEP010",
      "step:SYKD010.STEP020",
      "transaction:SYK8",
    ]);
  });

  it("展開は推移する。展開済みノードが表示に入ると、その隣接も表示に入る", () => {
    const ids = visible({ "job:SYKD010": true, "step:SYKD010.STEP010": true });
    expect(ids).toContain("program:SYK001");
    expect(ids).toContain("dataset:SYKT.D250718.ORDER.DAILY");
    // STEP020 自体は展開していないため、その先のプログラムは表示しない。
    expect(ids).not.toContain("program:SYK002");
  });

  it("展開を畳むと、その経路だけで見えていたノードは表示から外れる", () => {
    const expanded = { "job:SYKD010": true, "step:SYKD010.STEP010": true };
    const collapsed = { ...expanded, "job:SYKD010": false };
    expect(visible(collapsed)).toEqual(["job:SYKD010", "job:SYKD020", "transaction:SYK8"]);
  });

  it("隣接はエッジの向きに依らない。入ってくるエッジの相手も展開の対象にする", () => {
    const ids = visible({ "program:SYK001": true, "job:SYKD010": true, "step:SYKD010.STEP010": true });
    expect(ids).toContain("program:SYK003");
    expect(ids).toContain("step:SYKD010.STEP010");
  });

  it("種別フィルタを切ると、その種別のノードは表示しない", () => {
    const kinds: Record<AnyNodeKind, boolean> = { ...ALL_KINDS, STEP: false };
    const ids = visibleNodeIds(SAMPLE_GRAPH, { expanded: { "job:SYKD010": true }, kinds });
    expect([...ids]).not.toContain("step:SYKD010.STEP010");
    expect([...ids]).toContain("job:SYKD010");
  });

  it("起点の種別を切ると表示は空になる", () => {
    const kinds: Record<AnyNodeKind, boolean> = { ...ALL_KINDS, JOB: false, TRANSACTION: false };
    expect(visibleNodeIds(SAMPLE_GRAPH, { expanded: {}, kinds }).size).toBe(0);
  });

  it("解析不能ノードは孤立していても展開なしの初期表示に含まれる", () => {
    const ids = visibleNodeIds(SAMPLE_GRAPH_WITH_UNANALYZABLE, { expanded: {}, kinds: ALL_KINDS });
    expect(ids.has("unanalyzable:BROKEN1.cbl")).toBe(true);
  });

  it("種別フィルタで解析不能を切ると、孤立していても図から外れる", () => {
    const kinds: Record<AnyNodeKind, boolean> = { ...ALL_KINDS, UNANALYZABLE: false };
    const ids = visibleNodeIds(SAMPLE_GRAPH_WITH_UNANALYZABLE, { expanded: {}, kinds });
    expect(ids.has("unanalyzable:BROKEN1.cbl")).toBe(false);
  });
});

describe("Cytoscape 要素の生成", () => {
  const ids = visibleNodeIds(SAMPLE_GRAPH, {
    expanded: { "job:SYKD010": true, "step:SYKD010.STEP020": true, "program:SYK002": true },
    kinds: ALL_KINDS,
  });
  const elements = buildGraphElements(SAMPLE_GRAPH, ids);
  const nodeElements = elements.filter((element): element is GraphNodeElement => element.group === "nodes");
  const edgeElements = elements.filter((element): element is GraphEdgeElement => element.group === "edges");

  it("表示するノードだけを要素にする", () => {
    const nodeIds = nodeElements.map((element) => element.data.id);
    expect(nodeIds).toHaveLength(ids.size);
    expect(nodeIds).toContain("program:SYK002");
    expect(nodeIds).not.toContain("program:SYK006");
  });

  it("両端が表示されているエッジだけを要素にする", () => {
    for (const edge of edgeElements) {
      expect(ids.has(edge.data.source)).toBe(true);
      expect(ids.has(edge.data.target)).toBe(true);
    }
    expect(edgeElements.map((edge) => edge.data.id)).toContain("program:SYK002|program:SYK004|CALL|DATAFLOW");
  });

  it("データフロー由来・未解決のエッジへ破線クラスを付ける", () => {
    const dataflow = elements.find((element) => element.data.id === "program:SYK002|program:SYK004|CALL|DATAFLOW");
    const constant = elements.find(
      (element) => element.data.id === "job:SYKD010|step:SYKD010.STEP020|EXECUTION|CONSTANT",
    );
    expect(dataflow?.classes).toContain("ci-edge-dashed");
    expect(constant?.classes ?? "").not.toContain("ci-edge-dashed");
  });

  it("ノード要素は種別と表示名を持つ", () => {
    const node = nodeElements.find((element) => element.data.id === "program:SYK002");
    expect(node?.data.kind).toBe("PROGRAM");
    expect(node?.data.label).toBe("SYK002");
    expect(node?.data.kindLabel).toBe("プログラム");
  });

  it("要素の ID は一意である", () => {
    const allIds = elements.map((element) => element.data.id);
    expect(new Set(allIds).size).toBe(allIds.length);
  });

  it("破線クラスは種別ではなく解決根拠で決まる", () => {
    const unresolved = edgeElements.find(
      (edge) => edge.data.id === "program:SYK002|unresolved:WS-PROG-NAME|CALL|UNRESOLVED",
    );
    expect(unresolved?.classes).toContain("ci-edge-dashed");
    expect(unresolved?.data.resolution).toBe("UNRESOLVED");
  });
});

describe("スタイルとレイアウト", () => {
  it("種別ごとのノード selector とエッジ種別・破線の selector を持つ", () => {
    const selectors = graphStylesheet().map((entry) => entry.selector);
    for (const style of NODE_KIND_STYLES) {
      expect(selectors).toContain(`node[kind="${style.kind}"]`);
    }
    for (const style of EDGE_KIND_STYLES) {
      expect(selectors).toContain(`edge[kind="${style.kind}"]`);
    }
    expect(selectors).toContain("edge.ci-edge-dashed");
    expect(selectors).toContain("node.ci-node-selected");
  });

  it("レイアウトは ELK の層化レイアウトを左から右へ流す", () => {
    const layout = graphLayoutOptions();
    expect(layout.name).toBe("elk");
    expect(layout.elk["algorithm"]).toBe("layered");
    expect(layout.elk["elk.direction"]).toBe("RIGHT");
  });

  it("fit による拡大は等倍(maxZoom 1)を超えない", () => {
    // ノード数が少ない図(起点だけの初期表示など)で、1ノードが画面の大半を占めるほど
    // 拡大されることを防ぐ。maxZoom はコアの設定であり、レイアウト指定へ書いても効かない。
    const container = document.createElement("div");
    expect(graphCoreOptions(container).maxZoom).toBe(1);
    expect(graphCoreOptions(container).container).toBe(container);
    expect(graphCoreOptions(container).autoungrabify).toBe(true);
  });
});

describe("件数と閾値の警告", () => {
  it("種別ごとのノード件数を数える", () => {
    const counts = nodeKindCounts(SAMPLE_GRAPH);
    expect(counts.JOB).toBe(2);
    expect(counts.PROGRAM).toBe(6);
    expect(counts.PARAGRAPH).toBe(0);
    expect(counts.UNRESOLVED).toBe(1);
  });

  it("閾値以下では警告を出さない", () => {
    expect(graphWarning(VISIBLE_NODE_WARNING_THRESHOLD)).toBeNull();
  });

  it("閾値を超えると、フィルタと畳み込みを促す警告を出す", () => {
    const message = graphWarning(VISIBLE_NODE_WARNING_THRESHOLD + 1);
    expect(message).not.toBeNull();
    expect(message).toContain(String(VISIBLE_NODE_WARNING_THRESHOLD + 1));
    expect(message).toContain("ノード種別フィルタ");
  });
});

describe("成果物パスの導出", () => {
  it("SQLite の位置が未確定なら、作業フォルダ直下の既定名を使う", () => {
    expect(graphArtifactPaths(null)).toEqual({
      db: DEFAULT_DB_FILE,
      json: "callgraph.json",
      svg: "callgraph.svg",
      png: "callgraph.png",
    });
  });

  it("SQLite と同じフォルダへ JSON・SVG・PNG を書く(Windows 区切り)", () => {
    expect(graphArtifactPaths("C:\\proj\\out\\cobol-insight.db")).toEqual({
      db: "C:\\proj\\out\\cobol-insight.db",
      json: "C:\\proj\\out\\callgraph.json",
      svg: "C:\\proj\\out\\callgraph.svg",
      png: "C:\\proj\\out\\callgraph.png",
    });
  });

  it("スラッシュ区切りのパスでも同じフォルダへ導く", () => {
    expect(graphArtifactPaths("/var/proj/proj.db").json).toBe("/var/proj/callgraph.json");
  });
});

describe("ノード詳細", () => {
  const ids = visibleNodeIds(SAMPLE_GRAPH, { expanded: { "job:SYKD010": true }, kinds: ALL_KINDS });

  it("入出力エッジを相手側の表示名付きで返す", () => {
    const detail = nodeDetail(SAMPLE_GRAPH, "step:SYKD010.STEP010", ids);
    expect(detail?.incoming.map((edge) => edge.peerLabel)).toEqual(["SYKD010"]);
    expect(detail?.outgoing.map((edge) => edge.peerId)).toEqual([
      "dataset:SYKT.D250718.ORDER.DAILY",
      "dataset:SYKW.D250718.ORDER.VALID",
      "program:SYK001",
    ]);
    expect(detail?.outgoing.every((edge) => !edge.dashed)).toBe(true);
  });

  it("属性を一覧化し、未表示の隣接件数を数える", () => {
    const detail = nodeDetail(SAMPLE_GRAPH, "unresolved:WS-PROG-NAME", ids);
    expect(detail?.attributes).toEqual([{ key: "variable", value: "WS-PROG-NAME" }]);
    expect(detail?.kindStyle.label).toBe("未解決");
    // program:SYK002 はこの可視集合に無いため、未表示の隣接が 1 件ある。
    expect(detail?.hiddenNeighborCount).toBe(1);
  });

  it("隣接を持たないノードは展開できない", () => {
    const data: CallGraphData = {
      nodes: [{ id: "program:A", kind: "PROGRAM", label: "A", attributes: {} }],
      edges: [],
    };
    expect(nodeDetail(data, "program:A", new Set(["program:A"]))?.expandable).toBe(false);
  });

  it("存在しない ID は null を返す", () => {
    expect(nodeDetail(SAMPLE_GRAPH, "program:NONE", ids)).toBeNull();
  });
});

describe("ソースへのジャンプ先", () => {
  it("プログラム・ジョブ・BMS マップを資産一覧の相対パスへ対応づける", () => {
    const program = SAMPLE_GRAPH.nodes.find((node) => node.id === "program:SYK001");
    const job = SAMPLE_GRAPH.nodes.find((node) => node.id === "job:SYKD010");
    const map = SAMPLE_GRAPH.nodes.find((node) => node.id === "bmsmap:SYKMAP1.SYKM01");
    expect(graphSourcePaths(program!, SAMPLE_INVENTORY)).toEqual(["cobol/SYK001.cbl"]);
    expect(graphSourcePaths(job!, SAMPLE_INVENTORY)).toEqual(["jcl/SYKD010.jcl"]);
    expect(graphSourcePaths(map!, SAMPLE_INVENTORY)).toEqual(["bms/SYKMAP1.bms"]);
  });

  it("資産一覧に対応が無いノードは空を返す", () => {
    const dataset = SAMPLE_GRAPH.nodes.find((node) => node.id === "dataset:SYKT.D250718.ORDER.DAILY");
    const program = SAMPLE_GRAPH.nodes.find((node) => node.id === "program:SYK006");
    expect(graphSourcePaths(dataset!, SAMPLE_INVENTORY)).toEqual([]);
    expect(graphSourcePaths(program!, SAMPLE_INVENTORY)).toEqual([]);
  });

  it("同名のファイルが別フォルダにある場合は候補を落とさず、相対パス全体で区別できるよう全件を返す", () => {
    const program = SAMPLE_GRAPH.nodes.find((node) => node.id === "program:SYK001");
    const inventory = [
      ...SAMPLE_INVENTORY,
      {
        id: 7,
        path: "cobol-old/SYK001.cbl",
        name: "SYK001.cbl",
        type: "PROGRAM",
        codepage: "windows-31j",
        byteSize: 4100,
        findingCount: 0,
      },
    ];
    expect(graphSourcePaths(program!, inventory)).toEqual([
      "cobol-old/SYK001.cbl",
      "cobol/SYK001.cbl",
    ]);
  });
});

describe("終了コードの提示", () => {
  it("成功(0)では警告を出さない", () => {
    expect(graphExitBanner(0)).toBeNull();
  });

  it("警告あり(1)・エラーあり(2)は図を隠さず、起きたことを内部の終了コードに触れずに示す", () => {
    expect(graphExitBanner(1)).toContain("警告");
    expect(graphExitBanner(1)).not.toContain("終了コード");
    expect(graphExitBanner(2)).not.toContain("終了コード");
    expect(graphExitBanner(2)).toContain("解析エラー");
    // 解析エラーの資産は図から消えるのではなく「解析不能」ノードとして現れることを伝える。
    expect(graphExitBanner(2)).toContain("解析不能");
  });
});

describe("解析不能ノードの案内", () => {
  it("解析不能ノードが無ければ件数は0で、案内は出さない", () => {
    expect(unanalyzableCount(SAMPLE_GRAPH)).toBe(0);
    expect(unanalyzableBanner(0)).toBeNull();
  });

  it("解析不能ノードがあれば件数を数え、案内文に件数を含める", () => {
    expect(unanalyzableCount(SAMPLE_GRAPH_WITH_UNANALYZABLE)).toBe(1);
    const banner = unanalyzableBanner(1);
    expect(banner).toContain("1");
    expect(banner).toContain("解析不能");
  });
});
