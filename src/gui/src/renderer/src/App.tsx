import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { api, errorMessage } from "./api";
import { text } from "./text";
import { ProjectProvider, useProject } from "./state/projectStore";
import {
  PANEL_LIMITS,
  SIDE_LIMITS,
  WorkbenchProvider,
  isTabDirty,
  sourceTab,
  useWorkbench,
  useWorkbenchDispatch,
} from "./state/workbenchStore";
import { SettingsProvider, useSettingsDispatch } from "./state/settingsStore";
import { useShellStartup } from "./state/useShellStartup";
import { buildCommands, type Command } from "./state/commands";
import { commandForChord, isPaletteChord } from "./state/keybindings";
import { useAnalysis } from "./state/useAnalysis";
import { closeDecision } from "./model/closeGuard";
import { ActivityBar } from "./shell/ActivityBar";
import { SideBar } from "./shell/SideBar";
import { EditorGroup } from "./shell/EditorGroup";
import { Panel } from "./shell/Panel";
import { StatusBar } from "./shell/StatusBar";
import { TitleBar } from "./shell/TitleBar";
import { CommandPalette } from "./shell/CommandPalette";
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

  const openAsset = useCallback(
    (path: string, line: number | null): void => {
      workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(path, line) });
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
        workbenchDispatch({ type: "CLOSE_TAB", id });
      } else {
        setPendingClose(id);
      }
    },
    [workbench, workbenchDispatch],
  );

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
      }),
    [project, workbench, workbenchDispatch, selectFolder, runAnalysis, cancelAnalysis, requestCloseTab],
  );

  // The keyboard chords. They are ignored while typing into a field, except inside the code editor.
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent): void => {
      if (isPaletteChord(event)) {
        event.preventDefault();
        setPaletteOpen(true);
        return;
      }
      const id = commandForChord(event);
      if (id === null) {
        return;
      }
      const command = commands.find((candidate) => candidate.id === id);
      if (command === undefined || !command.when()) {
        return;
      }
      event.preventDefault();
      command.run();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [commands]);

  const commitSize = useCallback((): void => {
    workbenchDispatch({ type: "COMMIT_SIZE" });
  }, [workbenchDispatch]);

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
          <EditorGroup onRequestClose={requestCloseTab} onSelectFolder={selectFolder} />
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
                  workbenchDispatch({ type: "CLOSE_TAB", id: pendingClose });
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
        >
          {text.modal.confirmDiscardBody}
        </Modal>
      )}

      <Toast messages={toasts} onDismiss={dismissToast} />
    </div>
  );
}

/** The application root: the three stores wrapped around the shell. */
export function App(): ReactElement {
  return (
    <SettingsProvider>
      <ProjectProvider>
        <WorkbenchProvider>
          <Shell />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>
  );
}
