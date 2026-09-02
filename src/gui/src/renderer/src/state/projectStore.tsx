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

/** The three stages of a run: scan, lint, sql-lint. */
export type RunStage = 1 | 2 | 3;

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
  | { type: "START_RUN" }
  | { type: "SET_RUN_STAGE"; stage: RunStage }
  | { type: "SET_INVENTORY"; result: ArtifactState<AssetInventoryItem>; dbPath: string | null }
  | { type: "SET_FINDINGS"; result: ArtifactState<SarifFinding> }
  | { type: "SET_SQL_FINDINGS"; result: ArtifactState<SarifFinding> }
  | { type: "SET_SAVE_FINDINGS"; path: string; findings: readonly SarifFinding[] }
  | { type: "FINISH_RUN"; failed: boolean }
  | { type: "CANCEL_RUN" }
  | { type: "SET_RULES"; entries: readonly RuleCatalogEntry[]; ruleErrors: readonly string[] }
  | { type: "SET_RULES_ERROR"; message: string }
  | { type: "SET_CODEPAGE"; path: string; charset: string }
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

export function projectReducer(state: ProjectState, action: ProjectAction): ProjectState {
  switch (action.type) {
    case "SET_INPUT_DIR":
      if (action.inputDir === state.inputDir) {
        return state;
      }
      // Another folder: nothing read from the previous one describes this one.
      return {
        ...state,
        inputDir: action.inputDir,
        dbPath: null,
        inventory: { status: "none" },
        findings: { status: "none" },
        sqlFindings: { status: "none" },
        saveFindings: [],
      };

    case "SET_OUTPUT_PATHS":
      return { ...state, outputPaths: action.paths };

    case "START_RUN":
      // The findings are discarded, so one stage's result cannot be read beside an older run's. The
      // inventory stays: the tree keeps naming the same folder, and a run cancelled during the scan
      // leaves the previous listing rather than an empty one.
      return {
        ...state,
        mode: "running",
        runStage: 1,
        findings: { status: "none" },
        sqlFindings: { status: "none" },
        saveFindings: [],
      };

    case "SET_RUN_STAGE":
      return { ...state, runStage: action.stage };

    case "SET_INVENTORY":
      return { ...state, inventory: action.result, dbPath: action.dbPath ?? state.dbPath };

    case "SET_FINDINGS":
      return { ...state, findings: action.result };

    case "SET_SQL_FINDINGS":
      return { ...state, sqlFindings: action.result };

    case "SET_SAVE_FINDINGS":
      // Saving the same asset again replaces its previous verification result.
      return {
        ...state,
        saveFindings: [
          ...state.saveFindings.filter((finding) => finding.file !== action.path),
          ...action.findings,
        ],
      };

    case "FINISH_RUN":
      return { ...state, mode: action.failed ? "error" : "results" };

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
