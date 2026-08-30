/**
 * The shell's start-up and persistence effects: restore the stored settings, load the rule catalog,
 * and save the settings when a resize finishes.
 *
 * These are kept out of the shell component because each one is a lifecycle rule with a reason of
 * its own, and none of them has anything to do with what is drawn.
 */

import { useEffect } from "react";
import { api, errorMessage } from "../api";
import { useProjectDispatch } from "./projectStore";
import {
  PANE_SIZE_KEYS,
  useWorkbench,
  useWorkbenchDispatch,
} from "./workbenchStore";
import { toAppSettings, useSettings, useSettingsDispatch } from "./settingsStore";
import { useRulesDispatch } from "./rulesStore";

/** How a failure reaches the user. The shell passes its toast function. */
export type Notify = (message: string, failed?: boolean) => void;

export function useShellStartup(notify: Notify): void {
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
      .catch((error: unknown) => notify(errorMessage(error), true));
    return () => {
      cancelled = true;
    };
  }, [projectDispatch, rulesDispatch, notify]);

  /**
   * Save when a resize finishes rather than on every pixel of a drag, and never before the stored
   * settings have been read, which would erase them on start-up.
   */
  useEffect(() => {
    if (!settings.restored) {
      return;
    }
    api()
      .writeSettings({
        ...toAppSettings(settings),
        paneSizes: {
          ...settings.paneSizes,
          [PANE_SIZE_KEYS.side]: workbench.sideWidth,
          [PANE_SIZE_KEYS.panel]: workbench.panelHeight,
        },
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
    // The commit count and the chosen folder are the triggers; the sizes are read at that moment,
    // so listing them here would save on every pixel of a drag.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workbench.sizeCommitCount, settings.restored, settings.lastInputDir]);
}
