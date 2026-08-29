/**
 * The workbench state: which tabs are open, which one is active, and the visibility and size of the
 * side bar and the panel.
 *
 * Tab titles have their single source here; no screen keeps a label table of its own.
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import { text } from "../text";

/** Tab kinds. `source`, `fix` and `transpile` bind to one asset; the rest open at most one tab each. */
export type TabKind = "source" | "graph" | "rules" | "report" | "settings" | "fix" | "transpile";

/** What the activity bar can put in the side bar. */
export type SideView = "explorer" | "search" | "rules" | "problems";

/** What the panel can show. */
export type PanelView = "problems" | "output";

export interface WorkbenchTab {
  /** A source tab is "source:<relative path>"; every other kind is its own name. */
  readonly id: string;
  readonly kind: TabKind;
  /** The tab's caption. For a source tab this is the file name. */
  readonly title: string;
  /** The relative path a source tab shows; null for every other kind. */
  readonly path: string | null;
  /** The line to reveal on opening, or null. */
  readonly line: number | null;
}

export interface WorkbenchState {
  readonly tabs: readonly WorkbenchTab[];
  readonly activeTabId: string | null;
  readonly sideVisible: boolean;
  readonly sideWidth: number;
  readonly sideView: SideView;
  readonly panelVisible: boolean;
  readonly panelHeight: number;
  readonly panelView: PanelView;
  /**
   * Unsaved edited text, keyed by tab id. An inactive tab is not rendered, so the editor itself
   * cannot hold the text without losing it on every switch. The presence of a key is the dirty flag.
   */
  readonly drafts: Readonly<Record<string, string>>;
  /**
   * How many times a resize has finished. Dragging changes the size on every pixel, and persisting
   * all of that would mean dozens of writes per drag; the settings are saved on changes to this
   * count alone.
   */
  readonly sizeCommitCount: number;
}

/** Side bar sizing. `min` keeps the panel usable; `oppositeMin` is what the editor must keep. */
export const SIDE_LIMITS = { initial: 280, min: 200, oppositeMin: 520 } as const;

/** Panel sizing. `min` is a header plus two rows; `oppositeMin` is ten lines of editor. */
export const PANEL_LIMITS = { initial: 220, min: 120, oppositeMin: 200 } as const;

/** Pane-size keys as stored in the settings. */
export const PANE_SIZE_KEYS = { side: "sideWidth", panel: "panelHeight" } as const;

export const initialWorkbenchState: WorkbenchState = {
  tabs: [],
  activeTabId: null,
  sideVisible: true,
  sideWidth: SIDE_LIMITS.initial,
  sideView: "explorer",
  panelVisible: true,
  panelHeight: PANEL_LIMITS.initial,
  panelView: "problems",
  drafts: {},
  sizeCommitCount: 0,
};

/** The id of an asset's tab, used both by the editor and by the draft lookup. */
export function sourceTabId(path: string): string {
  return `source:${path}`;
}

/** A tab for one asset. The same asset never opens twice. */
export function sourceTab(path: string, line: number | null = null): WorkbenchTab {
  const name = path.split("/").pop() ?? path;
  return { id: sourceTabId(path), kind: "source", title: name, path, line };
}

/** The tab id of the custom-rule editor. No rule can be called this: custom ids start with U. */
export const CUSTOM_RULES_TAB_ID = "rules:custom";

/** The description of one rule, as the engine wrote it. */
export function ruleTab(ruleId: string): WorkbenchTab {
  return { id: `rules:${ruleId}`, kind: "rules", title: ruleId, path: ruleId, line: null };
}

/** The custom-rule editor. */
export function customRulesTab(title: string): WorkbenchTab {
  return { id: CUSTOM_RULES_TAB_ID, kind: "rules", title, path: null, line: null };
}

/**
 * The call-graph editor. `path` carries the label of the node to centre on — the program a problems
 * row named, say — or null to open on the graph's own roots.
 */
export function graphTab(title: string, focusLabel: string | null = null): WorkbenchTab {
  return { id: "graph", kind: "graph", title, path: focusLabel, line: null };
}

/** The report editor. */
export function reportTab(title: string): WorkbenchTab {
  return { id: "report", kind: "report", title, path: null, line: null };
}

/** The settings editor. */
export function settingsTab(title: string): WorkbenchTab {
  return { id: "settings", kind: "settings", title, path: null, line: null };
}

/** The id of an asset's fix diff. It is its own tab, so the source stays open beside it. */
export function fixTabId(path: string): string {
  return `fix:${path}`;
}

/** A tab holding the fix proposal for one asset, against the original. */
export function fixTab(path: string): WorkbenchTab {
  const name = path.split("/").pop() ?? path;
  return { id: fixTabId(path), kind: "fix", title: `${name}（${text.fixView.diff}）`, path, line: null };
}

/** The id of an asset's translation. Its own tab, so the COBOL source stays open beside it. */
export function transpileTabId(path: string): string {
  return `transpile:${path}`;
}

/** A tab holding the generated Python or Java for one asset, beside the COBOL. */
export function transpileTab(path: string): WorkbenchTab {
  const name = path.split("/").pop() ?? path;
  return {
    id: transpileTabId(path),
    kind: "transpile",
    title: `${name}（${text.transpileView.tab}）`,
    path,
    line: null,
  };
}

export type WorkbenchAction =
  | { type: "OPEN_TAB"; tab: WorkbenchTab }
  | { type: "CLOSE_TAB"; id: string }
  | { type: "ACTIVATE_TAB"; id: string }
  | { type: "STEP_TAB"; step: 1 | -1 }
  | { type: "SET_DRAFT"; id: string; draft: string | null }
  | { type: "TOGGLE_SIDE" }
  | { type: "SHOW_SIDE"; view: SideView }
  | { type: "SET_SIDE_WIDTH"; width: number }
  | { type: "TOGGLE_PANEL" }
  | { type: "SHOW_PANEL"; view: PanelView }
  | { type: "SET_PANEL_HEIGHT"; height: number }
  | { type: "COMMIT_SIZE" }
  | { type: "RESTORE_SIZES"; sideWidth?: number; panelHeight?: number };

/** Returns the drafts without the given tab's entry. */
function withoutDraft(
  drafts: Readonly<Record<string, string>>,
  id: string,
): Record<string, string> {
  const next = { ...drafts };
  delete next[id];
  return next;
}

/**
 * Which tab to select after one is closed: the one that follows it, or the one before it when the
 * last tab was closed. Null when nothing is left.
 */
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
      // An open tab is not opened again; only what the request points at is replaced — the line for
      // a source tab, and the node to centre on for the call graph.
      return {
        ...state,
        tabs: state.tabs.map((tab) =>
          tab.id === action.tab.id ? { ...tab, path: action.tab.path, line: action.tab.line } : tab,
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
      // Wrap around at either end; with nothing selected, count from the first tab.
      const next = (Math.max(current, 0) + action.step + state.tabs.length) % state.tabs.length;
      return { ...state, activeTabId: state.tabs[next].id };
    }

    case "SET_DRAFT": {
      const current = state.drafts[action.id];
      if (action.draft === null) {
        return current === undefined
          ? state
          : { ...state, drafts: withoutDraft(state.drafts, action.id) };
      }
      return current === action.draft
        ? state
        : { ...state, drafts: { ...state.drafts, [action.id]: action.draft } };
    }

    case "TOGGLE_SIDE":
      return { ...state, sideVisible: !state.sideVisible };

    case "SHOW_SIDE":
      // Choosing the current view again collapses the side bar, as the activity bar does.
      return state.sideVisible && state.sideView === action.view
        ? { ...state, sideVisible: false }
        : { ...state, sideVisible: true, sideView: action.view };

    case "SET_SIDE_WIDTH":
      return { ...state, sideWidth: action.width };

    case "TOGGLE_PANEL":
      return { ...state, panelVisible: !state.panelVisible };

    case "SHOW_PANEL":
      return state.panelVisible && state.panelView === action.view
        ? { ...state, panelVisible: false }
        : { ...state, panelVisible: true, panelView: action.view };

    case "SET_PANEL_HEIGHT":
      return { ...state, panelHeight: action.height };

    case "COMMIT_SIZE":
      return { ...state, sizeCommitCount: state.sizeCommitCount + 1 };

    case "RESTORE_SIZES":
      return {
        ...state,
        sideWidth: Math.max(SIDE_LIMITS.min, Math.round(action.sideWidth ?? state.sideWidth)),
        panelHeight: Math.max(PANEL_LIMITS.min, Math.round(action.panelHeight ?? state.panelHeight)),
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
  /** Overrides the initial state, so a test can render from any state. */
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
    throw new Error("useWorkbench must be used inside WorkbenchProvider");
  }
  return state;
}

export function useWorkbenchDispatch(): Dispatch<WorkbenchAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useWorkbenchDispatch must be used inside WorkbenchProvider");
  }
  return dispatch;
}

/** The active tab, or null when nothing is open. */
export function activeTabOf(state: WorkbenchState): WorkbenchTab | null {
  return state.tabs.find((tab) => tab.id === state.activeTabId) ?? null;
}

/** That tab's edited text, or null when it has not been edited. */
export function draftOf(state: WorkbenchState, id: string): string | null {
  return state.drafts[id] ?? null;
}

/** Whether the tab holds unsaved edits. */
export function isTabDirty(state: WorkbenchState, id: string): boolean {
  return state.drafts[id] !== undefined;
}
