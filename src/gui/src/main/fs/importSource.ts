/**
 * Writes text copied out of a terminal emulator into the asset folder as a source file.
 *
 * The destination is a relative path the user chooses inside the asset folder; the name rules are
 * those of shared/importPath.ts, and a link or a folder at the name is refused (the engine's scan
 * infers asset kind from the bytes, so there is no folder-name convention to honour). Column
 * extraction happens in the renderer, so this only writes lines.
 *
 * The encoding is UTF-8 without a BOM: the engine's detection reads it, and an imported file has no
 * original encoding to keep.
 */

import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { importRelPath } from "../../shared/importPath";
import type { ImportSourceRequest, ImportSourceResult } from "../../shared/ipc";
import { resolveWithinBase } from "./pathGuard";

/** The filesystem the import needs. */
export interface ImportFileSystem {
  /** Creates the directory including any missing parents. */
  makeDir(absPath: string): Promise<void>;
  exists(absPath: string): Promise<boolean>;
  /** What the path itself is, whatever a link there points at. Null when nothing is there. */
  entry(absPath: string): Promise<"file" | "folder" | "link" | null>;
  realPath(absPath: string): Promise<string>;
  /**
   * Gives the name to a new file holding the text, UTF-8 without a BOM. With `replace` the file
   * that had the name keeps its content under every other name it has, and a file link put at the
   * name is replaced unfollowed. Without it the write takes a free name only: false is the answer
   * where something has the name, and nothing is written.
   */
  writeText(absPath: string, text: string, replace: boolean): Promise<boolean>;
}

/**
 * CRLF line endings, so the file opens correctly in a Windows text editor. The engine reads either.
 */
const LINE_SEPARATOR = "\r\n";

/**
 * Whether the destination folder is the asset folder itself or below it. An import directly into the
 * asset folder makes the two equal, which resolveWithinBase (which excludes the base itself) rejects.
 */
function isInsideOrSame(base: string, target: string): boolean {
  const rel = relative(resolve(base), resolve(target));
  return rel === "" || (rel !== ".." && !rel.startsWith(`..${sep}`) && !isAbsolute(rel));
}

/**
 * Whether every member of the request as the channel delivers it has the type the request declares,
 * so main reads no member before it has seen its type, and whether the asset folder is an absolute
 * path, so no path is resolved against the working folder of the process.
 */
function wellFormed(request: unknown): request is ImportSourceRequest {
  const given = request as Partial<Record<keyof ImportSourceRequest, unknown>> | null;
  return (
    given !== null &&
    typeof given === "object" &&
    typeof given.inputDir === "string" &&
    isAbsolute(given.inputDir) &&
    typeof given.destDir === "string" &&
    typeof given.fileName === "string" &&
    Array.isArray(given.lines) &&
    // A line holding CR or LF would be written as two lines and counted as one.
    given.lines.every((line) => typeof line === "string" && !/[\r\n]/.test(line)) &&
    typeof given.overwrite === "boolean"
  );
}

/** What the dialog is told when the filesystem refused the write: the path, the cause, what to do next. */
function failure(relPath: string, error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  const failed = `「${relPath}」を保存できませんでした`;
  switch (code) {
    case "EPERM":
    case "EACCES":
    case "EBUSY":
      return `${failed}。ファイルが読み取り専用か、ほかのプログラムが開いています。読み取り専用を解除するか、開いているプログラムを閉じてから、もう一度保存してください。`;
    case "ENAMETOOLONG":
      return `${failed}。名前が長すぎます。保存先フォルダかファイルの名前を短くしてから、もう一度保存してください。`;
    case "ENOSPC":
      return `${failed}。保存先の空き容量が足りません。不要なファイルを削除して空き容量を作ってから、もう一度保存してください。`;
    case "ENOENT":
      return `${failed}。保存先のフォルダが見つからないか、作成できませんでした。保存先フォルダの名前を確かめてから、もう一度保存してください。`;
    case "EEXIST":
    case "ENOTDIR":
      return `${failed}。保存先フォルダの途中に同じ名前のファイルがあります。保存先フォルダの名前を変えてください。`;
    default:
      return `${failed}${typeof code === "string" ? `（${code}）` : ""}。保存先のフォルダに書き込めるかを確かめてから、もう一度保存してください。`;
  }
}

/**
 * Writes the text into the asset folder. When a file of that name exists and overwriting was not
 * allowed, nothing is written and "exists" is returned for the caller to confirm.
 */
export async function importSource(
  fs: ImportFileSystem,
  request: ImportSourceRequest,
): Promise<ImportSourceResult> {
  if (!wellFormed(request)) {
    throw new Error("貼り付けの内容を受け取れませんでした。もう一度貼り付けてください。");
  }
  if (request.lines.length === 0) {
    throw new Error("貼り付けた本文がありません。端末から複写した本文を貼り付けてください。");
  }
  const relPath = importRelPath(request.destDir, request.fileName);
  if (relPath === null) {
    if (request.fileName.trim() === "") {
      throw new Error("ファイル名がありません。ファイル名を入力してください。");
    }
    const typed = [request.destDir.trim(), request.fileName.trim()].filter((part) => part !== "").join("/");
    throw new Error(`保存先「${typed}」は使えません。保存先とファイル名を見直してください。`);
  }
  const absPath = resolveWithinBase(request.inputDir, relPath);
  if (absPath === null) {
    throw new Error(`「${relPath}」は資産フォルダの外です。資産フォルダの中の保存先を指定してください。`);
  }
  // Checked before any folder on the way is created, which would otherwise bring the asset folder back.
  if (!(await fs.exists(resolve(request.inputDir)))) {
    throw new Error(
      `「${relPath}」を保存できませんでした。資産フォルダが見つかりません。資産フォルダの場所を確かめて、開き直してください。`,
    );
  }
  let outcome: Outcome;
  try {
    outcome = await writeInside(fs, request, absPath);
  } catch (error) {
    // What the filesystem says is English and names the full path; the dialog gets the relative
    // path, the cause in its own words, and what to check.
    throw new Error(failure(relPath, error));
  }
  switch (outcome.kind) {
    case "link":
      throw new Error(`「${relPath}」はリンクです。リンクのないファイル名を指定してください。`);
    case "folder":
      throw new Error(`「${relPath}」はフォルダです。ファイル名を指定してください。`);
    case "outside":
      throw new Error(`「${relPath}」は資産フォルダの外です。資産フォルダの中の保存先を指定してください。`);
    case "exists":
      // The name as the folder holds it, so the confirmation names the file the tree shows.
      return { status: "exists", relPath: outcome.relPath ?? relPath, lineCount: 0 };
    case "written":
      // The name as the folder holds it: a file overwritten under a name typed in another case, or
      // under its short name, keeps the name it had, and the tree and the tabs know it by that one.
      return { status: "written", relPath: outcome.relPath, lineCount: request.lines.length };
  }
}

type Outcome =
  | { kind: "link" | "folder" | "outside" }
  | { kind: "exists"; relPath: string | null }
  | { kind: "written"; relPath: string };

/**
 * The checks and the write. What remains open is a folder on the way swapped for a link between the
 * last check and the write, by someone who can already write into the asset folder: the platform
 * gives no way to open a path relative to a folder already checked.
 */
async function writeInside(
  fs: ImportFileSystem,
  request: ImportSourceRequest,
  absPath: string,
): Promise<Outcome> {
  // A link at the file name leads wherever it points, which the folder check below never sees.
  const entry = await fs.entry(absPath);
  if (entry === "link" || entry === "folder") {
    return { kind: entry };
  }
  // A junction or symlink would pass the literal check, so the boundary is confirmed on the real
  // paths: first on the nearest folder that is already there, so that no folder is created through
  // a link that leads out, and then on the destination folder itself.
  const base = resolve(request.inputDir);
  let present = dirname(absPath);
  while (present !== base && dirname(present) !== present && !(await fs.exists(present))) {
    present = dirname(present);
  }
  if (present !== base) {
    const [realBase, realPresent] = await Promise.all([fs.realPath(base), fs.realPath(present)]);
    if (!isInsideOrSame(realBase, realPresent)) {
      return { kind: "outside" };
    }
  }
  if (entry === null) {
    await fs.makeDir(dirname(absPath));
  }
  const [realBase, realDir] = await Promise.all([
    fs.realPath(base),
    fs.realPath(dirname(absPath)),
  ]);
  if (!isInsideOrSame(realBase, realDir)) {
    return { kind: "outside" };
  }
  // The name the folder holds for a file that is there. A real path that lies in another folder
  // means the name became a link since the check, and the typed name is then the one written.
  const real = entry === null ? null : await fs.realPath(absPath);
  const name = real !== null && relative(realDir, dirname(real)) === "" ? basename(real) : basename(absPath);
  const target = join(realDir, name);
  const held = relative(realBase, target).split(sep).join("/");
  if (!request.overwrite && entry !== null) {
    return { kind: "exists", relPath: held };
  }
  const written = await fs.writeText(
    target,
    request.lines.join(LINE_SEPARATOR) + LINE_SEPARATOR,
    request.overwrite,
  );
  // A file that took the name since the check is left as it is, for the caller to confirm.
  return written ? { kind: "written", relPath: held } : { kind: "exists", relPath: held };
}
