/**
 * The rule configuration file as the screens hold it, plus the custom-rule editor's working copy.
 *
 * The file is shared: the rules view toggles built-in rules in it while the custom-rule editor
 * rewrites its `custom` array, and both write the same file. The editor's two panes — the form and
 * the raw JSON — are two views of one value, so the text and the definitions are kept in step here
 * rather than in either pane.
 *
 * What is in effect is not stored: that comes from the engine's catalogue in projectStore.
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import type { RulesValidation } from "../../../shared/ipc";
import type { CustomRule, RulesFile } from "../../../shared/rulesFile";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { parse, serialise } from "../model/customRules";

/** Which pane of the custom-rule editor is showing. */
export type CustomRulePane = "form" | "raw";

export interface RulesState {
  /** The file as it was last read or written. */
  readonly file: RulesFile;
  /** The custom rules being edited. */
  readonly draft: readonly CustomRule[];
  /** The raw pane's text, always the serialisation of `draft` unless the text does not parse. */
  readonly raw: string;
  readonly pane: CustomRulePane;
  /** Why the raw text does not parse; null when it does. */
  readonly rawError: string | null;
  /** What the engine made of the candidate file, or null when it has not been asked. */
  readonly validation: RulesValidation | null;
  /**
   * Whether the custom rules on screen differ from the ones in the file. Kept on the state rather
   * than recomputed on every read, since `EditorTabs` calls {@link isCustomDirty} once per tab on
   * every render.
   */
  readonly dirty: boolean;
}

export const initialRulesState: RulesState = {
  file: emptyRulesFile(),
  draft: [],
  raw: "[]",
  pane: "form",
  rawError: null,
  validation: null,
  dirty: false,
};

/** Whether the draft differs from the file's own custom rules, given a possibly unparsed raw pane. */
function computeDirty(
  file: RulesFile,
  draft: readonly CustomRule[],
  rawError: string | null,
): boolean {
  return rawError !== null || serialise(draft) !== serialise(file.custom);
}

export type RulesAction =
  | { type: "LOAD"; file: RulesFile }
  | { type: "SET_FILE"; file: RulesFile }
  | { type: "SET_DRAFT"; draft: readonly CustomRule[] }
  | { type: "SET_RAW"; raw: string }
  | { type: "SET_PANE"; pane: CustomRulePane }
  | { type: "SET_VALIDATION"; validation: RulesValidation | null };

export function rulesReducer(state: RulesState, action: RulesAction): RulesState {
  switch (action.type) {
    case "LOAD":
      return {
        ...state,
        file: action.file,
        draft: action.file.custom,
        raw: serialise(action.file.custom),
        rawError: null,
        validation: null,
        dirty: false,
      };

    case "SET_FILE":
      return { ...state, file: action.file, dirty: computeDirty(action.file, state.draft, state.rawError) };

    case "SET_DRAFT":
      return {
        ...state,
        draft: action.draft,
        raw: serialise(action.draft),
        rawError: null,
        validation: null,
        dirty: computeDirty(state.file, action.draft, null),
      };

    case "SET_RAW": {
      const parsed = parse(action.raw);
      // Text that does not parse leaves the definitions as they were, so the form still has a value
      // to render and nothing is lost while the JSON is halfway through being typed.
      const draft = parsed.error === null ? parsed.rules : state.draft;
      return {
        ...state,
        raw: action.raw,
        draft,
        rawError: parsed.error,
        validation: null,
        dirty: computeDirty(state.file, draft, parsed.error),
      };
    }

    case "SET_PANE":
      // The form cannot show text it could not read, and switching would discard the edit.
      return action.pane === "form" && state.rawError !== null
        ? state
        : { ...state, pane: action.pane };

    case "SET_VALIDATION":
      return { ...state, validation: action.validation };

    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

/** Whether the custom rules on screen differ from the ones in the file. */
export function isCustomDirty(state: RulesState): boolean {
  return state.dirty;
}

const StateContext = createContext<RulesState | null>(null);
const DispatchContext = createContext<Dispatch<RulesAction> | null>(null);

export interface RulesProviderProps {
  children: ReactNode;
  /** Overrides the initial state, so a test can render from any state. */
  initial?: RulesState;
}

export function RulesProvider({ children, initial }: RulesProviderProps): ReactElement {
  const [state, dispatch] = useReducer(rulesReducer, initial ?? initialRulesState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export function useRulesState(): RulesState {
  const state = useContext(StateContext);
  if (state === null) {
    throw new Error("useRulesState must be used inside RulesProvider");
  }
  return state;
}

export function useRulesDispatch(): Dispatch<RulesAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useRulesDispatch must be used inside RulesProvider");
  }
  return dispatch;
}
