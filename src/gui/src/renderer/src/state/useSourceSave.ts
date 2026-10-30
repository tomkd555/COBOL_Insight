/**
 * Writing edited source back over the original.
 *
 * Node cannot encode Shift_JIS or EBCDIC, so the engine's `save` does the write; the renderer hands
 * over the full edited text and the engine rewrites only the lines that differ, carrying the
 * untouched ones across as their original bytes.
 *
 * Before a checked write the file's stamp is compared with the one taken when it was decoded or last
 * written: once here, and once more in main when the save's turn comes, since a save can wait behind
 * a scan. A file that changed underneath is kept as it is, and the caller is asked what to do with
 * the draft; an overwrite writes without the check.
 *
 * The saves of one tab run one after another, and each reads the tab's draft and stamp when its turn
 * comes, so a second save never measures the file against the stamp the first one is replacing.
 */

import { useCallback, useMemo, useRef, useState } from "react";
import type { DecodeResult, SaveResult, SourceStamp } from "../../../shared/ipc";
import { api, errorMessage } from "../api";
import { text } from "../i18n/text";
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
import { existingModel, resetModel } from "../vendor/monacoModels";

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
  /** Whether a save of every tab is under way, so a scan can wait for the last of its writes. */
  readonly savingAll: boolean;
}

/**
 * A tab's draft, or null where there is nothing to write: no draft, or one equal to what the tab
 * last read or wrote (a save that has just written it, before the store has caught up).
 */
function unsavedText(workbench: WorkbenchState, tabId: string): string | null {
  const draft = draftOf(workbench, tabId);
  return draft === null || draft === openDocument(tabId)?.text ? null : draft;
}

/** Whether a source tab is still open and still holds something to write. */
function stillUnsaved(workbench: WorkbenchState, tabId: string): boolean {
  return workbench.tabs.some((tab) => tab.id === tabId) && unsavedText(workbench, tabId) !== null;
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
  const [savingAll, setSavingAll] = useState(false);
  const savingAllRef = useRef(false);
  /** The drafts as they stand now: a save reads them at its turn, after the awaits before it. */
  const workbenchRef = useRef(workbench);
  workbenchRef.current = workbench;
  /** The last save asked for on each tab, settled or not; the next one on that tab waits for it. */
  const tabTurns = useRef(new Map<string, Promise<void>>());

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

  /**
   * Runs a task once the saves asked for on the same tab before it have settled. `waited` tells the
   * task whether there was one to wait for.
   */
  const inTabTurn = useCallback(
    <T>(tabId: string, task: (waited: boolean) => Promise<T>): Promise<T> => {
      const before = tabTurns.current.get(tabId);
      const run = (before ?? Promise.resolve()).then(() => task(before !== undefined));
      const after = run.then(
        () => undefined,
        () => undefined,
      );
      tabTurns.current.set(tabId, after);
      void after.then(() => {
        if (tabTurns.current.get(tabId) === after) {
          tabTurns.current.delete(tabId);
        }
      });
      return run;
    },
    [],
  );

  /**
   * Writes one tab's draft as it stands now. With `checked`, main compares the tab's stamp with the
   * original once the save's turn comes, and a change found there raises the conflict; without it,
   * the write goes over whatever is on disk. True when the original was rewritten.
   */
  const write = useCallback(
    async (tabId: string, checked: boolean): Promise<boolean> => {
      const document = openDocument(tabId);
      const draft = unsavedText(workbenchRef.current, tabId);
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
          expectedStamp: checked ? (document.stamp ?? undefined) : undefined,
        });
      } catch (error: unknown) {
        notify(text.save.failed(document.path, errorMessage(error)), true);
        return false;
      }
      if (result.stale === true) {
        setConflict({ tabId, path: document.path, draft, diskText: null });
        return false;
      }
      const assetType = inventory.find((item) => item.path === document.path)?.type ?? "";
      const outcome = saveOutcomeOf(document.path, assetType, result);
      if (outcome.kind === "failed") {
        // The engine refused the write, so the original is untouched and the draft stays.
        notify(outcome.message, true);
        return false;
      }
      // The disk holds the draft by now, written or found there. A stamp that cannot be read leaves
      // the tab's stamp unknown: the next save then skips the staleness check once and takes a fresh
      // stamp after its write. The rest of what a save sets off still follows.
      const stamp = await api()
        .stat({ baseDir: inputDir, path: document.path })
        .catch(() => null);
      // A tab closed during the save holds nothing more: its decode and its draft are gone, and
      // recording either again would bring the old text back when the file is reopened.
      if (workbenchRef.current.tabs.some((tab) => tab.id === tabId)) {
        rememberStamp(tabId, stamp, draft);
        // What the editor holds now is a draft wherever it differs from what was written: text typed
        // during the write, an undo back to the text held before it included.
        const held =
          existingModel(tabId)?.getValue() ?? draftOf(workbenchRef.current, tabId) ?? document.text;
        workbenchDispatch({ type: "SET_DRAFT", id: tabId, draft: held === draft ? null : held });
      }
      if (outcome.kind === "unchanged") {
        return true;
      }
      projectDispatch({
        type: "SET_SAVE_FINDINGS",
        path: document.path,
        findings: outcome.diagnostics,
      });
      projectDispatch({ type: "SOURCE_SAVED", path: document.path });
      if (outcome.message !== null) {
        notify(outcome.message);
      }
      return true;
    },
    [
      inputDir,
      codepageFor,
      settings.copybookPaths,
      inventory,
      workbenchDispatch,
      projectDispatch,
      notify,
    ],
  );

  const save = useCallback(
    (tabId: string): Promise<boolean> =>
      inTabTurn(tabId, async (waited) => {
        const document = openDocument(tabId);
        const draft = unsavedText(workbenchRef.current, tabId);
        if (inputDir === null || document === null) {
          return false;
        }
        if (draft === null) {
          // A save that waited for one before it finds that one wrote everything there was.
          if (waited) {
            return true;
          }
          notify(text.save.nothingToSave);
          return false;
        }
        let current: SourceStamp | null;
        try {
          current = await api().stat({ baseDir: inputDir, path: document.path });
        } catch (error: unknown) {
          notify(text.save.failed(document.path, errorMessage(error)), true);
          return false;
        }
        if (isStale(document.stamp, current)) {
          setConflict({ tabId, path: document.path, draft, diskText: null });
          return false;
        }
        return write(tabId, true);
      }),
    [inputDir, inTabTurn, write, notify],
  );

  /**
   * Saves every dirty tab one after another. They are not run together: the engine serialises saves
   * anyway, and a failure part-way through should leave the remaining drafts untouched.
   *
   * The first tab left unsaved stops the run. A conflict is answered in a dialog holding one tab's
   * draft, so carrying on would replace the question with the next tab's and leave the first
   * unanswered and unreported. Each tab is looked at when its turn comes and again after its save:
   * one closed or saved in the meantime is passed over, and the count of what remains holds only the
   * tabs still open and unsaved.
   */
  const saveAll = useCallback(async (): Promise<void> => {
    // One run at a time: a second one would write the same drafts and end the first one's mark.
    if (savingAllRef.current) {
      return;
    }
    const pending = dirtySourceTabs(workbenchRef.current);
    savingAllRef.current = true;
    setSavingAll(true);
    try {
      for (const [index, tabId] of pending.entries()) {
        if (!stillUnsaved(workbenchRef.current, tabId)) {
          continue;
        }
        if (!(await save(tabId)) && stillUnsaved(workbenchRef.current, tabId)) {
          // The run stops here, so say how much of the work is still unwritten.
          const remain = pending
            .slice(index)
            .filter((each) => stillUnsaved(workbenchRef.current, each)).length;
          notify(text.save.unsavedRemain(remain), true);
          return;
        }
      }
    } finally {
      savingAllRef.current = false;
      setSavingAll(false);
    }
  }, [save, notify]);

  const reload = useCallback(
    async (tabId: string): Promise<void> => {
      const document = openDocument(tabId);
      if (inputDir === null || document === null) {
        return;
      }
      let result: DecodeResult;
      try {
        result = await api().decode({
          baseDir: inputDir,
          path: document.path,
          codepage: codepageFor(document.path),
          db: dbPath ?? undefined,
        });
      } catch (error: unknown) {
        // The tab keeps its text and its draft; nothing was read to replace them with.
        notify(errorMessage(error), true);
        return;
      }
      if (result.error !== "") {
        notify(result.error, true);
        return;
      }
      rememberDocument(tabId, document.path, result);
      resetModel(tabId, result.text);
      // The editor visibly takes the file as it now stands; a notification would only repeat it.
      workbenchDispatch({ type: "SET_DRAFT", id: tabId, draft: null });
    },
    [inputDir, dbPath, codepageFor, workbenchDispatch, notify],
  );

  const overwrite = useCallback((): void => {
    if (conflict === null) {
      return;
    }
    const tabId = conflict.tabId;
    setConflict(null);
    void inTabTurn(tabId, async () => {
      // The draft can have been saved or thrown away while the dialog was open.
      if (unsavedText(workbenchRef.current, tabId) === null) {
        notify(text.save.nothingToSave);
        return false;
      }
      return write(tabId, false);
    });
  }, [conflict, inTabTurn, write, notify]);

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

  const hasDirty = useMemo(() => dirtySourceTabs(workbench).length > 0, [workbench]);

  return {
    save,
    saveAll,
    reload,
    conflict,
    overwrite,
    showConflictDiff,
    dismissConflict,
    hasDirty,
    savingAll,
  };
}
