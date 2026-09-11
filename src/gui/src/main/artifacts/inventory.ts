import type { AssetInventoryItem } from "../../shared/ipc";
import { GRAPH_ID_BASE, mapRows, type QueryableDatabase } from "./sqlRows";

const INVENTORY_QUERY = `
  SELECT s.id AS id,
         s.path AS path,
         s.codepage AS codepage,
         n.type AS type,
         (SELECT COUNT(*) FROM FINDING f
           WHERE f.source_id = s.id AND f.id < ${GRAPH_ID_BASE}) AS findingCount
    FROM SOURCE s
    LEFT JOIN NODE n ON n.id = s.id
   WHERE s.root = $root COLLATE NOCASE
   ORDER BY s.path
`;

/**
 * Joins the SOURCE table of a scanned project file with NODE.type and the scan finding count to
 * produce the asset inventory the explorer shows. scan's summary JSON carries no inventory
 * (codepage, kind, counts), so this query is the explorer's only source. It relies on the
 * NODE.id = SOURCE.id convention.
 *
 * One project file holds every asset folder ever scanned, so `root` — the absolute asset folder, the
 * same string the engine received as INPUT_DIR — selects the one the screen is showing. Without it
 * the assets of the folders opened before stay on screen.
 */
export function readInventory(db: QueryableDatabase, root: string): AssetInventoryItem[] {
  return mapRows(db.exec(INVENTORY_QUERY, { $root: root }), (row) => {
    const path = row.text("path", "");
    return {
      id: row.int("id", 0),
      path,
      name: basename(path),
      type: row.text("type", "UNKNOWN"),
      codepage: row.textOrNull("codepage"),
      findingCount: row.int("findingCount", 0),
    };
  });
}

function basename(path: string): string {
  const parts = path.split(/[\\/]/);
  return parts[parts.length - 1];
}
