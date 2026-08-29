/**
 * Test helpers for opening a SQLite database with sql.js: the checked-in scanned fixture, and an
 * empty database the tests fill with exactly the rows one query needs.
 */

import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";

const nodeRequire = createRequire(import.meta.url);

let sqlJs: SqlJsStatic | null = null;

async function loadSqlJs(): Promise<SqlJsStatic> {
  if (sqlJs === null) {
    const wasmPath = nodeRequire.resolve("sql.js/dist/sql-wasm.wasm");
    sqlJs = await initSqlJs({ locateFile: () => wasmPath });
  }
  return sqlJs;
}

/** The path of a file under __fixtures__. */
export function fixturePath(name: string): string {
  return join(__dirname, "..", "__fixtures__", name);
}

/** Opens the checked-in project file produced by a real scan of samples/. */
export async function openFixtureDatabase(): Promise<Database> {
  const SQL = await loadSqlJs();
  return new SQL.Database(readFileSync(fixturePath("sample.db")));
}

/** Opens an empty in-memory database and runs the given DDL and inserts against it. */
export async function openDatabaseWith(sql: string): Promise<Database> {
  const SQL = await loadSqlJs();
  const database = new SQL.Database();
  database.run(sql);
  return database;
}
