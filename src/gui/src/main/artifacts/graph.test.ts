import { describe, it, expect, beforeAll } from "vitest";
import { createRequire } from "node:module";
import initSqlJs, { type Database } from "sql.js";
import { readGraph } from "./graph";
import type { QueryableDatabase } from "./sqlRows";

const require = createRequire(import.meta.url);

/** グラフ層の ID 下限(engine の ScanRunner.GRAPH_ID_BASE)。 */
const GRAPH_ID_BASE = 1_000_000_000_000;

/** schema v3 のうち、呼出関係の読取が触れる表だけを起こす。 */
const DDL = `
  CREATE TABLE NODE (id INTEGER PRIMARY KEY, type TEXT NOT NULL, label TEXT NOT NULL);
  CREATE TABLE CALL_EDGE (
    id INTEGER PRIMARY KEY, from_node INTEGER NOT NULL, to_node INTEGER NOT NULL,
    kind TEXT NOT NULL, resolution TEXT, host_var TEXT,
    seq INTEGER NOT NULL DEFAULT 0, line INTEGER
  );
  CREATE TABLE PROGRAM (id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL,
    program_id_name TEXT NOT NULL);
  CREATE TABLE PARAGRAPH (id INTEGER PRIMARY KEY, program_id INTEGER NOT NULL,
    name TEXT NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL);
  CREATE TABLE PARAGRAPH_EDGE (
    id INTEGER PRIMARY KEY, program_source_id INTEGER NOT NULL,
    from_paragraph INTEGER NOT NULL, to_paragraph INTEGER, to_name TEXT NOT NULL,
    kind TEXT NOT NULL, line INTEGER, seq INTEGER NOT NULL
  );
`;

/**
 * 行の投入順を実行順とわざとずらして入れる。並び替えを SQL 側が行っていることを確かめるためである。
 * ジョブ(1000...)・ステップ・プログラムはグラフ層、資産に対応するノードは SOURCE.id と同じ小さい ID。
 */
const ROWS = `
  INSERT INTO NODE (id, type, label) VALUES
    (2, 'PROGRAM', 'SYK001'),
    (3, 'PROGRAM', 'SYK002'),
    (${GRAPH_ID_BASE + 1}, 'JOB', 'SYKJOB1'),
    (${GRAPH_ID_BASE + 2}, 'STEP', 'STEP01'),
    (${GRAPH_ID_BASE + 3}, 'STEP', 'STEP02');
  INSERT INTO CALL_EDGE (id, from_node, to_node, kind, resolution, seq, line) VALUES
    (11, ${GRAPH_ID_BASE + 1}, ${GRAPH_ID_BASE + 3}, 'EXECUTION', 'CONSTANT', 2, 40),
    (12, ${GRAPH_ID_BASE + 1}, ${GRAPH_ID_BASE + 2}, 'EXECUTION', 'CONSTANT', 1, 20),
    (13, 2, 3, 'CALL', 'DATAFLOW', 0, NULL),
    (14, 2, 3, 'CALL', 'CONSTANT', 1, 60);
  INSERT INTO PROGRAM (id, source_id, program_id_name) VALUES (7, 2, 'SYK001');
  INSERT INTO PARAGRAPH (id, program_id, name, start_line, end_line) VALUES
    (22, 7, '主処理', 100, 140),
    (21, 7, '初期処理', 30, 90);
  INSERT INTO PARAGRAPH_EDGE
    (id, program_source_id, from_paragraph, to_paragraph, to_name, kind, line, seq) VALUES
    (32, 2, 21, NULL, '存在しない段落', 'GOTO', 88, 2),
    (31, 2, 21, 22, '主処理', 'PERFORM', 55, 1);
`;

let graphDb: QueryableDatabase;

beforeAll(async () => {
  const wasmPath = require.resolve("sql.js/dist/sql-wasm.wasm");
  const SQL = await initSqlJs({ locateFile: () => wasmPath });
  const database: Database = new SQL.Database();
  database.run(DDL);
  database.run(ROWS);
  graphDb = database;
});

describe("readGraph", () => {
  it("資産のノードとグラフ層のノードを併せて返す", () => {
    const { nodes } = readGraph(graphDb);
    expect(nodes.map((node) => node.label)).toEqual([
      "SYK001",
      "SYK002",
      "SYKJOB1",
      "STEP01",
      "STEP02",
    ]);
    expect(nodes[2]).toEqual({ id: GRAPH_ID_BASE + 1, type: "JOB", label: "SYKJOB1" });
  });

  /** ジョブの中のステップは実行順に並ぶ。行の投入順ではない。 */
  it("辺を起点ごとに実行順で並べ、行番号を添える", () => {
    const { edges } = readGraph(graphDb);
    const steps = edges.filter((edge) => edge.from === GRAPH_ID_BASE + 1);
    expect(steps.map((edge) => edge.seq)).toEqual([1, 2]);
    expect(steps[0]).toEqual({
      from: GRAPH_ID_BASE + 1,
      to: GRAPH_ID_BASE + 2,
      kind: "EXECUTION",
      resolution: "CONSTANT",
      seq: 1,
      line: 20,
    });
  });

  /** 実行順を決められない辺は seq 0 のまま残る。順序の付いた辺の前へ割り込ませない。 */
  it("実行順の無い辺を起点の後ろへ回し、行番号は null で返す", () => {
    const calls = readGraph(graphDb).edges.filter((edge) => edge.from === 2);
    expect(calls.map((edge) => edge.seq)).toEqual([1, 0]);
    expect(calls[1]).toEqual({
      from: 2,
      to: 3,
      kind: "CALL",
      resolution: "DATAFLOW",
      seq: 0,
      line: null,
    });
  });

  it("段落を資産(SOURCE.id)へ結び付け、開始行の順に返す", () => {
    const { paragraphs } = readGraph(graphDb);
    expect(paragraphs).toEqual([
      { id: 21, programSourceId: 2, name: "初期処理", startLine: 30, endLine: 90 },
      { id: 22, programSourceId: 2, name: "主処理", startLine: 100, endLine: 140 },
    ]);
  });

  it("段落の流れを出現順に返し、解決できない行き先は名前だけ残す", () => {
    const { paragraphEdges } = readGraph(graphDb);
    expect(paragraphEdges).toEqual([
      {
        programSourceId: 2,
        from: 21,
        to: 22,
        toName: "主処理",
        kind: "PERFORM",
        line: 55,
        seq: 1,
      },
      {
        programSourceId: 2,
        from: 21,
        to: null,
        toName: "存在しない段落",
        kind: "GOTO",
        line: 88,
        seq: 2,
      },
    ]);
  });
});
