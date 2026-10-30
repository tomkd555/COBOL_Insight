/**
 * What is known about each open source tab beyond its text: the codepage the engine actually used,
 * the file's stamp at the moment it was decoded, and the decoded text itself.
 *
 * It is held outside React state. Only the save path reads it, and it must be able to read every open tab
 * at once (save-all), selected or not; holding it in a store would mean re-rendering
 * the shell every time a decode finished, for a value nothing on screen shows.
 *
 * The decoded text is kept so that "discard" has something to restore, and the stamp so that a save
 * can tell whether the original changed under the editor.
 */

import type { DecodeResult, SourceStamp } from "../../../shared/ipc";

export interface OpenDocument {
  readonly tabId: string;
  /** The asset's path relative to the asset folder. */
  readonly path: string;
  /** The codepage the engine used, as it reported it. */
  readonly codepage: string;
  /**
   * The file as it stood when it was decoded or last written. Null when the stat after a write
   * failed: the next save then writes without the staleness check and takes a fresh stamp.
   */
  readonly stamp: SourceStamp | null;
  /** The decoded text, which is what "discard" restores. */
  readonly text: string;
}

const documents = new Map<string, OpenDocument>();

/**
 * Records the result of a decode against its tab, replacing anything the tab held before.
 *
 * With `holdsDraft` the tab keeps the stamp it already had: its unsaved edit was made against the file
 * as read then, so a change on disk since that read must still raise the conflict at save.
 */
export function rememberDocument(
  tabId: string,
  path: string,
  result: DecodeResult,
  holdsDraft = false,
): OpenDocument {
  const earlier = documents.get(tabId);
  const document: OpenDocument = {
    tabId,
    path,
    codepage: result.codepage,
    stamp: holdsDraft && earlier !== undefined ? earlier.stamp : result.stamp,
    text: result.text,
  };
  documents.set(tabId, document);
  return document;
}

/**
 * Updates the stamp after a successful save, so the next save does not see its own write as stale.
 * Null records that the stamp after the write could not be read.
 */
export function rememberStamp(tabId: string, stamp: SourceStamp | null, text: string): void {
  const document = documents.get(tabId);
  if (document !== undefined) {
    documents.set(tabId, { ...document, stamp, text });
  }
}

/** What is known about a tab, or null when it has not been decoded. */
export function openDocument(tabId: string): OpenDocument | null {
  return documents.get(tabId) ?? null;
}

/** Forgets a closed tab. An unknown id does nothing. */
export function forgetDocument(tabId: string): void {
  documents.delete(tabId);
}
