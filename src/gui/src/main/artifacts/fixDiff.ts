import type { FixDiff } from "../../shared/ipc";

/**
 * Pairs the original and fixed text of one file for the diff view. The IPC handler reads the
 * original (from the asset folder) and the fixed copy (from the apply output directory) and hands
 * both to this function.
 */
export function buildFixDiff(relPath: string, originalText: string, fixedText: string): FixDiff {
  return { relPath, originalText, fixedText };
}
