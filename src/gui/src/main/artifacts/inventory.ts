import type { AssetInventoryItem } from "../../shared/engine-api";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/**
 * 呼出関係グラフ層(ソース非対応ノード・グラフ辺・linker 由来 finding)の ID 下限。
 * scan 由来の finding だけを数えるため、この値以上の finding を除く。
 * ScanRunner.GRAPH_ID_BASE と一致させること。
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
 * scan 済み SQLite の SOURCE 表を NODE.type・scan 由来 finding 件数と結合し、資産一覧を返す純関数
 * (資産エクスプローラー画面の供給源)。scan の stdout サマリ JSON にはインベントリ(コードページ・
 * 種別・件数)が無いため、画面はこの問い合わせの結果だけを資産一覧として用いる。
 * NODE.id=SOURCE.id 規約に依存する。
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
