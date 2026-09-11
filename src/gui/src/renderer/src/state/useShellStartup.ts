/**
 * The shell's start-up and persistence effects: restore the stored settings, load the rule catalog,
 * and save the settings when a resize finishes.
 *
 * These are kept out of the shell component because each one is a lifecycle rule with a reason of
 * its own, and none of them has anything to do with what is drawn.
 */

import { useCallback, useEffect } from "react";
import { api, errorMessage } from "../api";
import { useProjectDispatch } from "./projectStore";
import {
  PANE_SIZE_KEYS,
  useWorkbench,
  useWorkbenchDispatch,
} from "./workbenchStore";
import { useSettings, useSettingsDispatch } from "./settingsStore";
import { useRulesDispatch } from "./rulesStore";

/** How a failure reaches the user. The shell passes its toast function. */
export type Notify = (message: string, failed?: boolean) => void;

export interface ShellStartup {
  /** Writes the current pane sizes to the settings file. Called once a resize drag ends. */
  persistPaneSizes: () => void;
}

export function useShellStartup(notify: Notify): ShellStartup {
  const projectDispatch = useProjectDispatch();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();
  const settings = useSettings();
  const settingsDispatch = useSettingsDispatch();
  const rulesDispatch = useRulesDispatch();

  // Restore the stored settings once, before anything is saved back over them.
  useEffect(() => {
    let cancelled = false;
    api()
      .readSettings()
      .then((stored) => {
        if (cancelled) return;
        settingsDispatch({ type: "RESTORE", settings: stored });
        workbenchDispatch({
          type: "RESTORE_SIZES",
          sideWidth: stored.paneSizes[PANE_SIZE_KEYS.side],
          panelHeight: stored.paneSizes[PANE_SIZE_KEYS.panel],
        });
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
    return () => {
      cancelled = true;
    };
  }, [settingsDispatch, workbenchDispatch, notify]);

  // Load the rule configuration and the catalog it produces once the output paths are known, so
  // findings can name their rule and the rules screen has a file to edit.
  useEffect(() => {
    let cancelled = false;
    api()
      .outputPaths()
      .then(async (paths) => {
        projectDispatch({ type: "SET_OUTPUT_PATHS", paths });
        const [file, catalog] = await Promise.all([
          api().readRules(paths.rules),
          api().rules({ rulesFile: paths.rules }),
        ]);
        if (cancelled) return;
        rulesDispatch({ type: "LOAD", file });
        projectDispatch({
          type: "SET_RULES",
          entries: catalog.rules,
          ruleErrors: catalog.ruleErrors,
        });
      })
      .catch((error: unknown) => {
        // The rules view says so where the list would be; the toast alone would leave it loading.
        projectDispatch({ type: "SET_RULES_ERROR", message: errorMessage(error) });
        notify(errorMessage(error), true);
      });
    return () => {
      cancelled = true;
    };
  }, [projectDispatch, rulesDispatch, notify]);

  /**
   * Writes the current pane sizes once a resize drag ends, rather than on every pixel dragged. Never
   * before the stored settings have been read, which would erase them on start-up.
   */
  const persistPaneSizes = useCallback((): void => {
    if (!settings.restored) {
      return;
    }
    api()
      .writeSettings({
        paneSizes: {
          [PANE_SIZE_KEYS.side]: workbench.sideWidth,
          [PANE_SIZE_KEYS.panel]: workbench.panelHeight,
        },
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  }, [settings, workbench.sideWidth, workbench.panelHeight, notify]);

  return { persistPaneSizes };
}
