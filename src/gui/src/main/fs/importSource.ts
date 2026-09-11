/**
 * Writes text copied out of a terminal emulator into the asset folder as a source file.
 *
 * The destination is a relative path the user chooses; the only condition is that it stays inside
 * the asset folder (the engine's scan infers asset kind from the bytes, so there is no folder-name
 * convention to honour). Column extraction happens in the renderer, so this only writes lines.
 *
 * The encoding is UTF-8 without a BOM: the engine's detection reads it, and this is the GUI's only
 * write of new content, so there is nothing to be gained by guessing an original encoding.
 */

import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";
import type { ImportSourceRequest, ImportSourceResult } from "../../shared/ipc";
import { resolveWithinBase } from "./pathGuard";

/** The filesystem the import needs. */
export interface ImportFileSystem {
  /** Creates the directory including any missing parents. */
  makeDir(absPath: string): Promise<void>;
  exists(absPath: string): Promise<boolean>;
  realPath(absPath: string): Promise<string>;
  /** Writes UTF-8 without a BOM, replacing any existing file. */
  writeText(absPath: string, text: string): Promise<void>;
}

/**
 * CRLF line endings, so the file opens correctly in a Windows text editor. The engine reads either.
 */
const LINE_SEPARATOR = "\r\n";

/** Path segments that cannot be part of a destination inside the asset folder. */
const REJECTED_SEGMENTS = new Set(["", ".", ".."]);

/**
 * Builds the relative destination path. Returns null when the destination folder or file name is
 * unusable (empty, a path segment that climbs out, or an absolute path).
 */
export function importRelPath(destDir: string, fileName: string): string | null {
  if (fileName.trim() === "" || isAbsolute(fileName) || /[\\/]/.test(fileName)) {
    return null;
  }
  const segments = destDir.split(/[\\/]/).filter((segment) => segment !== "");
  if (segments.some((segment) => REJECTED_SEGMENTS.has(segment))) {
    return null;
  }
  return [...segments, fileName].join("/");
}

/**
 * Whether the destination folder is the asset folder itself or below it. An import directly into the
 * asset folder makes the two equal, which resolveWithinBase (which excludes the base itself) rejects.
 */
function isInsideOrSame(base: string, target: string): boolean {
  const rel = relative(resolve(base), resolve(target));
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

/**
 * Writes the text into the asset folder. When a file of that name exists and overwriting was not
 * allowed, nothing is written and "exists" is returned for the caller to confirm.
 */
export async function importSource(
  fs: ImportFileSystem,
  request: ImportSourceRequest,
): Promise<ImportSourceResult> {
  if (request.lines.length === 0) {
    throw new Error("貼り付けた本文がありません。端末から複写した本文を貼り付けてください。");
  }
  const relPath = importRelPath(request.destDir, request.fileName);
  if (relPath === null) {
    throw new Error(
      `保存先「${request.destDir}/${request.fileName}」は使えません。保存先とファイル名を見直してください。`,
    );
  }
  const absPath = resolveWithinBase(request.inputDir, relPath);
  if (absPath === null) {
    throw new Error(`「${relPath}」は資産フォルダの外です。資産フォルダの中の保存先を指定してください。`);
  }
  if (!request.overwrite && (await fs.exists(absPath))) {
    return { status: "exists", relPath, lineCount: 0 };
  }
  await fs.makeDir(dirname(absPath));
  // A junction or symlink would pass the literal check, so confirm the boundary on the real paths.
  const [realBase, realDir] = await Promise.all([
    fs.realPath(resolve(request.inputDir)),
    fs.realPath(dirname(absPath)),
  ]);
  if (!isInsideOrSame(realBase, realDir)) {
    throw new Error(`「${relPath}」は資産フォルダの外です。資産フォルダの中の保存先を指定してください。`);
  }
  await fs.writeText(
    join(realDir, basename(absPath)),
    request.lines.join(LINE_SEPARATOR) + LINE_SEPARATOR,
  );
  return { status: "written", relPath, lineCount: request.lines.length };
}
