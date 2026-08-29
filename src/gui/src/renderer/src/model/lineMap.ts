/**
 * The COBOL-to-generated line correspondence (pure, independent of React and of Monaco).
 *
 * `translate` records one LINE_MAP row per translated construct, each covering a range on both sides:
 * one COBOL statement can become several generated lines and several COBOL lines can collapse into
 * one. The two panes of the transpile view are lined up through these rows alone — nothing here
 * guesses at a correspondence the engine did not record.
 */

import type { LineMapEntry, TranspileGeneratedFile, TranspileLanguage } from "../../../shared/ipc";

/** The rows belonging to one generated file, which is the only unit the two panes can be lined up in. */
export function entriesOf(
  lineMap: readonly LineMapEntry[],
  genFile: string,
): readonly LineMapEntry[] {
  return lineMap.filter((entry) => entry.genFile === genFile);
}

/**
 * The generated line a COBOL line corresponds to, or null when the engine recorded none.
 *
 * The first row covering the line wins. Rows are ordered by generated line, so where a COBOL line was
 * translated more than once — a paragraph performed from two places, say — the earliest generated
 * form is the one shown.
 */
export function generatedLineFor(
  entries: readonly LineMapEntry[],
  cobolLine: number,
): number | null {
  const hit = entries.find(
    (entry) => cobolLine >= entry.cobolLineStart && cobolLine <= entry.cobolLineEnd,
  );
  return hit === undefined ? null : hit.genLineStart;
}

/** The COBOL line a generated line corresponds to, or null when the engine recorded none. */
export function cobolLineFor(entries: readonly LineMapEntry[], genLine: number): number | null {
  const hit = entries.find(
    (entry) => genLine >= entry.genLineStart && genLine <= entry.genLineEnd,
  );
  return hit === undefined ? null : hit.cobolLineStart;
}

/** The languages the run produced a file in, in the order the files were read. */
export function languagesOf(
  files: readonly TranspileGeneratedFile[],
): readonly TranspileLanguage[] {
  const seen: TranspileLanguage[] = [];
  for (const file of files) {
    if (!seen.includes(file.language)) {
      seen.push(file.language);
    }
  }
  return seen;
}

/** The file to show for a language: the first one of it, or null when the run produced none. */
export function fileOf(
  files: readonly TranspileGeneratedFile[],
  language: TranspileLanguage,
): TranspileGeneratedFile | null {
  return files.find((file) => file.language === language) ?? null;
}
