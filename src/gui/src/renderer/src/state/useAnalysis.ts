/**
 * Running the analysis: scan, then lint, reporting progress and honouring a cancel.
 *
 * The scan stands on its own, because opening a folder only lists what is in it; lint, which writes
 * both the code and the SQL findings in one run, is what the analysis adds. A scoped run is lint
 * alone, preceded by a scan only when assets were written since the last scan read them: the folder
 * was scanned when it was opened, and only the findings of the chosen asset or folder are asked for
 * again.
 *
 * Each stage's artefact is read as soon as that stage finishes, so a run that is cancelled halfway
 * leaves the stages that did finish on screen. The killed stage's artefact is not read at all: the
 * engine was stopped before writing it, and reading it would report a failure that never happened.
 * A cancel that lands between stages, with nothing running, only keeps the next stage from starting.
 * A non-zero exit code carries the severity of the findings and counts as a normal finish. A stage
 * fails when the call is rejected, when its artefact cannot be read, or when the engine printed no
 * summary line — which is how a crashed run is told apart from a finished one that found nothing.
 */

import { useCallback, useRef } from "react";
import type {
  EngineCommonOptions,
  EngineInvocation,
  EngineOutputPaths,
  EngineResult,
  SarifFinding,
} from "../../../shared/ipc";
import { api, engineFailure, errorMessage } from "../api";
import { clearCopyExpansionCache } from "../editors/source/copyZones";
import { text } from "../i18n/text";
import {
  useProject,
  useProjectDispatch,
  type ArtifactState,
  type ProjectAction,
  type RunStage,
} from "./projectStore";
import { viewRunsSettled } from "./engineGate";
import { useSettings } from "./settingsStore";
import type { Notify } from "./useShellStartup";

export interface Analysis {
  /** Runs the scan alone against the given folder, which is what opening a folder does. */
  scan(inputDir: string): Promise<void>;
  /**
   * Scans the whole folder again because assets were written since the last scan, so the structure
   * the project file holds — the paragraphs, the flows, the inventory — is that of the files as they
   * now stand.
   * The findings on screen stay: no lint runs, and the scan reads none.
   */
  refresh(inputDir: string): Promise<void>;
  /** Runs both stages against the whole folder. */
  run(inputDir: string): Promise<void>;
  /**
   * Runs lint over one asset or one folder inside the asset folder, given as a path relative to
   * it, after a scan where assets were written since the last one. The findings it returns replace
   * the ones under that path and leave the rest standing; a run that fails analysed nothing, so
   * every finding stays and a notification reports the failure.
   */
  runScope(inputDir: string, scope: string): Promise<void>;
  /**
   * Stops the analysis: it kills the stage's engine when one is running, and keeps any later stage
   * from starting. The stages already finished keep their results.
   */
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

/** The engine's stderr as log lines. Blank lines carry nothing and are dropped. */
function stderrLines(stderr: string): string[] {
  return stderr
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== "");
}

/** Whether the engine wrote anything to stderr, which is what the run log already carries. */
function hasStderr(stderr: string): boolean {
  return stderrLines(stderr).length > 0;
}

/**
 * Reads one artefact. A failure becomes an error state, kept apart from an empty list so the screen
 * shows it as a failure.
 */
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

export function useAnalysis(notify: Notify): Analysis {
  const project = useProject();
  const dispatch = useProjectDispatch() as Dispatch;
  const settings = useSettings();
  /** Set while a cancel is pending, so the remaining stages are skipped. */
  const cancelled = useRef(false);
  /** Whether a stage of the analysis has its engine running, which is what a cancel stops. */
  const stageInFlight = useRef(false);
  /** Ends a stage's wait for the views' runs; set while a stage waits. */
  const endWait = useRef<(() => void) | null>(null);

  /**
   * Launches the engine for one stage, after any run a view started has ended. A cancel that came
   * at any point before the launch launches nothing, and one that comes during that wait ends the
   * wait at once: the empty result is handed to the caller, whose `stopped` check then ends the run.
   */
  const launch = useCallback(async (invocation: EngineInvocation): Promise<EngineResult> => {
    const waitEnded = new Promise<void>((resolve) => {
      endWait.current = resolve;
    });
    await Promise.race([viewRunsSettled(), waitEnded]);
    endWait.current = null;
    if (cancelled.current) {
      return {
        subcommand: invocation.subcommand,
        exitCode: -1,
        summary: null,
        stdout: "",
        stderr: "",
        outputs: {},
      };
    }
    stageInFlight.current = true;
    try {
      return await api().run(invocation);
    } finally {
      stageInFlight.current = false;
    }
  }, []);

  const stage = useCallback(
    (index: RunStage, label: string): void => {
      dispatch({ type: "SET_RUN_STAGE", stage: index });
      dispatch({ type: "LOG", text: text.run.stageStarted(label) });
    },
    [dispatch],
  );

  /**
   * The engine's stderr into the run log. The discovery warnings (a file of undecided kind, the
   * file ceiling, an unreadable file) are printed there and reach the screen nowhere else.
   */
  const logStderr = useCallback(
    (stderr: string): void => {
      for (const line of stderrLines(stderr)) {
        dispatch({ type: "LOG", text: line });
      }
    },
    [dispatch],
  );

  /**
   * A stage that did not finish: what failed, and its reason on the line below. The reason is left
   * out where it is already in the log — the engine's stderr is written there line by line, and
   * repeating its last lines underneath would say the same thing twice.
   */
  const stageFailed = useCallback(
    (label: string, reason?: string): void => {
      dispatch({ type: "LOG", text: text.run.stageFailed(label), failed: true });
      if (reason !== undefined) {
        dispatch({ type: "LOG", text: reason, failed: true });
      }
    },
    [dispatch],
  );

  /**
   * Whether a cancel arrived while that stage was running, in which case the run is over. The engine
   * was killed before it wrote its artefact, or was never launched, so the caller stops here: reading
   * the absent artefact would report as a failure what the user did deliberately.
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

  /** Where the artefacts go. Null means the run is over before any stage started. */
  const resolvePaths = useCallback(async (): Promise<EngineOutputPaths | null> => {
    try {
      const paths = await api().outputPaths();
      dispatch({ type: "SET_OUTPUT_PATHS", paths });
      return paths;
    } catch (error) {
      dispatch({ type: "LOG", text: errorMessage(error), failed: true });
      dispatch({ type: "FINISH_RUN", failed: true });
      return null;
    }
  }, [dispatch]);

  /**
   * Stage 1, which the whole-folder entry points start with. Null means the run is already over:
   * the output paths could not be resolved, or the scan was cancelled.
   */
  const runScan = useCallback(
    async (inputDir: string, keepFindings = false): Promise<ScanOutcome | null> => {
      cancelled.current = false;
      dispatch({ type: "SET_INPUT_DIR", inputDir });
      dispatch({ type: "START_RUN", keepFindings });

      const paths = await resolvePaths();
      if (paths === null) {
        return null;
      }

      // Stage 1: scan. Its project file is what every later read depends on.
      stage(1, text.run.scan);
      try {
        const finished = await launch({
          subcommand: "scan",
          request: { ...options(inputDir, paths), db: paths.db, copyExpansion: paths.copyExpansion },
        });
        logStderr(finished.stderr);
        // A scan that printed its summary has rewritten the project file, whatever came after: a
        // cancel that arrived as it ended stops the run at the next stage, and the views still get
        // the inventory and, once the scan is recorded (STRUCTURE_SCANNED moves the epoch the source
        // view keys its read on), the fresh COPY expansion of the file as it now is.
        if (finished.summary === null && stopped(text.run.scan)) {
          return null;
        }
        const crashed = engineFailure(finished);
        if (crashed !== null) {
          // The engine died. After a scan the user asked for, the project file still holds the
          // previous run's assets, so the path is cleared and the views reading it stop drawing them.
          // The scan after a write keeps them: they describe the folder as it was before the write,
          // the written assets stay stale, and the views say what is still unread.
          if (!keepFindings) {
            dispatch({ type: "SET_INVENTORY", result: { status: "error", message: crashed }, dbPath: null });
          }
          stageFailed(text.run.scan, hasStderr(finished.stderr) ? undefined : crashed);
          return { paths, failed: true };
        }
        // A finished scan is the only thing that rewrites the COPY expansion table; anything the
        // source view cached from an earlier one is now stale.
        clearCopyExpansionCache();
        const ok = await readStage(
          () => api().readInventory(paths.db, inputDir),
          (result) => {
            // An inventory the scan after a write could not read back leaves the one on screen.
            if (result.status === "ready" || !keepFindings) {
              dispatch({ type: "SET_INVENTORY", result, dbPath: paths.db });
            }
            if (result.status === "ready") {
              dispatch({ type: "LOG", text: text.run.scanDone(result.items.length) });
            } else {
              stageFailed(text.run.scan, result.message);
            }
          },
        );
        return { paths, failed: !ok };
      } catch (error) {
        if (!keepFindings) {
          dispatch({ type: "SET_INVENTORY", result: { status: "error", message: errorMessage(error) }, dbPath: null });
        }
        stageFailed(text.run.scan, errorMessage(error));
        return { paths, failed: true };
      }
    },
    [dispatch, launch, logStderr, options, resolvePaths, stage, stageFailed, stopped],
  );

  /**
   * Stage 2: lint. One engine run writes both the code and the SQL findings; a scope confines it to
   * one asset or one folder. Null means a cancel killed it and the run is over; otherwise the answer
   * carries why the stage failed, with a null reason when it finished.
   */
  const runLint = useCallback(
    async (
      inputDir: string,
      paths: EngineOutputPaths,
      scope?: string,
    ): Promise<{ reason: string | null } | null> => {
      stage(2, text.run.lint);
      // A scoped run writes a pair of its own: the whole-folder pair is what the report is generated
      // from, and a run over one asset must not leave that file holding one asset's findings.
      const sarifFile = scope === undefined ? paths.sarif : paths.scopedSarif;
      const sqlSarifFile = scope === undefined ? paths.sqlSarif : paths.scopedSqlSarif;
      try {
        const finished = await launch({
          subcommand: "lint",
          request: {
            ...options(inputDir, paths),
            sarifFile,
            sqlSarifFile,
            scope: scope === undefined ? undefined : [scope],
          },
        });
        logStderr(finished.stderr);
        if (stopped(text.run.lint)) {
          return null;
        }
        const crashed = engineFailure(finished);
        if (crashed !== null) {
          const failure: ArtifactState<SarifFinding> = { status: "error", message: crashed };
          dispatch({ type: "SET_FINDINGS", result: failure, scope });
          dispatch({ type: "SET_SQL_FINDINGS", result: failure, scope });
          stageFailed(text.run.lint, hasStderr(finished.stderr) ? undefined : crashed);
          return { reason: crashed };
        }
        let count = 0;
        let reason: string | null = null;
        const lintOk = await readStage(
          () => api().readSarif(sarifFile),
          (result) => {
            dispatch({ type: "SET_FINDINGS", result, scope });
            if (result.status === "ready") {
              count += result.items.length;
            } else {
              reason ??= result.message;
              stageFailed(text.run.lint, result.message);
            }
          },
        );
        const sqlOk = await readStage(
          () => api().readSarif(sqlSarifFile),
          (result) => {
            dispatch({ type: "SET_SQL_FINDINGS", result, scope });
            if (result.status === "ready") {
              count += result.items.length;
            } else {
              reason ??= result.message;
              stageFailed(text.run.lint, result.message);
            }
          },
        );
        if (lintOk && sqlOk) {
          dispatch({ type: "LOG", text: text.run.stageDone(text.run.lint, count) });
        }
        return { reason };
      } catch (error) {
        const failure: ArtifactState<SarifFinding> = {
          status: "error",
          message: errorMessage(error),
        };
        dispatch({ type: "SET_FINDINGS", result: failure, scope });
        dispatch({ type: "SET_SQL_FINDINGS", result: failure, scope });
        stageFailed(text.run.lint, errorMessage(error));
        return { reason: errorMessage(error) };
      }
    },
    [dispatch, launch, logStderr, options, stage, stageFailed, stopped],
  );

  const writesNow = project.staleWrites;
  const staleNow = project.structureStale.length > 0;
  /**
   * Stage 1, with its outcome recorded against the writes it started from: what had been written by
   * then is what the scan read, and a write made while it ran keeps the assets stale. The record is
   * made after a failed or stopped scan too: it sets `staleTried`, which ends `structureScanDue`, so
   * the effect in App.tsx that starts the scan after a write leaves it until the next write.
   */
  const scanFolder = useCallback(
    async (
      inputDir: string,
      { keepFindings }: { keepFindings: boolean },
    ): Promise<ScanOutcome | null> => {
      const writes = writesNow;
      let scanned: ScanOutcome | null = null;
      try {
        scanned = await runScan(inputDir, keepFindings);
        return scanned;
      } finally {
        dispatch({
          type: "STRUCTURE_SCANNED",
          writes,
          ok: scanned !== null && !scanned.failed,
        });
      }
    },
    [dispatch, runScan, writesNow],
  );

  const scan = useCallback(
    async (inputDir: string): Promise<void> => {
      const scanned = await scanFolder(inputDir, { keepFindings: false });
      if (scanned !== null && !abandoned()) {
        dispatch({ type: "FINISH_RUN", failed: scanned.failed });
      }
    },
    [abandoned, dispatch, scanFolder],
  );

  const refresh = useCallback(
    async (inputDir: string): Promise<void> => {
      const scanned = await scanFolder(inputDir, { keepFindings: true });
      if (scanned !== null && !abandoned()) {
        dispatch({ type: "FINISH_RUN", failed: scanned.failed });
      }
    },
    [abandoned, dispatch, scanFolder],
  );

  const run = useCallback(
    async (inputDir: string): Promise<void> => {
      dispatch({ type: "LOG", text: text.run.started });
      const scanned = await scanFolder(inputDir, { keepFindings: false });
      if (scanned === null || abandoned()) {
        return;
      }
      if (scanned.failed) {
        // A scan that failed left no project file, so the explorer, the graph and the report say
        // the folder could not be read. Linting it anyway would fill the problems panel with
        // findings for assets the rest of the shell cannot show.
        dispatch({ type: "FINISH_RUN", failed: true });
        return;
      }
      const lint = await runLint(inputDir, scanned.paths);
      if (lint === null || abandoned()) {
        return;
      }
      dispatch({ type: "FINISH_RUN", failed: lint.reason !== null, lint: "whole" });
    },
    [abandoned, dispatch, runLint, scanFolder],
  );

  const runScope = useCallback(
    async (inputDir: string, scope: string): Promise<void> => {
      cancelled.current = false;
      if (staleNow) {
        // Assets were written that no finished scan has read (the scan after the write failed, was
        // stopped, ran before a later write, or has yet to run), so the project file is brought
        // level first.
        const scanned = await scanFolder(inputDir, { keepFindings: true });
        if (scanned === null || abandoned()) {
          return;
        }
        if (scanned.failed) {
          dispatch({ type: "FINISH_RUN", failed: true });
          return;
        }
      }
      // The findings already on screen stay: this run replaces only what its scope covers.
      dispatch({ type: "SET_INPUT_DIR", inputDir });
      dispatch({ type: "START_RUN", scope });
      // Named before the output paths are fetched: from here on this run has no scan stage, and the
      // title bar would otherwise name one for as long as that call takes.
      dispatch({ type: "SET_RUN_STAGE", stage: 2 });
      dispatch({ type: "LOG", text: text.run.startedScope(scope) });

      const paths = await resolvePaths();
      if (paths === null) {
        return;
      }
      // The project file describes the folder: it was scanned on opening, or just above for the
      // assets written since.
      const lint = await runLint(inputDir, paths, scope);
      if (lint === null || abandoned()) {
        return;
      }
      if (lint.reason !== null) {
        // The notice names the failure and the first line of its reason, and leaves the findings on
        // screen unmentioned: the lint writes two artefacts, and one can have been read and merged
        // while the other was left unread. The run log carries which artefact failed and the whole
        // reason.
        notify(text.run.scopeFailed(scope, lint.reason.split(/\r?\n/)[0]), true);
      }
      dispatch({ type: "FINISH_RUN", failed: lint.reason !== null, lint: "scoped" });
    },
    [abandoned, dispatch, notify, resolvePaths, runLint, scanFolder, staleNow],
  );

  const cancel = useCallback(async (): Promise<void> => {
    cancelled.current = true;
    // While a stage waits for a view's run to end, that run is the engine in flight, and it is the
    // view's own: it runs on, and the stage stops waiting for it and ends the run.
    endWait.current?.();
    if (stageInFlight.current) {
      await api().cancel();
    }
  }, []);

  return { scan, refresh, run, runScope, cancel, running: project.mode === "running" };
}
