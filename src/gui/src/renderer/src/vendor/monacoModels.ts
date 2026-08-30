/**
 * The text models of the open tabs.
 *
 * Monaco keeps a document's undo stack on its model, not on the editor. The editor area renders only
 * the selected tab, so an editor bound straight to the tab would lose the undo stack — and, while an
 * edit is unsaved, the edit itself — on every switch. The models therefore outlive the editor: they
 * live here, keyed by tab id, and switching a tab is `editor.setModel`.
 *
 * There is one editor instance per editor group. That falls out of the rendering: the editor group
 * renders the same source-editor element for every source tab, so React keeps the component (and the
 * editor inside it) mounted across a switch and only the model changes.
 */

import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "./monacoEditor";

const models = new Map<string, monacoApi.editor.ITextModel>();

/** The model of an open tab, or null when the tab has never been opened. */
export function existingModel(tabId: string): monacoApi.editor.ITextModel | null {
  const model = models.get(tabId);
  return model === undefined || model.isDisposed() ? null : model;
}

/**
 * The model for a tab, created from `text` the first time the tab is opened.
 *
 * A model that is already there is returned as it stands, edits and undo stack included. Replacing
 * its text on every open is exactly what would throw the unsaved edit away.
 */
export function modelFor(
  tabId: string,
  text: string,
  languageId: string,
): monacoApi.editor.ITextModel {
  const existing = existingModel(tabId);
  if (existing !== null) {
    return existing;
  }
  const model = monacoEditor().editor.createModel(text, languageId);
  models.set(tabId, model);
  return model;
}

/**
 * Replaces a model's text, keeping the model itself.
 *
 * This is the one path that discards an edit: re-decoding under a different codepage, and the
 * "discard" action. `setValue` is deliberate here — it clears the undo stack, which is what
 * "restore the file as the engine decoded it" means.
 */
export function resetModel(tabId: string, text: string): void {
  existingModel(tabId)?.setValue(text);
}

/** Disposes a closed tab's model. An unknown tab id is not an error. */
export function disposeModel(tabId: string): void {
  models.get(tabId)?.dispose();
  models.delete(tabId);
}
