/**
 * Shared types and the row reader for the SQLite reads done through sql.js (WASM, offline). Values
 * are taken by column name rather than by position, and SQLite's loose typing is narrowed to
 * TypeScript values. Each reader module owns its own SQL and result interface.
 */

/**
 * The lower bound of the graph layer's ids (nodes with no source, graph edges, linker findings).
 * Below it an id is a SOURCE.id. Keep in step with Persist.GRAPH_ID_BASE.
 */
export const GRAPH_ID_BASE = 1_000_000_000_000;

/** The value domain of one sql.js cell. */
export type SqlCellValue = string | number | Uint8Array | null;

/** One result of sql.js Database.exec. */
export interface SqlExecResult {
  columns: string[];
  values: SqlCellValue[][];
}

/** sql.js bind parameters, either named ($name) or positional. */
export type SqlBindParams = Record<string, SqlCellValue> | SqlCellValue[];

/** The minimum sql.js Database interface the readers need. */
export interface QueryableDatabase {
  exec(sql: string, params?: SqlBindParams): SqlExecResult[];
}

/** Reads one row by column name, falling back where the type or the column is not what was expected. */
export interface SqlRow {
  /** A text column. NULL and non-strings fall back. */
  text(column: string, fallback: string): string;
  /** A nullable text column (SOURCE.codepage and the like). */
  textOrNull(column: string): string | null;
  /** An integer column. NULL and non-numbers fall back. */
  int(column: string, fallback: number): number;
  /** A nullable integer column (CALL_EDGE.line and the like). */
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

/** Maps the first statement's rows one by one, reading columns by name. No result yields []. */
export function mapRows<T>(results: SqlExecResult[], map: (row: SqlRow) => T): T[] {
  if (results.length === 0) {
    return [];
  }
  const { columns, values } = results[0];
  const index = new Map(columns.map((name, position) => [name, position]));
  return values.map((row) => map(rowReader(row, index)));
}
