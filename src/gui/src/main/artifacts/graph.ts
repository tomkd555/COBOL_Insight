import type {
  GraphData,
  GraphEdge,
  GraphNode,
  GraphParagraph,
  GraphParagraphEdge,
} from "../../shared/engine-api";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * 走査済み SQLite から呼出関係グラフを読む純関数。呼出関係タブは call-graph サブコマンドを
 * 起動せず、scan が書いたこの表をそのまま読む(SVG・PNG の書き出しだけが call-graph を要する)。
 *
 * ノードは資産に対応する行(id < GRAPH_ID_BASE、SOURCE.id と同じ値)と、資産に対応しない
 * グラフ層の行(id ≥ GRAPH_ID_BASE、ジョブ・ステップ・データセットなど)の双方を返す。
 * 実行順を持つのはグラフ層の辺であり、資産どうしを結ぶ古い EXECUTION 行は seq 0 のまま残る。
 */

const NODE_QUERY = `
  SELECT id AS id, type AS type, label AS label
    FROM NODE
   ORDER BY id
`;

/**
 * 辺は起点ごとに実行順で並べる。seq は 1 起点で、順序を決められない辺は 0 である。
 * 0 を先頭へ置くと順序の付いた辺の並びが崩れるため、0 は後ろへ回す。
 */
const EDGE_QUERY = `
  SELECT from_node AS fromNode,
         to_node AS toNode,
         kind AS kind,
         resolution AS resolution,
         seq AS seq,
         line AS line
    FROM CALL_EDGE
   ORDER BY from_node, CASE WHEN seq = 0 THEN 1 ELSE 0 END, seq, id
`;

/** 段落は PROGRAM を挟んで資産(SOURCE.id)へ結び付ける。並びはソースの現れる順である。 */
const PARAGRAPH_QUERY = `
  SELECT p.id AS id,
         g.source_id AS programSourceId,
         p.name AS name,
         p.start_line AS startLine,
         p.end_line AS endLine
    FROM PARAGRAPH p
    JOIN PROGRAM g ON g.id = p.program_id
   ORDER BY g.source_id, p.start_line, p.id
`;

const PARAGRAPH_EDGE_QUERY = `
  SELECT program_source_id AS programSourceId,
         from_paragraph AS fromParagraph,
         to_paragraph AS toParagraph,
         to_name AS toName,
         kind AS kind,
         line AS line,
         seq AS seq
    FROM PARAGRAPH_EDGE
   ORDER BY program_source_id, from_paragraph, seq, id
`;

export function readGraph(db: QueryableDatabase): GraphData {
  const nodes = mapRows<GraphNode>(db.exec(NODE_QUERY), (row) => ({
    id: row.int("id", 0),
    type: row.text("type", "UNKNOWN"),
    label: row.text("label", ""),
  }));
  const edges = mapRows<GraphEdge>(db.exec(EDGE_QUERY), (row) => ({
    from: row.int("fromNode", 0),
    to: row.int("toNode", 0),
    kind: row.text("kind", ""),
    resolution: row.textOrNull("resolution"),
    seq: row.int("seq", 0),
    line: row.intOrNull("line"),
  }));
  const paragraphs = mapRows<GraphParagraph>(db.exec(PARAGRAPH_QUERY), (row) => ({
    id: row.int("id", 0),
    programSourceId: row.int("programSourceId", 0),
    name: row.text("name", ""),
    startLine: row.int("startLine", 0),
    endLine: row.int("endLine", 0),
  }));
  const paragraphEdges = mapRows<GraphParagraphEdge>(db.exec(PARAGRAPH_EDGE_QUERY), (row) => ({
    programSourceId: row.int("programSourceId", 0),
    from: row.int("fromParagraph", 0),
    to: row.intOrNull("toParagraph"),
    toName: row.text("toName", ""),
    kind: row.text("kind", ""),
    line: row.intOrNull("line"),
    seq: row.int("seq", 0),
  }));
  return { nodes, edges, paragraphs, paragraphEdges };
}
