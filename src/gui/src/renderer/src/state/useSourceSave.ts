/**
 * Writing edited source back over the original.
 *
 * Node cannot encode Shift_JIS or EBCDIC, so the engine's `save` does the write; the renderer hands
 * over the full edited text and the engine rewrites only the lines that differ, carrying the
 * untouched ones across as their original bytes.
 *
 * Before every write the file's stamp is compared with the one taken when it was decoded. A file
 * that changed underneath is not overwritten silently: the caller is asked what to do with it.
 */

import { useCallback, useState } from "react";
import type { SaveResult } from "../../../shared/ipc";
import { api, errorMessage } from "../api";
import { text } from "../text";
import { artifactItems, useProject, useProjectDispatch } from "./projectStore";
import { useSettings } from "./settingsStore";
import {
  draftOf,
  useWorkbench,
  useWorkbenchDispatch,
  type WorkbenchState,
} from "./workbenchStore";
import { openDocument, rememberDocument, rememberStamp } from "../model/openDocuments";
import { isStale, saveOutcomeOf } from "../model/save";
import { resetModel } from "../vendor/monacoModels";

/** A save that stopped because the original had changed since it was read. */
export interface SaveConflict {
  readonly tabId: string;
  readonly path: string;
  /** The unsaved text. */
  readonly draft: string;
  /** The file as it now stands on disk, once "show the difference" has fetched it. */
  readonly diskText: string | null;
}

export interface SourceSave {
  /**
   * Saves one tab, asking first when the original has changed underneath. Resolves true only when
   * the file was written: a conflict and a failed write both leave the draft where it was, and a
   * caller that closes the tab afterwards would otherwise throw the edit away.
   */
  save(tabId: string): Promise<boolean>;
  /** Saves every tab that holds unsaved edits, one after another. */
  saveAll(): Promise<void>;
  /** Throws the tab's edits away and takes the file as it stands on disk. */
  reload(tabId: string): Promise<void>;
  readonly conflict: SaveConflict | null;
  /** Writes the draft over the changed original anyway. */
  overwrite(): void;
  /** Fetches the current file so the two can be compared. */
  showConflictDiff(): void;
  dismissConflict(): void;
  /** Whether anything is unsaved, so the commands can say when they apply. */
  readonly hasDirty: boolean;
}

/** The source tabs that hold unsaved edits, in tab order. */
function dirtySourceTabs(workbench: WorkbenchState): string[] {
  return workbench.tabs
    .filter((tab) => tab.kind === "source" && draftOf(workbench, tab.id) !== null)
    .map((tab) => tab.id);
}

export function useSourceSave(notify: (message: string, failed?: boolean) => void): SourceSave {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const settings = useSettings();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();
  const [conflict, setConflict] = useState<SaveConflict | null>(null);

  const inputDir = project.inputDir;
  const dbPath = project.dbPath;
  const inventory = artifactItems(project.inventory);

  /** The codepage to pin for one asset: the manual override, else the shell's default, else none. */
  const codepageFor = useCallback(
    (path: string): string | undefined => {
      const override = project.codepageOverrides[path] ?? "";
      const charset = override === "" ? settings.defaultEncoding : override;
      return charset === "" ? undefined : charset;
    },
    [project.codepageOverrides, settings.defaultEncoding],
  );

  /** Writes one tab's draft, with no staleness check. True when the original was rewritten. */
  const write = useCallback(
    async (tabId: string): Promise<boolean> => {
      const document = openDocument(tabId);
      const draft = draftOf(workbench, tabId);
      if (inputDir === null || document === null || draft === null) {
        return false;
      }
      let result: SaveResult;
      try {
        result = await api().save({
          baseDir: inputDir,
          path: document.path,
          editedText: draft,
          codepage: codepageFor(document.path),
          copybookPaths: [...settings.copybookPaths],
        });
      } catch (error: unknown) {
        notify(text.save.failed(document.path, errorMessage(error)), true);
        return false;
      }
      const assetType = inventory.find((item) => item.path === document.path)?.type ?? "";
      const outcome = saveOutcomeOf(document.path, assetType, result);
      if (outcome.kind === "failed") {
        // Exit code 2 means the original is untouched, so the draft stays exactly where it was.
        notify(outcome.message, true);
        return false;
      }
      const stamp = await api().stat({ baseDir: inputDir, path: document.path });
      rememberStamp(tabId, stamp ?? document.stamp, draft);
      workbenchDispatch({ type: "SET_DRAFT", id: tabId, draft: null });
      projectDispatch({
        type: "SET_SAVE_FINDINGS",
        path: document.path,
        findings: outcome.diagnostics,
      });
      notify(outcome.message);
      return true;
    },
    [
      inputDir,
      workbench,
      codepageFor,
      settings.copybookPaths,
      inventory,
      workbenchDispatch,
      projectDispatch,
      notify,
    ],
  );

  const save = useCallback(
    async (tabId: string): Promise<boolean> => {
      const document = openDocument(tabId);
      const draft = draftOf(workbench, tabId);
      if (inputDir === null || document === null) {
        return false;
      }
      if (draft === null) {
        notify(text.save.nothingToSave);
        return false;
      }
      const current = await api().stat({ baseDir: inputDir, path: document.path });
      if (isStale(document.stamp, current)) {
        setConflict({ tabId, path: document.path, draft, diskText: null });
        return false;
      }
      return write(tabId);
    },
    [inputDir, workbench, write, notify],
  );

  /**
   * Saves every dirty tab one after another. They are not run together: the engine serialises saves
   * anyway, and a failure part-way through should leave the remaining drafts untouched rather than
   * half-written.
   *
   * The first tab that is not written stops the run. A conflict is answered in a dialog holding one
   * tab's draft, so carrying on would replace the question with the next tab's and leave the first
   * unanswered and unreported.
   */
  const saveAll = useCallback(async (): Promise<void> => {
    for (const tabId of dirtySourceTabs(workbench)) {
      if (!(await save(tabId))) {
        return;
      }
    }
  }, [workbench, save]);

  const reload = useCallback(
    async (tabId: string): Promise<void> => {
      const document = openDocument(tabId);
      if (inputDir === null || document === null) {
        return;
      }
      const result = await api().decode({
        baseDir: inputDir,
        path: document.path,
        codepage: codepageFor(document.path),
        db: dbPath ?? undefined,
      });
      if (result.error !== "") {
        notify(result.error, true);
        return;
      }
      rememberDocument(tabId, document.path, result);
      resetModel(tabId, result.text);
      workbenchDispatch({ type: "SET_DRAFT", id: tabId, draft: null });
      notify(text.save.reloaded(document.path));
    },
    [inputDir, dbPath, codepageFor, workbenchDispatch, notify],
  );

  const overwrite = useCallback((): void => {
    if (conflict === null) {
      return;
    }
    const tabId = conflict.tabId;
    setConflict(null);
    void write(tabId);
  }, [conflict, write]);

  const showConflictDiff = useCallback((): void => {
    if (conflict === null || inputDir === null) {
      return;
    }
    // The stamp has changed, so the decode cache misses and this reads the file as it now stands.
    api()
      .decode({
        baseDir: inputDir,
        path: conflict.path,
        codepage: codepageFor(conflict.path),
        db: dbPath ?? undefined,
      })
      .then((result) => {
        // A decode that failed resolves with an empty text; showing it would present the file as
        // empty and invite an overwrite of something the user never saw.
        if (result.error !== "") {
          notify(result.error, true);
          return;
        }
        setConflict((current) => (current === null ? null : { ...current, diskText: result.text }));
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  }, [conflict, inputDir, dbPath, codepageFor, notify]);

  const dismissConflict = useCallback((): void => setConflict(null), []);

  return {
    save,
    saveAll,
    reload,
    conflict,
    overwrite,
    showConflictDiff,
    dismissConflict,
    hasDirty: dirtySourceTabs(workbench).length > 0,
  };
}
