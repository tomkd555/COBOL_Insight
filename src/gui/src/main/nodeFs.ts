/**
 * The node:fs adapters that satisfy the small filesystem interfaces each module declares, plus the
 * sql.js loader.
 *
 * The modules that do the work take a filesystem rather than importing node:fs, so their tests can
 * substitute one. This is the one place the real thing is supplied.
 */

import { access, mkdir, readdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
import type { GeneratedFileSystem } from "./artifacts/transpile";
import type { DecodeFileSystem } from "./engine/decode";
import type { SaveFileSystem } from "./engine/save";
import type { RulesFileSystem } from "./engine/rules";
import type { ImportFileSystem } from "./fs/importSource";
import type { JsonFileSystem } from "./fs/jsonFile";
import type { DirectoryStat } from "./fs/selectFolder";

const nodeRequire = createRequire(import.meta.url);

const exists = (path: string): Promise<boolean> => access(path).then(() => true, () => false);

/** Reading the flat generated files translate wrote. */
export const generatedFileSystem: GeneratedFileSystem = {
  list: (dir) => readdir(dir),
  readText: (absPath) => readFile(absPath, "utf-8"),
};

/** Decoding: the boundary check needs real paths, and the result arrives in a scratch file. */
export const decodeFileSystem: DecodeFileSystem = {
  realPath: (absPath) => realpath(absPath),
  readText: (absPath) => readFile(absPath, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

/** Writing back: the boundary check needs real paths, and the edited text goes to a scratch file. */
export const saveFileSystem: SaveFileSystem = {
  realPath: (absPath) => realpath(absPath),
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

/** Validating candidate rule text, which is handed to the engine through a scratch file. */
export const rulesFileSystem: RulesFileSystem = {
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

/**
 * The settings and the rule file. Both live under userData at paths main decides; the renderer never
 * names an arbitrary path for them.
 */
export const userDataFileSystem: JsonFileSystem = {
  readText: (path) => readFile(path, "utf-8"),
  writeText: (path, text) => writeFile(path, text, "utf-8"),
  exists,
};

/** Checking that a copybook search path exists. */
export const directoryStat: DirectoryStat = { stat: (path) => stat(path) };

/** Writing an imported source file; the boundary check needs real paths. */
export const importFileSystem: ImportFileSystem = {
  makeDir: async (absPath) => {
    await mkdir(absPath, { recursive: true });
  },
  exists,
  realPath: (absPath) => realpath(absPath),
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
};

let sqlJs: SqlJsStatic | null = null;

/** Initialises sql.js once. The wasm comes from the local bundle and is never fetched. */
async function loadSqlJs(): Promise<SqlJsStatic> {
  if (sqlJs === null) {
    const wasmPath = nodeRequire.resolve("sql.js/dist/sql-wasm.wasm");
    sqlJs = await initSqlJs({ locateFile: () => wasmPath });
  }
  return sqlJs;
}

/**
 * Reads the SQLite file as bytes, opens it in memory, hands it to a reader and always closes it.
 * sql.js works on the copy it read, so the project file the engine wrote is never written back to.
 */
export async function withDatabase<T>(dbPath: string, read: (db: Database) => T): Promise<T> {
  const SQL = await loadSqlJs();
  const database = new SQL.Database(await readFile(dbPath));
  try {
    return read(database);
  } finally {
    database.close();
  }
}
