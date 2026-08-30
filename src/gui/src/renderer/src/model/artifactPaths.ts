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
