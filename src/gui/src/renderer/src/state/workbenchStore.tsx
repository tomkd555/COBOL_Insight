/**
 * 作業面の状態。開いているタブ、選択中のタブ、左右・下のパネルの開閉と寸法を持つ。
 *
 * タブの見出しはここが唯一の供給源である。画面ごとのラベル表を別に持たない。
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";

/** タブの種類。source だけが資産1件に紐づき、残りは同種のタブを1枚だけ開く。 */
export type TabKind = "source" | "graph" | "rules" | "report" | "settings" | "fix";

/** source 以外のタブの見出し。 */
const SINGLETON_TAB_TITLES: Readonly<Record<Exclude<TabKind, "source">, string>> = {
  graph: "呼出関係図",
  rules: "ルール",
  report: "レポート",
  settings: "設定",
  fix: "修正案",
};

/** source 以外のタブの見出しを引く。 */
export function singletonTabTitle(kind: Exclude<TabKind, "source">): string {
  return SINGLETON_TAB_TITLES[kind];
}

export interface WorkbenchTab {
  /** タブの識別子。source は "source:<相対パス>"、それ以外は種類そのもの。 */
  readonly id: string;
  readonly kind: TabKind;
  /** タブに出す見出し。source はファイル名。 */
  readonly title: string;
  /** source タブが開いている資産の相対パス。他の種類では null。 */
  readonly path: string | null;
  /** 開いた直後に見せる行。指定が無ければ null。 */
  readonly line: number | null;
}

/** 下部パネルに出す面。 */
export type BottomView = "findings" | "log";

/** 左のアクティビティバーで選ぶ面。現時点で側パネルへ出せるのは資産一覧だけである。 */
export type SideView = "explorer";

export interface WorkbenchState {
  readonly tabs: readonly WorkbenchTab[];
  readonly activeTabId: string | null;
  readonly sideVisible: boolean;
  readonly sideWidth: number;
  readonly sideView: SideView;
  readonly bottomVisible: boolean;
  readonly bottomHeight: number;
  readonly bottomView: BottomView;
  /**
   * 保存していない編集後の本文(タブの ID → 全文)。選んでいないタブは描かないため、本文の面が
   * 持つと切り替えのたびに編集が消える。未保存の印もこの有無がそのまま表す。
   */
  readonly drafts: Readonly<Record<string, string>>;
  /**
   * 直近の保存(資産の相対パスと時刻)。トーストは数秒で消えるため、保存できたことを
   * ステータスバーに残す。次の編集で消す。
   */
  readonly lastSave: { readonly path: string; readonly at: number } | null;
  /**
   * 寸法の操作を終えた回数。ドラッグは1画素ごとに寸法を変えるため、その全部を保存すると1回の
   * ドラッグで数十回の書き込みが走る。保存はこの回数の変化だけを合図に行う。
   */
  readonly sizeCommitCount: number;
}

/** 側パネルの寸法。min はこのパネルが役目を果たす最小、oppositeMin は本文へ必ず残す最小である。 */
export const SIDE_PANEL_LIMITS = { initial: 280, min: 200, oppositeMin: 520 } as const;

/** 下部パネルの寸法。min は表の見出しと 2 行、oppositeMin は本文に 10 行分である。 */
export const BOTTOM_PANEL_LIMITS = { initial: 240, min: 120, oppositeMin: 200 } as const;

export const initialWorkbenchState: WorkbenchState = {
  tabs: [],
  activeTabId: null,
  sideVisible: true,
  sideWidth: SIDE_PANEL_LIMITS.initial,
  sideView: "explorer",
  bottomVisible: true,
  bottomHeight: BOTTOM_PANEL_LIMITS.initial,
  bottomView: "findings",
  drafts: {},
  lastSave: null,
  sizeCommitCount: 0,
};

/** 資産1件のタブの識別子。本文の面と、編集後の本文を引くときに使う。 */
export function sourceTabId(path: string): string {
  return `source:${path}`;
}

/** 資産1件を開くタブ。同じ資産は1枚だけ開く。 */
export function sourceTab(path: string, line: number | null = null): WorkbenchTab {
  const name = path.split("/").pop() ?? path;
  return { id: sourceTabId(path), kind: "source", title: name, path, line };
}

/** 種類ごとに1枚だけ開くタブ。 */
export function singletonTab(kind: Exclude<TabKind, "source">): WorkbenchTab {
  return { id: kind, kind, title: singletonTabTitle(kind), path: null, line: null };
}

export type WorkbenchAction =
  | { type: "OPEN_TAB"; tab: WorkbenchTab }
  | { type: "CLOSE_TAB"; id: string }
  | { type: "ACTIVATE_TAB"; id: string }
  | { type: "STEP_TAB"; step: 1 | -1 }
  | { type: "SET_DRAFT"; id: string; text: string | null }
  | { type: "SAVED"; path: string; at: number }
  | { type: "TOGGLE_SIDE" }
  | { type: "SHOW_SIDE"; view: SideView }
  | { type: "SET_SIDE_WIDTH"; width: number }
  | { type: "TOGGLE_BOTTOM" }
  | { type: "SHOW_BOTTOM"; view: BottomView }
  | { type: "SET_BOTTOM_HEIGHT"; height: number }
  | { type: "COMMIT_SIZE" }
  | { type: "RESTORE_SIZES"; sideWidth?: number; bottomHeight?: number };

/**
 * タブを閉じた後に選ぶタブ。閉じたタブの次を選び、末尾を閉じたときは手前を選ぶ。
 * 1枚も残らなければ null を返す。
 */
/** 1件の編集後の本文を落とした集合を返す。 */
function withoutDraft(
  drafts: Readonly<Record<string, string>>,
  id: string,
): Record<string, string> {
  const next = { ...drafts };
  delete next[id];
  return next;
}

function nextActiveId(
  tabs: readonly WorkbenchTab[],
  closedIndex: number,
  activeId: string | null,
  closedId: string,
): string | null {
  if (activeId !== closedId) {
    return activeId;
  }
  const remaining = tabs.filter((tab) => tab.id !== closedId);
  if (remaining.length === 0) {
    return null;
  }
  return remaining[Math.min(closedIndex, remaining.length - 1)].id;
}

export function workbenchReducer(state: WorkbenchState, action: WorkbenchAction): WorkbenchState {
  switch (action.type) {
    case "OPEN_TAB": {
      const existing = state.tabs.find((tab) => tab.id === action.tab.id);
      if (existing === undefined) {
        return { ...state, tabs: [...state.tabs, action.tab], activeTabId: action.tab.id };
      }
      // 開いてあるタブは開き直さない。行の指定だけを新しい要求で置き換える。
      return {
        ...state,
        tabs: state.tabs.map((tab) =>
          tab.id === action.tab.id ? { ...tab, line: action.tab.line } : tab,
        ),
        activeTabId: action.tab.id,
      };
    }

    case "CLOSE_TAB": {
      const index = state.tabs.findIndex((tab) => tab.id === action.id);
      if (index < 0) {
        return state;
      }
      return {
        ...state,
        tabs: state.tabs.filter((tab) => tab.id !== action.id),
        activeTabId: nextActiveId(state.tabs, index, state.activeTabId, action.id),
        drafts: withoutDraft(state.drafts, action.id),
      };
    }

    case "ACTIVATE_TAB":
      return state.tabs.some((tab) => tab.id === action.id)
        ? { ...state, activeTabId: action.id }
        : state;

    case "STEP_TAB": {
      if (state.tabs.length === 0) {
        return state;
      }
      const current = state.tabs.findIndex((tab) => tab.id === state.activeTabId);
      // 端では反対の端へ回す。選択が無いときは先頭から数える。
      const next = (Math.max(current, 0) + action.step + state.tabs.length) % state.tabs.length;
      return { ...state, activeTabId: state.tabs[next].id };
    }

    case "SET_DRAFT": {
      const current = state.drafts[action.id];
      if (action.text === null) {
        return current === undefined
          ? state
          : { ...state, drafts: withoutDraft(state.drafts, action.id) };
      }
      return current === action.text
        ? state
        : { ...state, drafts: { ...state.drafts, [action.id]: action.text }, lastSave: null };
    }

    case "SAVED":
      return { ...state, lastSave: { path: action.path, at: action.at } };

    case "TOGGLE_SIDE":
      return { ...state, sideVisible: !state.sideVisible };

    case "SHOW_SIDE":
      // 同じ面を選び直したときは畳む(アクティビティバーの押し直しで隠せる)。
      return state.sideVisible && state.sideView === action.view
        ? { ...state, sideVisible: false }
        : { ...state, sideVisible: true, sideView: action.view };

    case "SET_SIDE_WIDTH":
      return { ...state, sideWidth: action.width };

    case "TOGGLE_BOTTOM":
      return { ...state, bottomVisible: !state.bottomVisible };

    case "SHOW_BOTTOM":
      return state.bottomVisible && state.bottomView === action.view
        ? { ...state, bottomVisible: false }
        : { ...state, bottomVisible: true, bottomView: action.view };

    case "SET_BOTTOM_HEIGHT":
      return { ...state, bottomHeight: action.height };

    case "COMMIT_SIZE":
      return { ...state, sizeCommitCount: state.sizeCommitCount + 1 };

    case "RESTORE_SIZES":
      return {
        ...state,
        sideWidth: Math.max(SIDE_PANEL_LIMITS.min, Math.round(action.sideWidth ?? state.sideWidth)),
        bottomHeight: Math.max(
          BOTTOM_PANEL_LIMITS.min,
          Math.round(action.bottomHeight ?? state.bottomHeight),
        ),
      };

    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

const StateContext = createContext<WorkbenchState | null>(null);
const DispatchContext = createContext<Dispatch<WorkbenchAction> | null>(null);

export interface WorkbenchProviderProps {
  children: ReactNode;
  initial?: WorkbenchState;
}

export function WorkbenchProvider({ children, initial }: WorkbenchProviderProps): ReactElement {
  const [state, dispatch] = useReducer(workbenchReducer, initial ?? initialWorkbenchState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export function useWorkbench(): WorkbenchState {
  const state = useContext(StateContext);
  if (state === null) {
    throw new Error("useWorkbench は WorkbenchProvider の内側で使う");
  }
  return state;
}

export function useWorkbenchDispatch(): Dispatch<WorkbenchAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useWorkbenchDispatch は WorkbenchProvider の内側で使う");
  }
  return dispatch;
}

/** 選択中のタブ。1枚も開いていなければ null。 */
export function activeTabOf(state: WorkbenchState): WorkbenchTab | null {
  return state.tabs.find((tab) => tab.id === state.activeTabId) ?? null;
}

/** そのタブの編集後の本文。編集していなければ null。 */
export function draftOf(state: WorkbenchState, id: string): string | null {
  return state.drafts[id] ?? null;
}

/** そのタブが未保存の編集を抱えているか。 */
export function isTabDirty(state: WorkbenchState, id: string): boolean {
  return state.drafts[id] !== undefined;
}
