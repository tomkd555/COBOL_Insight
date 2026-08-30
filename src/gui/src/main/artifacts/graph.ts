import type {
  GraphData,
  GraphEdge,
  GraphNode,
  GraphParagraph,
  GraphParagraphEdge,
} from "../../shared/ipc";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * Reads the call graph from a scanned project file. The call-graph view does not launch the
 * call-graph subcommand; it reads the tables scan already wrote (only SVG and PNG export need the
 * subcommand).
 *
 * Nodes cover both the rows that correspond to assets (id < GRAPH_ID_BASE, the same value as
 * SOURCE.id) and the graph-layer rows that do not (jobs, steps, datasets). Execution order lives on
 * the graph-layer edges; the older EXECUTION rows between assets keep seq 0.
 */

const NODE_QUERY = `
  SELECT id AS id, type AS type, label AS label
    FROM NODE
   ORDER BY id
`;

/**
 * Edges are ordered by execution order within each origin. seq is 1-based and 0 means no order could
 * be established; putting 0 first would break up the ordered run, so it is sorted last.
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

/** Paragraphs reach their asset through PROGRAM. They are ordered as they appear in the source. */
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
