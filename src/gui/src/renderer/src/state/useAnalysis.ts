/**
 * Running the analysis: scan, then lint, then sql-lint, reporting progress and honouring a cancel.
 *
 * The scan stands on its own, because opening a folder only lists what is in it; the two stages that
 * produce findings are what the analysis adds.
 *
 * Each stage's artefact is read as soon as that stage finishes, so a run that is cancelled halfway
 * leaves the stages that did finish on screen. The killed stage's artefact is not read at all: the
 * engine was stopped before writing it, and reading it would report a failure that never happened.
 * A cancel that lands between stages, with nothing running, only keeps the next stage from starting.
 * A non-zero exit code carries finding severity, not failure, so only a rejected call or an
 * unreadable artefact marks a stage as failed.
 */

import { useCallback, useRef } from "react";
import type { EngineCommonOptions, EngineOutputPaths } from "../../../shared/ipc";
import { api, errorMessage } from "../api";
import { text } from "../i18n/text";
import {
  useProject,
  useProjectDispatch,
  type ProjectAction,
  type RunStage,
} from "./projectStore";
import { useSettings } from "./settingsStore";

export interface Analysis {
  /** Runs the scan alone against the given folder, which is what opening a folder does. */
  scan(inputDir: string): Promise<void>;
  /** Runs the three stages against the given folder. */
  run(inputDir: string): Promise<void>;
  /** Asks the engine to stop. The stages already finished keep their results. */
  cancel(): Promise<void>;
  /** Whether a run is in progress. */
  running: boolean;
}

type Dispatch = (action: ProjectAction) => void;

/** What the scan leaves behind: where the artefacts are, and whether the stage failed. */
interface ScanOutcome {
  paths: EngineOutputPaths;
  failed: boolean;
}

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

  const stage = useCallback(
    (index: RunStage, label: string): void => {
      dispatch({ type: "SET_RUN_STAGE", stage: index });
      dispatch({ type: "LOG", text: text.run.stageStarted(label) });
    },
    [dispatch],
  );

  /** A stage that did not finish: what failed, then the engine's own reason on the line below. */
  const stageFailed = useCallback(
    (label: string, reason: string): void => {
      dispatch({ type: "LOG", text: text.run.stageFailed(label), failed: true });
      dispatch({ type: "LOG", text: reason, failed: true });
    },
    [dispatch],
  );

  /**
   * Whether a cancel arrived while that stage was running, in which case the run is over. The engine
   * was killed before it wrote its artefact, so the caller stops here instead of reading one that is
   * not there and reporting a failure the user caused deliberately.
   */
  const stopped = useCallback(
    (label: string): boolean => {
      if (!cancelled.current) {
        return false;
      }
      dispatch({ type: "CANCEL_RUN" });
      dispatch({ type: "LOG", text: text.run.stageCancelled(label) });
      dispatch({ type: "LOG", text: text.run.cancelled });
      return true;
    },
    [dispatch],
  );

  /**
   * Whether a cancel arrived between two stages — while an artefact was being read, or after a stage
   * had already failed. Nothing was killed, so no stage is reported as cancelled; the next one is
   * simply not started.
   */
  const abandoned = useCallback((): boolean => {
    if (!cancelled.current) {
      return false;
    }
    dispatch({ type: "CANCEL_RUN" });
    dispatch({ type: "LOG", text: text.run.cancelled });
    return true;
  }, [dispatch]);

  const options = useCallback(
    (inputDir: string, paths: EngineOutputPaths): EngineCommonOptions => ({
      inputDir,
      copybookPaths: [...settings.copybookPaths],
      codepageOverrides: project.codepageOverrides,
      rulesFile: paths.rules,
    }),
    [project.codepageOverrides, settings.copybookPaths],
  );

  /**
   * Stage 1, which both entry points start with. Null means the run is already over: the output
   * paths could not be resolved, or the scan was cancelled.
   */
  const runScan = useCallback(
    async (inputDir: string): Promise<ScanOutcome | null> => {
      cancelled.current = false;
      dispatch({ type: "SET_INPUT_DIR", inputDir });
      dispatch({ type: "START_RUN" });

      let paths: EngineOutputPaths;
      try {
        paths = await api().outputPaths();
        dispatch({ type: "SET_OUTPUT_PATHS", paths });
      } catch (error) {
        dispatch({ type: "LOG", text: errorMessage(error), failed: true });
        dispatch({ type: "FINISH_RUN", failed: true });
        return null;
      }

      // Stage 1: scan. Its project file is what every later read depends on.
      stage(1, text.run.scan);
      try {
        await api().run({
          subcommand: "scan",
          request: { ...options(inputDir, paths), db: paths.db, copyExpansion: paths.copyExpansion },
        });
        if (stopped(text.run.scan)) {
          return null;
        }
        const ok = await readStage(
          () => api().readInventory(paths.db),
          (result) => {
            dispatch({ type: "SET_INVENTORY", result, dbPath: paths.db });
            if (result.status === "ready") {
              dispatch({ type: "LOG", text: text.run.scanDone(result.items.length) });
            } else {
              stageFailed(text.run.scan, result.message);
            }
          },
        );
        return { paths, failed: !ok };
      } catch (error) {
        dispatch({ type: "SET_INVENTORY", result: { status: "error", message: errorMessage(error) }, dbPath: null });
        stageFailed(text.run.scan, errorMessage(error));
        return { paths, failed: true };
      }
    },
    [dispatch, options, stage, stageFailed, stopped],
  );

  const scan = useCallback(
    async (inputDir: string): Promise<void> => {
      const scanned = await runScan(inputDir);
      if (scanned !== null && !abandoned()) {
        dispatch({ type: "FINISH_RUN", failed: scanned.failed });
      }
    },
    [abandoned, dispatch, runScan],
  );

  const run = useCallback(
    async (inputDir: string): Promise<void> => {
      dispatch({ type: "LOG", text: text.run.started });
      const scanned = await runScan(inputDir);
      if (scanned === null || abandoned()) {
        return;
      }
      const paths = scanned.paths;
      const common = options(inputDir, paths);
      let failed = scanned.failed;

      // Stage 2: lint.
      stage(2, text.run.lint);
      try {
        await api().run({ subcommand: "lint", request: { ...common, sarifFile: paths.sarif } });
        if (stopped(text.run.lint)) {
          return;
        }
        const ok = await readStage(
          () => api().readSarif(paths.sarif),
          (result) => {
            dispatch({ type: "SET_FINDINGS", result });
            if (result.status === "ready") {
              dispatch({ type: "LOG", text: text.run.stageDone(text.run.lint, result.items.length) });
            } else {
              stageFailed(text.run.lint, result.message);
            }
          },
        );
        failed = failed || !ok;
      } catch (error) {
        failed = true;
        dispatch({ type: "SET_FINDINGS", result: { status: "error", message: errorMessage(error) } });
        stageFailed(text.run.lint, errorMessage(error));
      }
      if (abandoned()) {
        return;
      }

      // Stage 3: sql-lint. Its SARIF goes to a separate file so it cannot overwrite stage 2's.
      stage(3, text.run.sqlLint);
      try {
        await api().run({ subcommand: "sql-lint", request: { ...common, sarifFile: paths.sqlSarif } });
        if (stopped(text.run.sqlLint)) {
          return;
        }
        const ok = await readStage(
          () => api().readSarif(paths.sqlSarif),
          (result) => {
            dispatch({ type: "SET_SQL_FINDINGS", result });
            if (result.status === "ready") {
              dispatch({
                type: "LOG",
                text: text.run.stageDone(text.run.sqlLint, result.items.length),
              });
            } else {
              stageFailed(text.run.sqlLint, result.message);
            }
          },
        );
        failed = failed || !ok;
      } catch (error) {
        failed = true;
        dispatch({ type: "SET_SQL_FINDINGS", result: { status: "error", message: errorMessage(error) } });
        stageFailed(text.run.sqlLint, errorMessage(error));
      }

      dispatch({ type: "FINISH_RUN", failed });
    },
    [abandoned, dispatch, options, runScan, stage, stageFailed, stopped],
  );

  const cancel = useCallback(async (): Promise<void> => {
    cancelled.current = true;
    await api().cancel();
  }, []);

  return { scan, run, cancel, running: project.mode === "running" };
}
