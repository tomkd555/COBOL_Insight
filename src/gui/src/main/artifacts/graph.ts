import type {
  GraphData,
  GraphEdge,
  GraphNode,
  GraphParagraph,
  GraphParagraphEdge,
} from "../../shared/ipc";
import { GRAPH_ID_BASE, mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * Reads the call graph from a scanned project file. The call-graph view launches no subcommand of
 * its own; it reads the tables scan already wrote.
 *
 * Nodes cover both the rows that correspond to assets (id < GRAPH_ID_BASE, the same value as
 * SOURCE.id) and the graph-layer rows that do not (jobs, steps, datasets). Execution order lives on
 * the graph-layer edges; the older EXECUTION rows between assets keep seq 0.
 *
 * One project file holds every asset folder ever scanned, so every query is confined to the assets
 * of one root — the absolute asset folder, as the engine received it. Graph-layer rows carry no
 * source of their own and are read whole.
 */

/** The assets of the root under view. Every query below is confined to these ids. */
const ROOT_SOURCES = `SELECT id FROM SOURCE WHERE root = $root COLLATE NOCASE`;

const NODE_QUERY = `
  SELECT id AS id, type AS type, label AS label
    FROM NODE
   WHERE id >= ${GRAPH_ID_BASE} OR id IN (${ROOT_SOURCES})
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
         line AS line,
         access AS access
    FROM CALL_EDGE
   WHERE (from_node >= ${GRAPH_ID_BASE} OR from_node IN (${ROOT_SOURCES}))
     AND (to_node >= ${GRAPH_ID_BASE} OR to_node IN (${ROOT_SOURCES}))
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
   WHERE g.source_id IN (${ROOT_SOURCES})
   ORDER BY g.source_id, p.start_line, p.id
`;

/**
 * The flows between paragraphs. A call of another program leaving a paragraph (CALL, XCTL) is for
 * the trace of one program (programFlow); here those calls are already drawn as program edges.
 */
const PARAGRAPH_EDGE_QUERY = `
  SELECT program_source_id AS programSourceId,
         from_paragraph AS fromParagraph,
         to_paragraph AS toParagraph,
         to_name AS toName,
         kind AS kind,
         line AS line,
         seq AS seq
    FROM PARAGRAPH_EDGE
   WHERE program_source_id IN (${ROOT_SOURCES})
     AND kind IN ('PERFORM', 'GOTO', 'FALLTHROUGH')
   ORDER BY program_source_id, from_paragraph, seq, id
`;

export function readGraph(db: QueryableDatabase, root: string): GraphData {
  const params = { $root: root };
  const assetNodes = mapRows<GraphNode>(db.exec(NODE_QUERY, params), (row) => ({
    id: row.int("id", 0),
    type: row.text("type", "UNKNOWN"),
    label: row.text("label", ""),
  }));
  const edges = mapRows<GraphEdge>(db.exec(EDGE_QUERY, params), (row) => ({
    from: row.int("fromNode", 0),
    to: row.int("toNode", 0),
    kind: row.text("kind", ""),
    resolution: row.textOrNull("resolution"),
    seq: row.int("seq", 0),
    line: row.intOrNull("line"),
    access: row.textOrNull("access"),
  }));
  const { nodes, edges: graphEdges } = withGraphKinds(assetNodes, edges);
  const paragraphs = mapRows<GraphParagraph>(db.exec(PARAGRAPH_QUERY, params), (row) => ({
    id: row.int("id", 0),
    programSourceId: row.int("programSourceId", 0),
    name: row.text("name", ""),
    startLine: row.int("startLine", 0),
    endLine: row.int("endLine", 0),
  }));
  const paragraphEdges = mapRows<GraphParagraphEdge>(
    db.exec(PARAGRAPH_EDGE_QUERY, params),
    (row) => ({
      programSourceId: row.int("programSourceId", 0),
      from: row.int("fromParagraph", 0),
      to: row.intOrNull("toParagraph"),
      toName: row.text("toName", ""),
      kind: row.text("kind", ""),
      line: row.intOrNull("line"),
      seq: row.int("seq", 0),
    }),
  );
  return { nodes, edges: graphEdges, paragraphs, paragraphEdges };
}

/**
 * The nodes with the kind the call graph knows each by, and the edges that belong to the graph.
 *
 * The engine gives a job and a program the NODE row of the file they stand in, so that row carries
 * the asset's type: `JCL` for the file of a job, `EASYTRIEVE` for an Easytrieve member. In the graph
 * those rows are the job and the program themselves, and every screen that draws or walks the graph
 * asks for JOB and PROGRAM.
 *
 * A JCL row is a job when it starts a step; a PROC or INCLUDE member has no step of its own and
 * stays an asset row, which the graph leaves out. An Easytrieve row is a program when an edge of the
 * graph touches it; a macro member is touched by none.
 *
 * A JCL file also carries an EXECUTION row straight to each program it runs. That row is what the
 * engine's incremental scan follows from a program back to the jobs that depend on it; it has no
 * step and no order, and the same execution is in the graph as job, step, program. Read as an edge
 * of the job it would be a step that is no step, so it is left out.
 */
function withGraphKinds(
  assetNodes: GraphNode[],
  edges: GraphEdge[],
): { nodes: GraphNode[]; edges: GraphEdge[] } {
  const typeById = new Map(assetNodes.map((node) => [node.id, node.type]));
  const jobs = new Set<number>();
  const linked = new Set<number>();
  for (const edge of edges) {
    linked.add(edge.from);
    linked.add(edge.to);
    if (edge.kind === "EXECUTION" && typeById.get(edge.to) === "STEP") {
      jobs.add(edge.from);
    }
  }
  const nodes = assetNodes.map((node) => {
    if (node.type === "JCL" && jobs.has(node.id)) {
      return { ...node, type: "JOB" };
    }
    if (node.type === "EASYTRIEVE" && linked.has(node.id)) {
      return { ...node, type: "PROGRAM" };
    }
    return node;
  });
  const graphEdges = edges.filter(
    (edge) =>
      !(
        edge.kind === "EXECUTION" &&
        typeById.get(edge.from) === "JCL" &&
        typeById.get(edge.to) !== "STEP"
      ),
  );
  return { nodes, edges: graphEdges };
}
