/**
 * 端末エミュレータの画面から複写した本文を、資産フォルダ配下のソースファイルとして書き出す。
 * 書き出し先は engine の走査規約に従い、資産フォルダ直下の種別フォルダへ限る。桁の切り出しは
 * renderer で済ませてあり、ここは行の配列をそのまま1つのファイルへ落とす。
 *
 * 文字コードは UTF-8(BOM 無し)とする。engine の自動判別が読める形であり、GUI が扱う唯一の
 * 書込であるため、資産の元の文字コードを推測して書き分けることはしない。
 */

import { basename, dirname, join, resolve } from "node:path";
import { importRelPath } from "../../shared/assetImport";
import type { ImportSourceRequest, ImportSourceResult } from "../../shared/engine-api";
import { resolveWithinInputDir } from "./sourceText";

/** 取込の書出に要する最小のファイルシステム。main が fs/promises を束ねて渡す。 */
export interface ImportFileSystem {
  /** 途中のフォルダを含めて作る。既にあれば何もしない。 */
  makeDir(absPath: string): Promise<void>;
  /** そのパスにファイルがあるか。 */
  exists(absPath: string): Promise<boolean>;
  /** 接合・symlink を解いた実体パス。 */
  realPath(absPath: string): Promise<string>;
  /** UTF-8(BOM 無し)で書く。既存ファイルは置き換える。 */
  writeText(absPath: string, text: string): Promise<void>;
}

/**
 * 行末は CRLF とする。Windows のテキストエディタで開いても行が崩れないためであり、
 * engine は CRLF・LF のいずれも読む。
 */
const LINE_SEPARATOR = "\r\n";

/**
 * 本文を資産フォルダ配下へ書き出す。同名のファイルがあり上書きの許可が無いときは、書かずに
 * exists を返して呼び手の確認に委ねる。
 */
export async function importSource(
  fs: ImportFileSystem,
  request: ImportSourceRequest,
): Promise<ImportSourceResult> {
  if (request.lines.length === 0) {
    throw new Error("取り込む本文がありません。");
  }
  const relPath = importRelPath(request.kind, request.fileName);
  if (relPath === null) {
    throw new Error(`ファイル名として使えません: ${request.fileName}`);
  }
  const absPath = resolveWithinInputDir(request.inputDir, relPath);
  if (absPath === null) {
    throw new Error(`資産フォルダの外へは書き出せません: ${relPath}`);
  }
  if (!request.overwrite && (await fs.exists(absPath))) {
    return { status: "exists", relPath, lineCount: 0 };
  }
  await fs.makeDir(dirname(absPath));
  // 保存先フォルダが接合・symlink であれば字面の判定は素通りするため、実体パスでも配下を確かめる。
  const [realBase, realDir] = await Promise.all([
    fs.realPath(resolve(request.inputDir)),
    fs.realPath(dirname(absPath)),
  ]);
  if (resolveWithinInputDir(realBase, realDir) === null) {
    throw new Error(`資産フォルダの外へは書き出せません: ${relPath}`);
  }
  await fs.writeText(
    join(realDir, basename(absPath)),
    request.lines.join(LINE_SEPARATOR) + LINE_SEPARATOR,
  );
  return { status: "written", relPath, lineCount: request.lines.length };
}
