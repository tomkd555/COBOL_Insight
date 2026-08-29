/**
 * Running the analysis: scan, then lint, then sql-lint, reporting progress and honouring a cancel.
 *
 * Each stage's artefact is read as soon as that stage finishes, so a run that is cancelled halfway
 * leaves the stages that did finish on screen. A non-zero exit code carries finding severity, not
 * failure, so only a rejected call or an unreadable artefact marks a stage as failed.
 */

import { useCallback, useRef } from "react";
import type { EngineCommonOptions, EngineOutputPaths } from "../../../shared/ipc";
import { api, errorMessage } from "../api";
import { text } from "../text";
import {
  useProject,
  useProjectDispatch,
  type ProjectAction,
  type RunStage,
} from "./projectStore";
import { useSettings } from "./settingsStore";

export interface Analysis {
  /** Runs the three stages against the given folder. */
  run(inputDir: string): Promise<void>;
  /** Asks the engine to stop. The stages already finished keep their results. */
  cancel(): Promise<void>;
  /** Whether a run is in progress. */
  running: boolean;
}

type Dispatch = (action: ProjectAction) => void;

/** Reads one artefact, turning a failure into an error state rather than an empty result. */
async function readStage<T>(
  read: () => Promise<T[]>,
  onResult: (result: { status: "ready"; items: T[] } | { status: "error"; message: string }) => void,
): Promise<boolean> {
  try {
    onResult({ status: "ready", items: await read() });
    return true;
  } catch (error) {
    onResult({ status: "error", message: errorMessage(error) });
    return false;
  }
}

export function useAnalysis(): Analysis {
  const project = useProject();
  const dispatch = useProjectDispatch() as Dispatch;
  const settings = useSettings();
  /** Set while a cancel is pending, so the remaining stages are skipped. */
  const cancelled = useRef(false);

  const run = useCallback(
    async (inputDir: string): Promise<void> => {
      cancelled.current = false;
      dispatch({ type: "SET_INPUT_DIR", inputDir });
      dispatch({ type: "START_RUN" });
      dispatch({ type: "LOG", text: text.run.started });

      let paths: EngineOutputPaths;
      try {
        paths = await api().outputPaths();
        dispatch({ type: "SET_OUTPUT_PATHS", paths });
      } catch (error) {
        dispatch({ type: "LOG", text: errorMessage(error), failed: true });
        dispatch({ type: "FINISH_RUN", failed: true });
        return;
      }

      const common: EngineCommonOptions = {
        inputDir,
        copybookPaths: [...settings.copybookPaths],
        codepageOverrides: project.codepageOverrides,
        rulesFile: paths.rules,
      };

      const stage = (index: RunStage, label: string): void => {
        dispatch({ type: "SET_RUN_STAGE", stage: index });
        dispatch({ type: "LOG", text: text.run.stageStarted(label) });
      };

      let failed = false;

      // Stage 1: scan. Its project file is what every later read depends on.
      stage(1, text.run.scan);
      try {
        await api().run({ subcommand: "scan", request: { ...common, db: paths.db, copyExpansion: paths.copyExpansion } });
        const ok = await readStage(
          () => api().readInventory(paths.db),
          (result) => {
            dispatch({ type: "SET_INVENTORY", result, dbPath: paths.db });
            dispatch({
              type: "LOG",
              text:
                result.status === "ready"
                  ? text.run.scanDone(result.items.length)
                  : text.run.stageFailed(text.run.scan, result.message),
              failed: result.status === "error",
            });
          },
        );
        failed = failed || !ok;
      } catch (error) {
        failed = true;
        dispatch({ type: "SET_INVENTORY", result: { status: "error", message: errorMessage(error) }, dbPath: null });
        dispatch({ type: "LOG", text: text.run.stageFailed(text.run.scan, errorMessage(error)), failed: true });
      }

      if (cancelled.current) {
        dispatch({ type: "CANCEL_RUN" });
        dispatch({ type: "LOG", text: text.run.cancelled });
        return;
      }

      // Stage 2: lint.
      stage(2, text.run.lint);
      try {
        await api().run({ subcommand: "lint", request: { ...common, sarifFile: paths.sarif } });
        const ok = await readStage(
          () => api().readSarif(paths.sarif),
          (result) => {
            dispatch({ type: "SET_FINDINGS", result });
            dispatch({
              type: "LOG",
              text:
                result.status === "ready"
                  ? text.run.stageDone(text.run.lint, result.items.length)
                  : text.run.stageFailed(text.run.lint, result.message),
              failed: result.status === "error",
            });
          },
        );
        failed = failed || !ok;
      } catch (error) {
        failed = true;
        dispatch({ type: "SET_FINDINGS", result: { status: "error", message: errorMessage(error) } });
        dispatch({ type: "LOG", text: text.run.stageFailed(text.run.lint, errorMessage(error)), failed: true });
      }

      if (cancelled.current) {
        dispatch({ type: "CANCEL_RUN" });
        dispatch({ type: "LOG", text: text.run.cancelled });
        return;
      }

      // Stage 3: sql-lint. Its SARIF goes to a separate file so it cannot overwrite stage 2's.
      stage(3, text.run.sqlLint);
      try {
        await api().run({ subcommand: "sql-lint", request: { ...common, sarifFile: paths.sqlSarif } });
        const ok = await readStage(
          () => api().readSarif(paths.sqlSarif),
          (result) => {
            dispatch({ type: "SET_SQL_FINDINGS", result });
            dispatch({
              type: "LOG",
              text:
                result.status === "ready"
                  ? text.run.stageDone(text.run.sqlLint, result.items.length)
                  : text.run.stageFailed(text.run.sqlLint, result.message),
              failed: result.status === "error",
            });
          },
        );
        failed = failed || !ok;
      } catch (error) {
        failed = true;
        dispatch({ type: "SET_SQL_FINDINGS", result: { status: "error", message: errorMessage(error) } });
        dispatch({ type: "LOG", text: text.run.stageFailed(text.run.sqlLint, errorMessage(error)), failed: true });
      }

      if (cancelled.current) {
        dispatch({ type: "CANCEL_RUN" });
        dispatch({ type: "LOG", text: text.run.cancelled });
        return;
      }
      dispatch({ type: "FINISH_RUN", failed });
    },
    [dispatch, project.codepageOverrides, settings.copybookPaths],
  );

  const cancel = useCallback(async (): Promise<void> => {
    cancelled.current = true;
    await api().cancel();
  }, []);

  return { run, cancel, running: project.mode === "running" };
}
