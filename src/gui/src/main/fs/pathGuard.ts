/**
 * The one gate every path argument that comes from the renderer passes through.
 *
 * The renderer may name any file the user picked on screen, so reading, decoding, stat-ing and
 * writing back all funnel into these two functions. The allowed base directory is the asset folder
 * or one of the copybook search paths.
 *
 * The boundary is checked twice: once on the literal resolved path, and once on the real paths with
 * symlinks and junctions resolved, so a symlink planted inside the base folder cannot lead out of it.
 */

import { isAbsolute, relative, resolve } from "node:path";

/** The minimum filesystem the boundary check needs. */
export interface RealPathResolver {
  /** The real absolute path with symlinks and junctions resolved. Throws when the path is absent. */
  realPath(absPath: string): Promise<string>;
}

/**
 * Resolves a target inside the base directory. Relative paths resolve against the base.
 *
 * Returns null for anything that escapes: a relative path climbing out, an absolute path in another
 * folder, or the base directory itself (which is not a file).
 */
export function resolveWithinBase(baseDir: string, path: string): string | null {
  const base = resolve(baseDir);
  const target = isAbsolute(path) ? resolve(path) : resolve(base, path);
  const rel = relative(base, target);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) {
    return null;
  }
  return target;
}

/**
 * Resolves the real path of a target inside the base directory, throwing when it lies outside.
 * Reading, decoding and writing back all go through this single gate.
 */
export async function resolveSourceFile(
  fs: RealPathResolver,
  baseDir: string,
  path: string,
): Promise<string> {
  const absPath = resolveWithinBase(baseDir, path);
  if (absPath === null) {
    throw new Error(`「${path}」は資産フォルダの外にあります。資産フォルダの中の資産を選んでください。`);
  }
  const [realBase, realTarget] = await Promise.all([
    fs.realPath(resolve(baseDir)),
    fs.realPath(absPath),
  ]);
  if (resolveWithinBase(realBase, realTarget) === null) {
    throw new Error(`「${path}」は資産フォルダの外にあります。資産フォルダの中の資産を選んでください。`);
  }
  return realTarget;
}
