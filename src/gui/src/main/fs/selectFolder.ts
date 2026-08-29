/**
 * The pure part of the asset-folder picker and the directory existence check. Electron's dialog and
 * fs.stat are injected, so both stay testable without a window.
 */

/** The part of dialog.showOpenDialog's result this uses. */
export interface OpenDialogOutcome {
  canceled: boolean;
  filePaths: string[];
}

/** A showOpenDialog call configured with `openDirectory`. */
export type OpenDirectoryDialog = () => Promise<OpenDialogOutcome>;

/** Opens the folder picker and returns the chosen folder, or null when cancelled. */
export async function selectFolder(dialog: OpenDirectoryDialog): Promise<string | null> {
  const outcome = await dialog();
  if (outcome.canceled) {
    return null;
  }
  return outcome.filePaths[0] ?? null;
}

/** The part of fs.stat this uses. */
export interface DirectoryStat {
  stat(path: string): Promise<{ isDirectory(): boolean }>;
}

/**
 * Whether the path exists as a directory. Absent, inaccessible and "is a file" are all false; the
 * caller needs no finer distinction.
 */
export async function dirExists(fs: DirectoryStat, path: string): Promise<boolean> {
  try {
    return (await fs.stat(path)).isDirectory();
  } catch {
    return false;
  }
}
