/**
 * The node:fs adapters that satisfy the small filesystem interfaces each module declares, plus the
 * sql.js loader.
 *
 * The modules that do the work take a filesystem as a parameter, so their tests can substitute
 * one. This is the one place the real thing is supplied.
 */

import { constants } from "node:fs";
import {
  access,
  copyFile,
  lstat,
  mkdir,
  open,
  readdir,
  readFile,
  realpath,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
import type { GeneratedFileSystem } from "./artifacts/transpile";
import type { DecodeFileSystem } from "./engine/decode";
import type { SaveFileSystem } from "./engine/save";
import type { RulesFileSystem } from "./engine/rules";
import type { ImportFileSystem } from "./fs/importSource";
import type { RulesFileSystem as UserDataFileSystem } from "./fs/rulesFile";
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
export const userDataFileSystem: UserDataFileSystem = {
  readText: (path) => readFile(path, "utf-8"),
  readBytes: (path) => readFile(path),
  // Whole or as it was: if the application stops midway, the file keeps the settings and the rules
  // the user had. A name that is a link has the file it leads to replaced, so the link stays a link.
  writeText: async (path, text) => replaceFile(await realOrNew(path), text, "write"),
  exists,
  copyNew: (from, to) =>
    copyFile(from, to, constants.COPYFILE_EXCL).then(
      () => true,
      (error: unknown) => {
        if ((error as { code?: unknown } | null)?.code === "EEXIST") {
          return false;
        }
        throw error;
      },
    ),
};

/** The real path of a file that is there, with links resolved; the path itself for a new file. */
async function realOrNew(path: string): Promise<string> {
  try {
    return await realpath(path);
  } catch (error) {
    if ((error as { code?: unknown } | null)?.code === "ENOENT") {
      return path;
    }
    throw error;
  }
}

/** Checking that a copybook search path exists. */
export const directoryStat: DirectoryStat = { stat: (path) => stat(path) };

/** Writing an imported source file; the boundary check needs real paths. */
export const importFileSystem: ImportFileSystem = {
  makeDir: async (absPath) => {
    await mkdir(absPath, { recursive: true });
  },
  exists,
  entry: (absPath) =>
    lstat(absPath).then(
      (stats) => (stats.isSymbolicLink() ? "link" : stats.isDirectory() ? "folder" : "file"),
      (error: unknown) => {
        // Nothing there: a name on the path is missing (ENOENT) or is a file (ENOTDIR). Any other
        // failure leaves the question open, and an open question must stop the write.
        const code = (error as { code?: unknown } | null)?.code;
        if (code === "ENOENT" || code === "ENOTDIR") {
          return null;
        }
        throw error;
      },
    ),
  realPath: (absPath) => realpath(absPath),
  writeText: async (absPath, text, replace) => {
    if (!replace) {
      // Created only where nothing has the name; a link put there since the check counts as taken.
      try {
        await writeFile(absPath, text, { encoding: "utf-8", flag: "wx" });
        return true;
      } catch (error) {
        if ((error as { code?: unknown } | null)?.code === "EEXIST") {
          return false;
        }
        throw error;
      }
    }
    await replaceFile(absPath, text, "import");
    return true;
  },
};

/**
 * Puts the text under the name through a new file beside it, which then takes the name. Opening the
 * file that has the name would write through to every other name it has (a hard link) and through a
 * link put there since the check, and a failure midway would leave half a file. The new file's name
 * starts with a dot, which the engine's scan passes over, and has one length whatever the name.
 * The new file is flushed to disk before it takes the name, so the name never leads to content the
 * system still holds in memory.
 */
async function replaceFile(absPath: string, text: string, suffix: string): Promise<void> {
  const fresh = join(dirname(absPath), `.${randomUUID()}.${suffix}`);
  try {
    const handle = await open(fresh, "wx");
    try {
      await handle.writeFile(text, "utf-8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(fresh, absPath);
  } catch (error) {
    await rm(fresh, { force: true });
    throw error;
  }
}

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
