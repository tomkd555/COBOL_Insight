import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactElement,
} from "react";
import { api, errorMessage } from "./api";
import { text } from "./i18n/text";
import { ProjectProvider, artifactItems, useProject } from "./state/projectStore";
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
import { SettingsProvider } from "./state/settingsStore";
import { RulesProvider } from "./state/rulesStore";
import { EditorStatusProvider } from "./state/editorStatusStore";
import { useShellStartup } from "./state/useShellStartup";
import { useTheme } from "./state/useTheme";
import { buildCommands, type Command } from "./state/commands";
import {
  SEQUENCE_TIMEOUT_MS,
  commandAfterPrefix,
  commandForChord,
  isModifierKey,
  isPaletteChord,
  isSequencePrefix,
} from "./state/keybindings";
import { useAnalysis } from "./state/useAnalysis";
import { useSourceSave } from "./state/useSourceSave";
import { assetTypeOf } from "./model/assetTree";
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

  const { persistPaneSizes } = useShellStartup(notify);
  useTheme();
  const analysis = useAnalysis(notify);
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

  /** Analyses what the explorer has selected: one asset, or everything under one folder. */
  const runAnalysis = useCallback((): void => {
    if (project.inputDir === null) {
      notify(text.run.noFolder, true);
      return;
    }
    const selection = workbench.selection;
    if (selection === null) {
      notify(text.run.noSelection, true);
      return;
    }
    // A copybook is not a unit of analysis: it is checked through the programs that copy it, so a
    // scope holding no program leaves the engine nothing to parse and its empty result would erase
    // the findings those copybooks genuinely have. The test is over the whole scope rather than the
    // selected item, because a folder of copybooks is as empty a scope as one copybook.
    const analysable = artifactItems(project.inventory).some(
      (item) =>
        (item.path === selection.path || item.path.startsWith(`${selection.path}/`)) &&
        assetTypeOf(item.type) !== "copybook",
    );
    if (!analysable) {
      notify(text.run.copybookSelection, true);
      return;
    }
    void analysis.runScope(project.inputDir, selection.path);
  }, [analysis, project.inputDir, project.inventory, workbench.selection, notify]);

  const runAnalysisAll = useCallback((): void => {
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
        if (folder !== project.inputDir) {
          // A tab id holds the path relative to the asset folder and nothing else, so the same
          // relative path in the new folder would inherit this folder's draft and be written over
          // with it. Every tab therefore goes, together with its model and its decode — and an
          // unsaved edit is never thrown away on the way: the switch waits for it to be settled.
          if (sourceSave.hasDirty) {
            notify(text.save.dirtyBeforeFolderChange, true);
            return;
          }
          for (const tab of workbench.tabs) {
            disposeModel(tab.id);
            forgetDocument(tab.id);
          }
          workbenchDispatch({ type: "CLOSE_ALL_TABS" });
        }
        // Opening a folder lists what is in it and stops there. Detecting the findings can take
        // minutes on a large folder, and it is the analysis the user asks for by name.
        void analysis.scan(folder);
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  }, [analysis, workbenchDispatch, project.inputDir, workbench.tabs, sourceSave.hasDirty, notify]);

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
        runAnalysisAll,
        cancelAnalysis,
        requestCloseTab,
        saveActiveTab,
        saveAllTabs,
        hasDirty: sourceSave.hasDirty,
      }),
    // project as a whole changes on every run-log line; only the fields buildCommands actually reads
    // (mode, inputDir, inventory) decide whether a command applies, so only those are listed here —
    // otherwise every log line during a run would rebuild the list and re-register the key listener.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      project.mode,
      project.inputDir,
      project.inventory,
      workbench,
      workbenchDispatch,
      selectFolder,
      runAnalysis,
      runAnalysisAll,
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
  const armTimer = useRef<number | null>(null);
  useEffect(() => {
    const disarm = (): void => {
      armed.current = false;
      if (armTimer.current !== null) {
        window.clearTimeout(armTimer.current);
        armTimer.current = null;
      }
    };
    const arm = (): void => {
      disarm();
      armed.current = true;
      armTimer.current = window.setTimeout(disarm, SEQUENCE_TIMEOUT_MS);
    };
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
        if (isModifierKey(event)) {
          // Ctrl released and pressed again on the way to the second key. Stay armed.
          return;
        }
        disarm();
        const sequenced = commandAfterPrefix(event);
        if (sequenced !== null) {
          event.preventDefault();
          runCommand(sequenced);
          return;
        }
      }
      if (isSequencePrefix(event)) {
        event.preventDefault();
        arm();
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
    // Leaving the window ends the gesture: the second key would land somewhere else entirely.
    window.addEventListener("blur", disarm);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("blur", disarm);
    };
  }, [commands]);

  const commitSize = useCallback((): void => {
    persistPaneSizes();
  }, [persistPaneSizes]);

  const conflict = sourceSave.conflict;

  return (
    <div
      className="ci-shell"
      // What the toasts stack above, so they never cover the panel (styles/overlays.css).
      style={
        {
          "--ci-panel-open-height": workbench.panelVisible
            ? `min(${workbench.panelHeight}px, 50%)`
            : "0px",
        } as CSSProperties
      }
    >
      <TitleBar
        onRun={runAnalysis}
        onRunAll={runAnalysisAll}
        target={workbench.selection}
        onCancel={cancelAnalysis}
        onSelectFolder={selectFolder}
      />
      <div className="ci-shell__body">
        <ActivityBar />
        {workbench.sideVisible ? (
          <>
            <div className="ci-shell__side" style={{ width: `${workbench.sideWidth}px` }}>
              <SideBar onOpenAsset={openAsset} notify={notify} />
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
            onRunAll={runAnalysisAll}
            notify={notify}
            onShowFix={showFix}
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
              <div
                className="ci-shell__panel"
                style={{ height: `min(${workbench.panelHeight}px, 50%)` }}
              >
                <Panel onOpenAsset={openAsset} onShowFix={showFix} />
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
                className="ci-button"
                onClick={() => {
                  const id = pendingClose;
                  setPendingClose(null);
                  // Only a written file closes its tab. A conflict or a failed write leaves the
                  // edit where it is, and closing then would be the one way to lose it.
                  void sourceSave.save(id).then((saved) => {
                    if (saved) {
                      closeTab(id);
                    }
                  });
                }}
                data-testid="confirm-discard-save"
              >
                {text.modal.saveAndClose}
              </button>
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
                onClick={() => setPendingClose(null)}
                data-testid="confirm-discard-no"
              >
                {text.modal.keep}
              </button>
            </>
          }
        />
      )}

      {conflict === null ? null : (
        <Modal
          title={text.save.conflictTitle}
          testId="save-conflict"
          wide={conflict.diskText !== null}
          onDismiss={sourceSave.dismissConflict}
          actions={
            // Safest first, destructive late, cancel last: nothing terminal sits under the cursor.
            <>
              <button
                type="button"
                className="ci-button ci-button--primary"
                onClick={sourceSave.showConflictDiff}
                data-testid="save-conflict-diff"
              >
                {text.save.showDiff}
              </button>
              <button
                type="button"
                className="ci-button ci-button--danger-quiet"
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
                className="ci-button ci-button--danger-quiet"
                onClick={sourceSave.overwrite}
                data-testid="save-conflict-overwrite"
              >
                {text.save.overwrite}
              </button>
              <button
                type="button"
                className="ci-button"
                onClick={sourceSave.dismissConflict}
                data-testid="save-conflict-cancel"
              >
                {text.save.conflictCancel}
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
        <RulesProvider>
          <WorkbenchProvider>
            <EditorStatusProvider>
              <Shell />
            </EditorStatusProvider>
          </WorkbenchProvider>
        </RulesProvider>
      </ProjectProvider>
    </SettingsProvider>
  );
}
