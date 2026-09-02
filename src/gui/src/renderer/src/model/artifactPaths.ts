/**
 * Where the screens put the directories the engine writes into (pure, independent of React).
 *
 * Main resolves the project file's location and the renderer derives the rest from it, so every
 * scratch directory sits beside the project file rather than in whatever the engine's working
 * directory happens to be. The one exception is the fix output directory, which the settings screen
 * lets a user point somewhere they collect results from.
 */

/** The folder a path sits in, trailing separator included. Empty when it names no folder. */
export function directoryOf(path: string): string {
  const separator = Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/"));
  return separator < 0 ? "" : path.slice(0, separator + 1);
}

/**
 * A directory beside the project file, named `name`. Empty when the project file's location is not
 * known yet, which is what callers check before running anything.
 */
export function artifactSubdir(dbPath: string | null | undefined, name: string): string {
  const parent = directoryOf(dbPath ?? "");
  return parent === "" ? "" : `${parent}${name}`;
}

/**
 * Where `fix apply` writes when the user asks for the proposal to be written out: the setting when
 * it names a directory, and the one beside the project file otherwise.
 */
export function fixOutDirOf(fixOutDir: string, dbPath: string | null | undefined): string {
  const chosen = fixOutDir.trim();
  return chosen === "" ? artifactSubdir(dbPath, "fix") : chosen;
}

/** A directory for comparison: one separator style, no trailing separator, case folded. */
function comparablePath(path: string): string {
  return path.trim().replace(/[\\/]+/g, "/").replace(/\/+$/, "").toLowerCase();
}

/**
 * Whether the chosen directory is the asset folder or sits inside it. Writing the proposals there
 * would leave them among the originals they were derived from, so the settings screen refuses it.
 *
 * Windows paths are compared case-insensitively, and both separators and a trailing one are ignored.
 * An empty asset folder (none chosen yet) is no boundary, so nothing is inside it.
 */
export function insideAssetFolder(fixOutDir: string, inputDir: string | null | undefined): boolean {
  const base = comparablePath(inputDir ?? "");
  const chosen = comparablePath(fixOutDir);
  if (base === "" || chosen === "") {
    return false;
  }
  return chosen === base || chosen.startsWith(`${base}/`);
}
