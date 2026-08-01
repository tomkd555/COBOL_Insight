/**
 * テスト用の呼出関係グラフ fixture。samples を callgraph サブコマンドへ通した実出力
 * (gui/src/main/__fixtures__/callgraph.json)の形と ID 体系(job:/step:/program:/dataset:/
 * db2:/transaction:/bmsmap:/unresolved:/utility: の接頭辞)にそろえた抜粋である。
 * 実出力に現れない解決根拠と種別も検証できるよう、データフロー由来の CALL・未解決ノード・
 * 外部ユーティリティを加えている。
 */

import type { CallGraphData } from "../../../../shared/engine-api";

export const SAMPLE_GRAPH: CallGraphData = {
  nodes: [
    { id: "bmsmap:SYKMAP1.SYKM01", kind: "BMS_MAP", label: "SYKMAP1.SYKM01", attributes: {} },
    { id: "dataset:SYKT.D250718.ORDER.DAILY", kind: "DATASET", label: "SYKT.D250718.ORDER.DAILY", attributes: {} },
    { id: "dataset:SYKW.D250718.ORDER.VALID", kind: "DATASET", label: "SYKW.D250718.ORDER.VALID", attributes: {} },
    { id: "db2:SYKDB.ZAIKOM", kind: "DB2_TABLE", label: "SYKDB.ZAIKOM", attributes: {} },
    { id: "job:SYKD010", kind: "JOB", label: "SYKD010", attributes: {} },
    { id: "job:SYKD020", kind: "JOB", label: "SYKD020", attributes: {} },
    { id: "program:SYK001", kind: "PROGRAM", label: "SYK001", attributes: {} },
    { id: "program:SYK002", kind: "PROGRAM", label: "SYK002", attributes: {} },
    { id: "program:SYK003", kind: "PROGRAM", label: "SYK003", attributes: {} },
    { id: "program:SYK004", kind: "PROGRAM", label: "SYK004", attributes: {} },
    { id: "program:SYK006", kind: "PROGRAM", label: "SYK006", attributes: {} },
    { id: "program:SYK008", kind: "PROGRAM", label: "SYK008", attributes: {} },
    { id: "step:SYKD010.STEP010", kind: "STEP", label: "STEP010", attributes: {} },
    { id: "step:SYKD010.STEP020", kind: "STEP", label: "STEP020", attributes: {} },
    { id: "step:SYKD020.STEP010", kind: "STEP", label: "STEP010", attributes: {} },
    { id: "transaction:SYK8", kind: "TRANSACTION", label: "SYK8", attributes: {} },
    { id: "unresolved:WS-PROG-NAME", kind: "UNRESOLVED", label: "WS-PROG-NAME", attributes: { variable: "WS-PROG-NAME" } },
    { id: "utility:DFSORT", kind: "EXTERNAL_UTILITY", label: "DFSORT", attributes: { utility: "DFSORT" } },
  ],
  edges: [
    { from: "job:SYKD010", to: "step:SYKD010.STEP010", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "job:SYKD010", to: "step:SYKD010.STEP020", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "job:SYKD020", to: "step:SYKD020.STEP010", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "program:SYK001", to: "program:SYK003", kind: "CALL", resolution: "CONSTANT" },
    { from: "program:SYK002", to: "program:SYK004", kind: "CALL", resolution: "DATAFLOW" },
    { from: "program:SYK002", to: "unresolved:WS-PROG-NAME", kind: "CALL", resolution: "UNRESOLVED" },
    { from: "program:SYK006", to: "db2:SYKDB.ZAIKOM", kind: "REFERENCE", resolution: "CONSTANT" },
    { from: "program:SYK008", to: "bmsmap:SYKMAP1.SYKM01", kind: "MAP_REFERENCE", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP010", to: "dataset:SYKT.D250718.ORDER.DAILY", kind: "REFERENCE", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP010", to: "dataset:SYKW.D250718.ORDER.VALID", kind: "REFERENCE", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP010", to: "program:SYK001", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "step:SYKD010.STEP020", to: "program:SYK002", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "step:SYKD020.STEP010", to: "utility:DFSORT", kind: "EXECUTION", resolution: "CONSTANT" },
    { from: "transaction:SYK8", to: "program:SYK008", kind: "TRANSACTION_TRANSITION", resolution: "CONSTANT" },
  ],
};

/**
 * 解析不能ノードを1件含む fixture。構文解析に失敗した資産はエッジを持たない孤立ノードとして
 * 来るため、SAMPLE_GRAPH に足すと総ノード数の前提(全 18 ノード)を使う既存テストを崩す。
 * そのため SAMPLE_GRAPH に解析不能ノードを1件加えた別 fixture として持つ。
 */
export const SAMPLE_GRAPH_WITH_UNANALYZABLE: CallGraphData = {
  nodes: [
    ...SAMPLE_GRAPH.nodes,
    {
      id: "unanalyzable:BROKEN1.cbl",
      kind: "UNANALYZABLE",
      label: "BROKEN1.cbl",
      attributes: { path: "cobol/BROKEN1.cbl", reason: "予期しないトークンで構文解析が中断した" },
    },
  ],
  edges: SAMPLE_GRAPH.edges,
};
