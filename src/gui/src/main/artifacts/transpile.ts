/**
 * Reads the translate artefacts. The runner writes its generated files flat under the output
 * directory, and the correspondence between COBOL lines and generated lines lives in the LINE_MAP
 * table. Generated file names derive from the PROGRAM-ID and record names and do not match the COBOL
 * file name, so LINE_MAP.gen_file is the only thing that says which file translates which source.
 */

import { join } from "node:path";
import type { LineMapEntry, TranspileGeneratedFile, TranspileLanguage } from "../../shared/ipc";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/** Listing a directory and reading UTF-8 text is all this needs. */
export interface GeneratedFileSystem {
  /** File names directly under the directory, without their path. */
  list(dir: string): Promise<string[]>;
  readText(absPath: string): Promise<string>;
}

const LINE_MAP_QUERY = `
  SELECT m.cobol_line_start AS cobolLineStart,
         m.cobol_line_end AS cobolLineEnd,
         m.gen_file AS genFile,
         m.gen_line_start AS genLineStart,
         m.gen_line_end AS genLineEnd
    FROM LINE_MAP m
    JOIN SOURCE s ON s.id = m.cobol_source_id
   WHERE s.path = $path
   ORDER BY m.gen_file, m.gen_line_start, m.cobol_line_start, m.id
`;

/**
 * Returns the line correspondence for one COBOL source (SOURCE.path form), ordered by generated file
 * and generated start line.
 */
export function readLineMap(db: QueryableDatabase, cobolRelPath: string): LineMapEntry[] {
  return mapRows(db.exec(LINE_MAP_QUERY, { $path: cobolRelPath }), (row) => ({
    cobolLineStart: row.int("cobolLineStart", 0),
    cobolLineEnd: row.int("cobolLineEnd", 0),
    genFile: row.text("genFile", ""),
    genLineStart: row.int("genLineStart", 0),
    genLineEnd: row.int("genLineEnd", 0),
  }));
}

/** Derives the language from the generated file's name; anything else is not a translation. */
export function generatedLanguage(fileName: string): TranspileLanguage | null {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".py")) return "python";
  if (lower.endsWith(".java")) return "java";
  return null;
}

/**
 * Reads the generated files the line map references, sorted by name for a deterministic order.
 * References the output directory no longer holds (a line map left over from an earlier output
 * directory) are skipped, so callers must not assume `files` covers every gen_file in `lineMap`.
 */
export async function readGeneratedFiles(
  fs: GeneratedFileSystem,
  outDir: string,
  lineMap: readonly LineMapEntry[],
): Promise<TranspileGeneratedFile[]> {
  const referenced = new Set(lineMap.map((entry) => entry.genFile));
  if (referenced.size === 0) {
    return [];
  }
  const present = (await fs.list(outDir)).filter((name) => referenced.has(name));
  present.sort((left, right) => (left < right ? -1 : left > right ? 1 : 0));

  const files: TranspileGeneratedFile[] = [];
  for (const name of present) {
    const language = generatedLanguage(name);
    if (language === null) {
      continue;
    }
    files.push({ name, language, text: await fs.readText(join(outDir, name)) });
  }
  return files;
}
