import type { FlowEdge, FlowParagraph, ProgramFlow } from "../../shared/ipc";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * Reads the paragraphs of one program and the flows between them from a scanned project file.
 *
 * The call graph reads these tables for a whole asset folder; the source view traces one program,
 * and a folder's worth of paragraphs is far more than it can use, so this asks for the one asset.
 * The asset is named the way the inventory names it: the asset folder as the engine received it,
 * and the path relative to it.
 */

/** Paragraph ids rise in definition order, which is the order the flow between them follows. */
const PARAGRAPH_QUERY = `
  SELECT p.id AS id,
         p.name AS name,
         p.kind AS kind,
         p.section_name AS sectionName,
         p.start_line AS startLine,
         p.end_line AS endLine
    FROM PARAGRAPH p
    JOIN PROGRAM g ON g.id = p.program_id
    JOIN SOURCE s ON s.id = g.source_id
   WHERE s.root = $root COLLATE NOCASE AND s.path = $path COLLATE NOCASE
   ORDER BY p.id
`;

/**
 * A CALL or XCTL names a program; the asset that declares that PROGRAM-ID in the same folder is
 * looked up here, so the trace can open it.
 */
const EDGE_QUERY = `
  SELECT e.from_paragraph AS fromParagraph,
         e.to_paragraph AS toParagraph,
         e.to_name AS toName,
         e.kind AS kind,
         e.thru_paragraph AS thruParagraph,
         e.thru_name AS thruName,
         e.line AS line,
         e.seq AS seq,
         CASE WHEN e.kind IN ('CALL', 'XCTL') THEN (
           SELECT t.path
             FROM PROGRAM tp
             JOIN SOURCE t ON t.id = tp.source_id
            WHERE t.root = $root COLLATE NOCASE
              AND tp.program_id_name = e.to_name COLLATE NOCASE
            ORDER BY tp.id
            LIMIT 1
         ) END AS toPath
    FROM PARAGRAPH_EDGE e
    JOIN SOURCE s ON s.id = e.program_source_id
   WHERE s.root = $root COLLATE NOCASE AND s.path = $path COLLATE NOCASE
   ORDER BY e.from_paragraph, e.seq, e.id
`;

export function readProgramFlow(db: QueryableDatabase, root: string, relPath: string): ProgramFlow {
  const params = { $root: root, $path: relPath };
  const paragraphs = mapRows<FlowParagraph>(db.exec(PARAGRAPH_QUERY, params), (row) => ({
    id: row.int("id", 0),
    name: row.text("name", ""),
    kind: row.text("kind", "PARAGRAPH"),
    sectionName: row.textOrNull("sectionName"),
    startLine: row.int("startLine", 0),
    endLine: row.int("endLine", 0),
  }));
  const edges = mapRows<FlowEdge>(db.exec(EDGE_QUERY, params), (row) => ({
    from: row.int("fromParagraph", 0),
    to: row.intOrNull("toParagraph"),
    toName: row.text("toName", ""),
    kind: row.text("kind", ""),
    thru: row.intOrNull("thruParagraph"),
    thruName: row.textOrNull("thruName"),
    line: row.intOrNull("line"),
    seq: row.int("seq", 0),
    toPath: row.textOrNull("toPath"),
  }));
  return { paragraphs, edges };
}
