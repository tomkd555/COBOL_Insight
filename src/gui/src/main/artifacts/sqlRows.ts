/**
 * sql.js(WASM・オフライン)で SQLite を読むときの共通型と行の読み口。列順に依存せず列名で値を取り出し、
 * SQLite の緩い型付けを TypeScript の値へ狭める。SQL 文と取り出し先の interface は各読取モジュールが持つ。
 */

/** sql.js の1セルの値域。 */
export type SqlCellValue = string | number | Uint8Array | null;

/** sql.js Database.exec の返す結果1件分。 */
export interface SqlExecResult {
  columns: string[];
  values: SqlCellValue[][];
}

/** sql.js の bind パラメータ(名前付き `$name` または位置指定)。 */
export type SqlBindParams = Record<string, SqlCellValue> | SqlCellValue[];

/** SQLite 読取に要する、sql.js Database の最小インターフェース。 */
export interface QueryableDatabase {
  exec(sql: string, params?: SqlBindParams): SqlExecResult[];
}

/** 1行から列名で値を取り出す読み口。想定外の型・列の不在は既定値へ落とす。 */
export interface SqlRow {
  /** 文字列列。NULL・非文字列は fallback。 */
  text(column: string, fallback: string): string;
  /** NULL 可の文字列列(SOURCE.codepage 等)。 */
  textOrNull(column: string): string | null;
  /** 整数列。NULL・非数値は fallback。 */
  int(column: string, fallback: number): number;
  /** NULL 可の整数列(CALL_EDGE.line 等)。 */
  intOrNull(column: string): number | null;
}

function rowReader(values: SqlCellValue[], index: Map<string, number>): SqlRow {
  const cell = (column: string): SqlCellValue => {
    const position = index.get(column);
    return position === undefined ? null : values[position];
  };
  return {
    text: (column, fallback) => {
      const value = cell(column);
      return typeof value === "string" ? value : fallback;
    },
    textOrNull: (column) => {
      const value = cell(column);
      return typeof value === "string" ? value : null;
    },
    int: (column, fallback) => {
      const value = cell(column);
      return typeof value === "number" ? value : fallback;
    },
    intOrNull: (column) => {
      const value = cell(column);
      return typeof value === "number" ? value : null;
    },
  };
}

/** exec の結果(先頭の1文分)を列名で引きながら1件ずつ変換する。結果が無ければ空配列。 */
export function mapRows<T>(results: SqlExecResult[], map: (row: SqlRow) => T): T[] {
  if (results.length === 0) {
    return [];
  }
  const { columns, values } = results[0];
  const index = new Map(columns.map((name, position) => [name, position]));
  return values.map((row) => map(rowReader(row, index)));
}
