import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { api, errorMessage } from "./api";
import { text } from "./text";
import { ProjectProvider, useProject } from "./state/projectStore";
import {
  PANEL_LIMITS,
  SIDE_LIMITS,
  WorkbenchProvider,
  fixTab,
  isTabDirty,
  sourceTab,
  useWorkbench,
  useWorkbenchDispatch,
} from "./state/workbenchStore";
import { SettingsProvider, useSettingsDispatch } from "./state/settingsStore";
import { EditorStatusProvider } from "./state/editorStatusStore";
import { useShellStartup } from "./state/useShellStartup";
import { buildCommands, type Command } from "./state/commands";
import {
  commandAfterPrefix,
  commandForChord,
  isPaletteChord,
  isSequencePrefix,
} from "./state/keybindings";
import { useAnalysis } from "./state/useAnalysis";
import { useSourceSave } from "./state/useSourceSave";
import { closeDecision } from "./model/closeGuard";
import { forgetDocument } from "./model/openDocuments";
import { disposeModel } from "./vendor/monacoModels";
import { languageIdFor } from "./vendor/monarch";
import { ActivityBar } from "./shell/ActivityBar";
import { SideBar } from "./shell/SideBar";
import { EditorGroup } from "./shell/EditorGroup";
import { Panel } from "./shell/Panel";
import { StatusBar } from "./shell/StatusBar";
import { TitleBar } from "./shell/TitleBar";
import { CommandPalette } from "./shell/CommandPalette";
import { DiffView } from "./editors/diff/DiffView";
import { SplitHandle } from "./ui/SplitHandle";
import { Modal } from "./ui/Modal";
import { Toast, type ToastMessage } from "./ui/Toast";

/** The shell proper. It sits inside the providers so it can read and write every store. */
function Shell(): ReactElement {
  const project = useProject();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();
  const settingsDispatch = useSettingsDispatch();
  const analysis = useAnalysis();

  const [paletteOpen, setPaletteOpen] = useState(false);
  const [pendingClose, setPendingClose] = useState<string | null>(null);
  const [toasts, setToasts] = useState<readonly ToastMessage[]>([]);
  const toastId = useRef(0);

  const notify = useCallback((message: string, failed = false): void => {
    toastId.current += 1;
    const entry = { id: toastId.current, text: message, failed };
    setToasts((current) => [...current, entry]);
  }, []);

  const dismissToast = useCallback((id: number): void => {
    setToasts((current) => current.filter((message) => message.id !== id));
  }, []);

  useShellStartup(notify);
  const sourceSave = useSourceSave(notify);

  const openAsset = useCallback(
    (path: string, line: number | null): void => {
      workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(path, line) });
    },
    [workbenchDispatch],
  );

  const showFix = useCallback(
    (path: string): void => {
      workbenchDispatch({ type: "OPEN_TAB", tab: fixTab(path) });
    },
    [workbenchDispatch],
  );

  /** Closes a tab for good, releasing the text model and the decode it was holding. */
  const closeTab = useCallback(
    (id: string): void => {
      workbenchDispatch({ type: "CLOSE_TAB", id });
      disposeModel(id);
      forgetDocument(id);
    },
    [workbenchDispatch],
  );

  const runAnalysis = useCallback((): void => {
    if (project.inputDir === null) {
      notify(text.run.noFolder, true);
      return;
    }
    void analysis.run(project.inputDir);
  }, [analysis, project.inputDir, notify]);

  const selectFolder = useCallback((): void => {
    api()
      .selectFolder()
      .then((folder) => {
        if (folder === null) {
          return;
        }
        settingsDispatch({ type: "SET_LAST_INPUT_DIR", dir: folder });
        void analysis.run(folder);
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  }, [analysis, settingsDispatch, notify]);

  const cancelAnalysis = useCallback((): void => {
    analysis.cancel().catch((error: unknown) => notify(errorMessage(error), true));
  }, [analysis, notify]);

  /** Closes a tab, asking first when it holds unsaved edits. */
  const requestCloseTab = useCallback(
    (id: string): void => {
      if (closeDecision(isTabDirty(workbench, id)) === "close") {
        closeTab(id);
      } else {
        setPendingClose(id);
      }
    },
    [workbench, closeTab],
  );

  const saveActiveTab = useCallback((): void => {
    if (workbench.activeTabId !== null) {
      void sourceSave.save(workbench.activeTabId);
    }
  }, [workbench.activeTabId, sourceSave]);

  const saveAllTabs = useCallback((): void => {
    void sourceSave.saveAll();
  }, [sourceSave]);

  const commands: Command[] = useMemo(
    () =>
      buildCommands({
        project,
        workbench,
        workbenchDispatch,
        selectFolder,
        runAnalysis,
        cancelAnalysis,
        requestCloseTab,
        saveActiveTab,
        saveAllTabs,
        hasDirty: sourceSave.hasDirty,
      }),
    [
      project,
      workbench,
      workbenchDispatch,
      selectFolder,
      runAnalysis,
      cancelAnalysis,
      requestCloseTab,
      saveActiveTab,
      saveAllTabs,
      sourceSave.hasDirty,
    ],
  );

  // The keyboard chords. They are ignored while typing into a field, except inside the code editor.
  // Ctrl+K arms a two-key sequence; the next key completes it or cancels it.
  const armed = useRef(false);
  useEffect(() => {
    const runCommand = (id: string): boolean => {
      const command = commands.find((candidate) => candidate.id === id);
      if (command === undefined || !command.when()) {
        return false;
      }
      command.run();
      return true;
    };
    const onKeyDown = (event: globalThis.KeyboardEvent): void => {
      if (armed.current) {
        armed.current = false;
        const sequenced = commandAfterPrefix(event);
        if (sequenced !== null) {
          event.preventDefault();
          runCommand(sequenced);
          return;
        }
      }
      if (isSequencePrefix(event)) {
        event.preventDefault();
        armed.current = true;
        return;
      }
      if (isPaletteChord(event)) {
        event.preventDefault();
        setPaletteOpen(true);
        return;
      }
      const id = commandForChord(event);
      if (id === null) {
        return;
      }
      // A bound chord is swallowed even when the command does not currently apply, so that the
      // browser's own binding (Ctrl+S, say) never fires behind it.
      event.preventDefault();
      runCommand(id);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [commands]);

  const commitSize = useCallback((): void => {
    workbenchDispatch({ type: "COMMIT_SIZE" });
  }, [workbenchDispatch]);

  const conflict = sourceSave.conflict;

  return (
    <div className="ci-shell">
      <TitleBar onRun={runAnalysis} onCancel={cancelAnalysis} onSelectFolder={selectFolder} />
      <div className="ci-shell__body">
        <ActivityBar />
        {workbench.sideVisible ? (
          <>
            <div className="ci-shell__side" style={{ width: `${workbench.sideWidth}px` }}>
              <SideBar onSelectFolder={selectFolder} onOpenAsset={openAsset} />
            </div>
            <SplitHandle
              size={workbench.sideWidth}
              min={SIDE_LIMITS.min}
              oppositeMin={SIDE_LIMITS.oppositeMin}
              side="before"
              ariaLabel={text.sideBar.resize}
              onSizeChange={(width) => workbenchDispatch({ type: "SET_SIDE_WIDTH", width })}
              onCommit={commitSize}
            />
          </>
        ) : null}
        <div className="ci-shell__main">
          <EditorGroup
            onRequestClose={requestCloseTab}
            onSelectFolder={selectFolder}
            onShowFix={showFix}
            onNotify={notify}
          />
          {workbench.panelVisible ? (
            <>
              <SplitHandle
                size={workbench.panelHeight}
                min={PANEL_LIMITS.min}
                oppositeMin={PANEL_LIMITS.oppositeMin}
                orientation="horizontal"
                ariaLabel={text.panel.resize}
                onSizeChange={(height) => workbenchDispatch({ type: "SET_PANEL_HEIGHT", height })}
                onCommit={commitSize}
              />
              <div className="ci-shell__panel" style={{ height: `${workbench.panelHeight}px` }}>
                <Panel onOpenAsset={openAsset} />
              </div>
            </>
          ) : null}
        </div>
      </div>
      <StatusBar />

      {paletteOpen ? (
        <CommandPalette commands={commands} onClose={() => setPaletteOpen(false)} />
      ) : null}

      {pendingClose === null ? null : (
        <Modal
          title={text.modal.confirmDiscardTitle}
          testId="confirm-discard"
          onDismiss={() => setPendingClose(null)}
          actions={
            <>
              <button
                type="button"
                className="ci-button ci-button--danger"
                onClick={() => {
                  closeTab(pendingClose);
                  setPendingClose(null);
                }}
                data-testid="confirm-discard-yes"
              >
                {text.modal.discard}
              </button>
              <button
                type="button"
                className="ci-button"
                onClick={() => {
                  const id = pendingClose;
                  setPendingClose(null);
                  void sourceSave.save(id).then(() => closeTab(id));
                }}
                data-testid="confirm-discard-save"
              >
                {text.modal.saveAndClose}
              </button>
              <button
                type="button"
                className="ci-button"
                onClick={() => setPendingClose(null)}
                data-testid="confirm-discard-no"
              >
                {text.modal.keep}
              </button>
            </>
          }
        >
          {text.modal.confirmDiscardBody}
        </Modal>
      )}

      {conflict === null ? null : (
        <Modal
          title={text.save.conflictTitle}
          testId="save-conflict"
          wide={conflict.diskText !== null}
          onDismiss={sourceSave.dismissConflict}
          actions={
            <>
              <button
                type="button"
                className="ci-button ci-button--danger"
                onClick={sourceSave.overwrite}
                data-testid="save-conflict-overwrite"
              >
                {text.save.overwrite}
              </button>
              <button
                type="button"
                className="ci-button"
                onClick={() => {
                  const id = conflict.tabId;
                  sourceSave.dismissConflict();
                  void sourceSave.reload(id);
                }}
                data-testid="save-conflict-reload"
              >
                {text.save.reload}
              </button>
              <button
                type="button"
                className="ci-button"
                onClick={sourceSave.showConflictDiff}
                data-testid="save-conflict-diff"
              >
                {text.save.showDiff}
              </button>
            </>
          }
        >
          <p>{text.save.conflictBody(conflict.path)}</p>
          {conflict.diskText === null ? null : (
            <div className="ci-modal__diff">
              <DiffView
                original={conflict.diskText}
                modified={conflict.draft}
                languageId={languageIdFor(conflict.path)}
                ariaLabel={`${text.save.diskLabel} / ${text.save.draftLabel}`}
              />
            </div>
          )}
        </Modal>
      )}

      <Toast messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}

/** The application root: the stores wrapped around the shell. */
export function App(): ReactElement {
  return (
    <SettingsProvider>
      <ProjectProvider>
        <WorkbenchProvider>
          <EditorStatusProvider>
            <Shell />
          </EditorStatusProvider>
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>
  );
}
