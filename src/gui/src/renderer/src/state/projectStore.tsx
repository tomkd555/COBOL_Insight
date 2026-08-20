/**
 * プロジェクトの状態。解析対象のフォルダ、解析のライフサイクル、engine が返した成果物
 * (資産一覧・指摘・SQL指摘)、ルール一覧、実行ログを持つ。
 *
 * 画面の配置(タブ・ペイン)は workbenchStore、保存する設定は settingsStore が持ち、この store は
 * 「engine から得たもの」だけを扱う。
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
  SarifFinding,
  UserRuleDefinition,
} from "../../../shared/engine-api";
import { EMPTY_RULE_CATALOG, buildRuleCatalog, type RuleCatalogIndex } from "../data/ruleCatalog";
import type { RuleCatalogEntry } from "../../../shared/engine-api";
import type { FixState } from "../screens/diff/diffModel";
import type { ScanDiscovery } from "../services/scanSummary";

/** 解析のライフサイクル。画面はこの値で空・実行中・結果・失敗の4状態を描き分ける。 */
export type AnalysisMode = "empty" | "running" | "results" | "error";

/** 実行中に提示する解析段(第1段=scan / 第2段=lint / 第3段=sql-lint)。 */
export type RunStage = 1 | 2 | 3;

/**
 * engine の成果物の取得状態。none=未取得、ready=取得済み、error=起動または読取の失敗。
 * 失敗を空配列(0 件)と区別することで、解析失敗を「指摘 0 件」として提示しない。
 */
export type ArtifactState<T> =
  | { readonly status: "none" }
  | { readonly status: "ready"; readonly items: readonly T[] }
  | { readonly status: "error"; readonly message: string };

/** 修正案の採否。未判定はキー不在で表す。 */
export type FixDecision = "adopted" | "rejected";

/** 実行ログの1行。下部パネルの実行ログがそのまま並べる。 */
export interface RunLogEntry {
  readonly id: number;
  /** 時刻(HH:MM:SS)。 */
  readonly time: string;
  readonly text: string;
  readonly failed: boolean;
}

export interface ProjectState {
  readonly mode: AnalysisMode;
  readonly runStage: RunStage;
  /** 製品バージョン(ステータスバー表示)。 */
  readonly version: string;

  /** 資産フォルダ(scan/lint/sql-lint の位置引数)。未選択は null。 */
  readonly inputDir: string | null;
  /** scan が書いた SQLite プロジェクトファイル。未解析は null。 */
  readonly dbPath: string | null;

  readonly inventory: ArtifactState<AssetInventoryItem>;
  readonly scanDiscovery: ScanDiscovery | null;
  readonly findings: ArtifactState<SarifFinding>;
  readonly sqlAdvice: ArtifactState<SarifFinding>;
  /** 書き戻しのたびに engine が返した再パース検証の誤り。指摘の表へ出所を分けて載せる。 */
  readonly saveFindings: readonly SarifFinding[];

  /** 修正案の取得状態。タブを閉じても保ち、開き直すたびに engine を起こし直さない。 */
  readonly fix: FixState;
  /** 修正案の採否。engine は選んで書き出せないため、人の判断の記録として保つ。 */
  readonly fixDecisions: Readonly<Record<string, FixDecision>>;

  /** ルール一覧の索引。名前・カテゴリ・説明の供給源は engine である。 */
  readonly catalog: RuleCatalogIndex;
  readonly userRules: readonly UserRuleDefinition[];
  /** engine が返した利用者定義ルールの定義の誤り。 */
  readonly userRuleErrors: readonly string[];

  /** 資産(相対パス)ごとの文字コード手動指定。次の解析で codepageOverrides として渡す。 */
  readonly codepageOverrides: Readonly<Record<string, string>>;

  readonly runLog: readonly RunLogEntry[];
}

export const initialProjectState: ProjectState = {
  mode: "empty",
  runStage: 1,
  version: "1.0.0",
  inputDir: null,
  dbPath: null,
  inventory: { status: "none" },
  scanDiscovery: null,
  findings: { status: "none" },
  sqlAdvice: { status: "none" },
  saveFindings: [],
  fix: { status: "idle" },
  fixDecisions: {},
  catalog: EMPTY_RULE_CATALOG,
  userRules: [],
  userRuleErrors: [],
  codepageOverrides: {},
  runLog: [],
};

export type ProjectAction =
  | { type: "SET_INPUT_DIR"; inputDir: string }
  | { type: "START_RUN" }
  | { type: "SET_RUN_STAGE"; stage: RunStage }
  | {
      type: "SET_INVENTORY";
      result: ArtifactState<AssetInventoryItem>;
      dbPath: string | null;
      discovery: ScanDiscovery | null;
    }
  | { type: "SET_FINDINGS"; result: ArtifactState<SarifFinding> }
  | { type: "SET_SQL_ADVICE"; result: ArtifactState<SarifFinding> }
  | { type: "SET_SAVE_FINDINGS"; path: string; findings: readonly SarifFinding[] }
  | { type: "SET_FIX"; fix: FixState }
  | { type: "SET_FIX_DECISIONS"; decisions: Readonly<Record<string, FixDecision>> }
  | { type: "FINISH_RUN"; failed: boolean }
  | { type: "CANCEL_RUN" }
  | { type: "SET_CATALOG"; entries: readonly RuleCatalogEntry[]; userRuleErrors: readonly string[] }
  | { type: "SET_USER_RULES"; rules: readonly UserRuleDefinition[] }
  | { type: "SET_CODEPAGE"; path: string; charset: string }
  | { type: "LOG"; text: string; failed?: boolean };

/** 実行ログの時刻。表示だけに使うため秒までとする。 */
function nowText(): string {
  return new Date().toTimeString().slice(0, 8);
}

/** 実行ログの保持件数。古い行から落とす。 */
const RUN_LOG_LIMIT = 200;

function appendLog(log: readonly RunLogEntry[], text: string, failed: boolean): RunLogEntry[] {
  const id = (log[log.length - 1]?.id ?? 0) + 1;
  return [...log, { id, time: nowText(), text, failed }].slice(-RUN_LOG_LIMIT);
}

export function projectReducer(state: ProjectState, action: ProjectAction): ProjectState {
  switch (action.type) {
    case "SET_INPUT_DIR":
      return { ...state, inputDir: action.inputDir };

    case "START_RUN":
      // 前回の成果物を捨てる。段ごとの結果が古い実行と混ざらないようにする。
      return {
        ...state,
        mode: "running",
        runStage: 1,
        inventory: { status: "none" },
        scanDiscovery: null,
        findings: { status: "none" },
        sqlAdvice: { status: "none" },
        saveFindings: [],
        fix: { status: "idle" },
        runLog: appendLog(state.runLog, "解析を開始しました。", false),
      };

    case "SET_RUN_STAGE":
      return { ...state, runStage: action.stage };

    case "SET_INVENTORY":
      return {
        ...state,
        inventory: action.result,
        scanDiscovery: action.discovery ?? state.scanDiscovery,
        dbPath: action.dbPath ?? state.dbPath,
        runLog: appendLog(
          state.runLog,
          action.result.status === "error"
            ? `走査に失敗しました。${action.result.message}`
            : `走査を終えました（資産 ${action.result.status === "ready" ? action.result.items.length : 0} 件）。`,
          action.result.status === "error",
        ),
      };

    case "SET_FINDINGS":
      return {
        ...state,
        findings: action.result,
        runLog: appendLog(
          state.runLog,
          action.result.status === "error"
            ? `指摘の検出に失敗しました。${action.result.message}`
            : `指摘の検出を終えました（${action.result.status === "ready" ? action.result.items.length : 0} 件）。`,
          action.result.status === "error",
        ),
      };

    case "SET_SQL_ADVICE":
      return {
        ...state,
        sqlAdvice: action.result,
        runLog: appendLog(
          state.runLog,
          action.result.status === "error"
            ? `SQL指摘の検出に失敗しました。${action.result.message}`
            : `SQL指摘の検出を終えました（${action.result.status === "ready" ? action.result.items.length : 0} 件）。`,
          action.result.status === "error",
        ),
      };

    case "SET_SAVE_FINDINGS":
      // 同じ資産を保存し直したら、前回の検証結果は残さない。
      return {
        ...state,
        saveFindings: [
          ...state.saveFindings.filter((finding) => finding.file !== action.path),
          ...action.findings,
        ],
      };

    case "SET_FIX":
      return { ...state, fix: action.fix };

    case "SET_FIX_DECISIONS":
      return { ...state, fixDecisions: { ...action.decisions } };

    case "FINISH_RUN":
      return { ...state, mode: action.failed ? "error" : "results" };

    case "CANCEL_RUN":
      return {
        ...state,
        mode: "results",
        runLog: appendLog(state.runLog, "解析をキャンセルしました（完了分の結果は残ります）。", false),
      };

    case "SET_CATALOG":
      return {
        ...state,
        catalog: buildRuleCatalog(action.entries),
        userRuleErrors: [...action.userRuleErrors],
      };

    case "SET_USER_RULES":
      return { ...state, userRules: [...action.rules] };

    case "SET_CODEPAGE":
      return {
        ...state,
        codepageOverrides: { ...state.codepageOverrides, [action.path]: action.charset },
      };

    case "LOG":
      return {
        ...state,
        runLog: appendLog(state.runLog, action.text, action.failed === true),
      };

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
  /** 初期状態の上書き。テストが任意の状態から描くために使う。 */
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
    throw new Error("useProject は ProjectProvider の内側で使う");
  }
  return state;
}

export function useProjectDispatch(): Dispatch<ProjectAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useProjectDispatch は ProjectProvider の内側で使う");
  }
  return dispatch;
}

/** 成果物の件数。取得済みは件数、それ以外(未取得・失敗)は null。 */
export function artifactCount<T>(result: ArtifactState<T>): number | null {
  return result.status === "ready" ? result.items.length : null;
}

/** 取得済みの要素。未取得・失敗は空配列。 */
export function artifactItems<T>(result: ArtifactState<T>): readonly T[] {
  return result.status === "ready" ? result.items : [];
}
