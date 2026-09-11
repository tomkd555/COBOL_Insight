/**
 * The project state: the folder under analysis, the analysis lifecycle, what the engine returned
 * (the inventory, the findings, the SQL findings), the rule index and the run log.
 *
 * Layout belongs to workbenchStore and persisted preferences to settingsStore; this store holds only
 * what came from the engine.
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import type {
  AssetInventoryItem,
  EngineOutputPaths,
  RuleCatalogEntry,
  SarifFinding,
} from "../../../shared/ipc";
import { EMPTY_RULE_INDEX, buildRuleIndex, type RuleIndex } from "../model/ruleIndex";

/** The analysis lifecycle. The screens draw the empty, running, results and error states from it. */
export type AnalysisMode = "empty" | "running" | "results" | "error";

/** The two stages of a run: scan, then lint. */
export type RunStage = 1 | 2;

/**
 * How an artefact stands. Keeping "error" apart from an empty list is what stops a failed analysis
 * from being presented as "no findings".
 */
export type ArtifactState<T> =
  | { readonly status: "none" }
  | { readonly status: "ready"; readonly items: readonly T[] }
  | { readonly status: "error"; readonly message: string };

/** One line of the run log, as the output panel lists it. */
export interface RunLogEntry {
  readonly id: number;
  /** HH:MM:SS. */
  readonly time: string;
  readonly text: string;
  readonly failed: boolean;
}

export interface ProjectState {
  readonly mode: AnalysisMode;
  readonly runStage: RunStage;
  /**
   * The artefact epoch: bumped whenever what the engine last read or wrote could have changed —
   * a finished run, a source save, or a rules-file write. FixDiff and TranspilePane key their cached
   * engine launch on this, so a stale preview or translation is never shown as current without
   * either view having to compare its own request by hand.
   */
  readonly runId: number;
  /** Whether the rules file was written after the last run finished, so a stale result can be flagged. */
  readonly rulesChangedAfterRun: boolean;
  /**
   * The runId of the last lint over the whole folder, and of the last lint over a scope, counting
   * only the runs that finished: one that failed wrote no SARIF and left the screen as it was. The
   * report is generated from the SARIF pair the whole-folder lint writes, so a larger
   * `lastScopedRunId` means the findings on screen are ahead of what a report would show, and
   * `lastWholeFolderRunId` of zero means there is no pair for this folder to generate one from.
   */
  readonly lastWholeFolderRunId: number;
  readonly lastScopedRunId: number;

  /** The asset folder (the positional argument of scan, lint and sql-lint). Null when unchosen. */
  readonly inputDir: string | null;
  /** The SQLite project file scan wrote. Null before the first analysis. */
  readonly dbPath: string | null;
  /** Where the engine writes its artefacts, as main resolved them. Null until they are fetched. */
  readonly outputPaths: EngineOutputPaths | null;

  readonly inventory: ArtifactState<AssetInventoryItem>;
  readonly findings: ArtifactState<SarifFinding>;
  readonly sqlFindings: ArtifactState<SarifFinding>;
  /** The reparse errors the engine returned on each write-back, listed with their own source. */
  readonly saveFindings: readonly SarifFinding[];

  /** The rule index. Rule names, categories and prose all come from the engine. */
  readonly rules: RuleIndex;
  /** What the engine reported about the rule configuration file. */
  readonly ruleErrors: readonly string[];
  /** Why the rule catalog could not be fetched at all, or null when it was. */
  readonly rulesError: string | null;

  /** Manual codepage per asset, passed as codepageOverrides on the next run. */
  readonly codepageOverrides: Readonly<Record<string, string>>;

  readonly runLog: readonly RunLogEntry[];
}

export const initialProjectState: ProjectState = {
  mode: "empty",
  runStage: 1,
  runId: 0,
  rulesChangedAfterRun: false,
  lastWholeFolderRunId: 0,
  lastScopedRunId: 0,
  inputDir: null,
  dbPath: null,
  outputPaths: null,
  inventory: { status: "none" },
  findings: { status: "none" },
  sqlFindings: { status: "none" },
  saveFindings: [],
  rules: EMPTY_RULE_INDEX,
  ruleErrors: [],
  rulesError: null,
  codepageOverrides: {},
  runLog: [],
};

export type ProjectAction =
  | { type: "SET_INPUT_DIR"; inputDir: string }
  | { type: "SET_OUTPUT_PATHS"; paths: EngineOutputPaths }
  /** `scope` is what a scoped run sets: its result is merged into the findings on screen. */
  | { type: "START_RUN"; scope?: string }
  | { type: "SET_RUN_STAGE"; stage: RunStage }
  | { type: "SET_INVENTORY"; result: ArtifactState<AssetInventoryItem>; dbPath: string | null }
  /** `scope` is the relative path a scoped lint covered; absent means the whole folder. */
  | { type: "SET_FINDINGS"; result: ArtifactState<SarifFinding>; scope?: string }
  | { type: "SET_SQL_FINDINGS"; result: ArtifactState<SarifFinding>; scope?: string }
  | { type: "SET_SAVE_FINDINGS"; path: string; findings: readonly SarifFinding[] }
  /** `lint` says which SARIF pair the run's lint stage wrote, or is absent when it ran none. */
  | { type: "FINISH_RUN"; failed: boolean; lint?: "whole" | "scoped" }
  | { type: "CANCEL_RUN" }
  | { type: "SET_RULES"; entries: readonly RuleCatalogEntry[]; ruleErrors: readonly string[] }
  | { type: "SET_RULES_ERROR"; message: string }
  | { type: "SET_CODEPAGE"; path: string; charset: string }
  | { type: "RULES_WRITTEN" }
  | { type: "SOURCE_SAVED" }
  | { type: "LOG"; text: string; failed?: boolean }
  | { type: "CLEAR_LOG" };

/** The run log's timestamp. It is only displayed, so seconds are enough. */
function nowText(): string {
  return new Date().toTimeString().slice(0, 8);
}

/** How many log lines are kept; the oldest are dropped. */
const RUN_LOG_LIMIT = 200;

function appendLog(log: readonly RunLogEntry[], text: string, failed: boolean): RunLogEntry[] {
  const id = (log[log.length - 1]?.id ?? 0) + 1;
  return [...log, { id, time: nowText(), text, failed }].slice(-RUN_LOG_LIMIT);
}

/** Whether a finding names the scoped asset itself or one inside the scoped folder. */
function inScope(file: string, scope: string): boolean {
  return file === scope || file.startsWith(`${scope}/`);
}

/**
 * What a lint stage leaves in an artefact. A whole-folder run replaces it outright, a failure
 * included: the failure has to be shown as one, and nothing on screen came from anywhere else.
 *
 * A scoped run replaces only the findings its scope covers and keeps the rest, because the files
 * outside the scope were not analysed and their findings still stand. A scoped run that failed
 * analysed nothing at all, so it keeps every finding and the failure is reported through the run log
 * and a notification instead.
 *
 * A file the scoped run reports on is a file it re-analysed, whether or not it sits inside the
 * scope: a copybook an in-scope program expands keeps its findings in the scoped result, so its
 * previous ones go too. Without that they would be listed a second time on every scoped run.
 */
function mergeFindings(
  previous: ArtifactState<SarifFinding>,
  next: ArtifactState<SarifFinding>,
  scope: string | undefined,
): ArtifactState<SarifFinding> {
  if (scope === undefined) {
    return next;
  }
  if (next.status !== "ready") {
    return previous;
  }
  if (previous.status !== "ready") {
    return next;
  }
  const reported = new Set(next.items.map((finding) => finding.file));
  return {
    status: "ready",
    items: [
      ...previous.items.filter(
        (finding) => !inScope(finding.file, scope) && !reported.has(finding.file),
      ),
      ...next.items,
    ],
  };
}

/**
 * The reparse findings of a save, once a lint result has arrived. A scoped run that finished
 * re-checked everything under its scope, so its own findings are the last word there; a run that
 * analysed nothing leaves them standing, or the parse error the user introduced would disappear
 * from the screen with nothing to replace it.
 *
 * Only the code artefact decides this. A save is reparsed as a program (model/save.ts), so the SQL
 * result says nothing about these findings, and reading it as a second opinion would drop them on a
 * run whose code SARIF could not be read at all.
 */
function keptSaveFindings(
  saveFindings: readonly SarifFinding[],
  next: ArtifactState<SarifFinding>,
  scope: string | undefined,
): readonly SarifFinding[] {
  if (scope === undefined || next.status !== "ready") {
    return saveFindings;
  }
  return saveFindings.filter((finding) => !inScope(finding.file, scope));
}

export function projectReducer(state: ProjectState, action: ProjectAction): ProjectState {
  switch (action.type) {
    case "SET_INPUT_DIR":
      if (action.inputDir === state.inputDir) {
        return state;
      }
      // Another folder: nothing read from the previous one describes this one. The two run ids go
      // with them, because the SARIF pair they stand for was written from the previous folder.
      return {
        ...state,
        inputDir: action.inputDir,
        dbPath: null,
        lastWholeFolderRunId: 0,
        lastScopedRunId: 0,
        inventory: { status: "none" },
        findings: { status: "none" },
        sqlFindings: { status: "none" },
        saveFindings: [],
      };

    case "SET_OUTPUT_PATHS":
      return { ...state, outputPaths: action.paths };

    case "START_RUN": {
      // The findings are discarded, so one stage's result cannot be read beside an older run's. The
      // inventory stays: the tree keeps naming the same folder, and a run cancelled during the scan
      // leaves the previous listing rather than an empty one. A scoped run keeps them instead: it
      // analyses one asset or one folder, and what it does not cover is merged back afterwards.
      // The reparse findings of a save are kept for the same reason, the scope included: a run that
      // never finishes re-checks nothing, so they are dropped when its result arrives, not here.
      const scope = action.scope;
      return {
        ...state,
        mode: "running",
        runStage: 1,
        findings: scope === undefined ? { status: "none" } : state.findings,
        sqlFindings: scope === undefined ? { status: "none" } : state.sqlFindings,
        saveFindings: scope === undefined ? [] : state.saveFindings,
      };
    }

    case "SET_RUN_STAGE":
      return { ...state, runStage: action.stage };

    case "SET_INVENTORY":
      // A null path is the scan saying it wrote no project file, so it clears the previous one:
      // otherwise the views reading it would keep drawing the assets of a run that has failed.
      return { ...state, inventory: action.result, dbPath: action.dbPath };

    case "SET_FINDINGS":
      return {
        ...state,
        findings: mergeFindings(state.findings, action.result, action.scope),
        saveFindings: keptSaveFindings(state.saveFindings, action.result, action.scope),
      };

    case "SET_SQL_FINDINGS":
      return {
        ...state,
        sqlFindings: mergeFindings(state.sqlFindings, action.result, action.scope),
      };

    case "SET_SAVE_FINDINGS":
      // Saving the same asset again replaces its previous verification result.
      return {
        ...state,
        saveFindings: [
          ...state.saveFindings.filter((finding) => finding.file !== action.path),
          ...action.findings,
        ],
      };

    case "FINISH_RUN": {
      const runId = state.runId + 1;
      // Only a lint that finished counts: one that failed wrote no SARIF pair, so the report has
      // nothing new to be generated from and nothing on screen moved ahead of it.
      const wrote = !action.failed;
      return {
        ...state,
        mode: action.failed ? "error" : "results",
        runId,
        lastWholeFolderRunId:
          wrote && action.lint === "whole" ? runId : state.lastWholeFolderRunId,
        lastScopedRunId: wrote && action.lint === "scoped" ? runId : state.lastScopedRunId,
        // Only a whole-folder lint puts the screen back level with the rule file. A scoped one left
        // everything outside its scope as the previous rules found it, and a failed one changed
        // nothing at all, so the note about the stale result still has to stand.
        rulesChangedAfterRun:
          wrote && action.lint === "whole" ? false : state.rulesChangedAfterRun,
      };
    }

    case "CANCEL_RUN":
      return { ...state, mode: "results" };

    case "SET_RULES":
      return {
        ...state,
        rules: buildRuleIndex(action.entries),
        ruleErrors: [...action.ruleErrors],
        rulesError: null,
      };

    case "SET_RULES_ERROR":
      return { ...state, rulesError: action.message };

    case "SET_CODEPAGE":
      return {
        ...state,
        codepageOverrides: { ...state.codepageOverrides, [action.path]: action.charset },
      };

    case "RULES_WRITTEN":
      return { ...state, runId: state.runId + 1, rulesChangedAfterRun: true };

    case "SOURCE_SAVED":
      return { ...state, runId: state.runId + 1 };

    case "LOG":
      return { ...state, runLog: appendLog(state.runLog, action.text, action.failed === true) };

    case "CLEAR_LOG":
      return { ...state, runLog: [] };

    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

const StateContext = createContext<ProjectState | null>(null);
const DispatchContext = createContext<Dispatch<ProjectAction> | null>(null);

export interface ProjectProviderProps {
  children: ReactNode;
  /** Overrides the initial state, so a test can render from any state. */
  initial?: ProjectState;
}

export function ProjectProvider({ children, initial }: ProjectProviderProps): ReactElement {
  const [state, dispatch] = useReducer(projectReducer, initial ?? initialProjectState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export function useProject(): ProjectState {
  const state = useContext(StateContext);
  if (state === null) {
    throw new Error("useProject must be used inside ProjectProvider");
  }
  return state;
}

export function useProjectDispatch(): Dispatch<ProjectAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useProjectDispatch must be used inside ProjectProvider");
  }
  return dispatch;
}

/** How many items an artefact holds; null when it is unfetched or failed. */
export function artifactCount<T>(result: ArtifactState<T>): number | null {
  return result.status === "ready" ? result.items.length : null;
}

/**
 * What stands for "no items". One array is shared by every caller: a fresh one each time would be a
 * new value on every render, and the memos and effects keyed on the result would never settle.
 */
const NO_ITEMS: readonly never[] = [];

/** The items an artefact holds; empty when it is unfetched or failed. */
export function artifactItems<T>(result: ArtifactState<T>): readonly T[] {
  return result.status === "ready" ? result.items : NO_ITEMS;
}
