/**
 * transpile 成果物の読取。生成物は TranspileRunner が出力先直下へ平坦に書き(TranspileRunner.java:233)、
 * COBOL 行と生成行の対応は SQLite の LINE_MAP 表が持つ(Schema.java:120-133)。
 * 生成ファイル名は PROGRAM-ID とレコード名から決まり COBOL のファイル名とは一致しないため、
 * ある COBOL ソースの対訳がどのファイルかは LINE_MAP の gen_file だけが示す。
 */

import { join } from "node:path";
import type {
  LineMapEntry,
  TranspileGeneratedFile,
  TranspileLanguage,
} from "../../shared/engine-api";
import { mapRows, type QueryableDatabase } from "./sqlRows";

/** 生成物の一覧取得と本文読取に要する最小のファイルシステム。main が fs/promises を束ねて渡す。 */
export interface GeneratedFileSystem {
  /** ディレクトリ直下のファイル名(パスを含まない)を返す。 */
  list(dir: string): Promise<string[]>;
  /** UTF-8 のテキストとして読む。 */
  readText(absPath: string): Promise<string>;
}

const LINE_MAP_QUERY = `
  SELECT m.id AS id,
         m.cobol_line_start AS cobolLineStart,
         m.cobol_line_end AS cobolLineEnd,
         m.gen_file AS genFile,
         m.gen_line_start AS genLineStart,
         m.gen_line_end AS genLineEnd,
         m.kind AS kind,
         m.note AS note,
         m.anchor_id AS anchorId
    FROM LINE_MAP m
    JOIN SOURCE s ON s.id = m.cobol_source_id
   WHERE s.path = $path
   ORDER BY m.gen_file, m.gen_line_start, m.cobol_line_start, m.id
`;

/**
 * 指定した COBOL ソース(SOURCE.path と同形の相対パス)の行対応を、生成ファイル・生成開始行の順で返す。
 * 空の note は注記なし、非空は直訳できなかった箇所の注記である。
 */
export function readLineMap(db: QueryableDatabase, cobolRelPath: string): LineMapEntry[] {
  return mapRows(db.exec(LINE_MAP_QUERY, { $path: cobolRelPath }), (row) => ({
    id: row.int("id", 0),
    cobolLineStart: row.int("cobolLineStart", 0),
    cobolLineEnd: row.int("cobolLineEnd", 0),
    genFile: row.text("genFile", ""),
    genLineStart: row.int("genLineStart", 0),
    genLineEnd: row.int("genLineEnd", 0),
    kind: row.text("kind", ""),
    note: row.text("note", ""),
    anchorId: row.text("anchorId", ""),
  }));
}

/** 生成物のファイル名から対象言語を決める。対訳の対象外(ランタイム以外の付随物)は null。 */
export function generatedLanguage(fileName: string): TranspileLanguage | null {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".py")) {
    return "python";
  }
  if (lower.endsWith(".java")) {
    return "java";
  }
  return null;
}

/**
 * 対応表が参照する生成物を出力先直下から読む。名前順で決定論的に並べる。
 * 出力先に存在しない参照(過去の出力先に対する対応表が DB に残っている場合)は飛ばすため、
 * 呼び出し側は files が lineMap の gen_file を網羅しないことを前提に描く。
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
