import type { AssetInventoryItem } from "../../shared/ipc";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * The lower bound of the graph layer's ids (nodes with no source, graph edges, linker findings).
 * Findings at or above it are excluded so only scan-derived findings are counted.
 * Keep in step with ScanRunner.GRAPH_ID_BASE.
 */
const GRAPH_ID_BASE = 1_000_000_000_000;

const INVENTORY_QUERY = `
  SELECT s.id AS id,
         s.path AS path,
         s.codepage AS codepage,
         s.byte_size AS byteSize,
         n.type AS type,
         (SELECT COUNT(*) FROM FINDING f
           WHERE f.source_id = s.id AND f.id < ${GRAPH_ID_BASE}) AS findingCount
    FROM SOURCE s
    LEFT JOIN NODE n ON n.id = s.id
   ORDER BY s.path
`;

/**
 * Joins the SOURCE table of a scanned project file with NODE.type and the scan finding count to
 * produce the asset inventory the explorer shows. scan's summary JSON carries no inventory
 * (codepage, kind, counts), so this query is the explorer's only source. It relies on the
 * NODE.id = SOURCE.id convention.
 */
export function readInventory(db: QueryableDatabase): AssetInventoryItem[] {
  return mapRows(db.exec(INVENTORY_QUERY), (row) => {
    const path = row.text("path", "");
    return {
      id: row.int("id", 0),
      path,
      name: basename(path),
      type: row.text("type", "UNKNOWN"),
      codepage: row.textOrNull("codepage"),
      byteSize: row.int("byteSize", 0),
      findingCount: row.int("findingCount", 0),
    };
  });
}

function basename(path: string): string {
  const parts = path.split(/[\\/]/);
  return parts[parts.length - 1];
}
