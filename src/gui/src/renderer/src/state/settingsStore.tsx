/**
 * The persisted settings, as the renderer holds them.
 *
 * Restoring checks the stored values against the vocabulary the renderer knows: a severity or a
 * codepage the interface no longer offers is dropped rather than kept as a value nothing can select.
 * The file format itself is normalised in shared/settings.
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import type { AppSettings } from "../../../shared/settings";
import { emptyAppSettings } from "../../../shared/settings";
import { CODEPAGES } from "../../../shared/codepage";
import { SEVERITIES, type Severity } from "../model/severity";

export interface SettingsState {
  readonly severityThreshold: Severity;
  /** The default codepage, or "" for "let the engine detect it". */
  readonly defaultEncoding: string;
  readonly copybookPaths: readonly string[];
  /** Where `fix apply` writes the corrected sources. Empty means the engine's own default. */
  readonly fixOutDir: string;
  readonly lastInputDir: string;
  readonly paneSizes: Readonly<Record<string, number>>;
  /** Whether the stored settings have been read yet. Saving before that would erase them. */
  readonly restored: boolean;
}

export const initialSettingsState: SettingsState = {
  severityThreshold: "warning",
  defaultEncoding: "",
  copybookPaths: [],
  fixOutDir: "",
  lastInputDir: "",
  paneSizes: {},
  restored: false,
};

export type SettingsAction =
  | { type: "RESTORE"; settings: AppSettings }
  | { type: "SET_THRESHOLD"; threshold: Severity }
  | { type: "SET_ENCODING"; encoding: string }
  | { type: "SET_COPYBOOK_PATHS"; paths: readonly string[] }
  | { type: "SET_FIX_OUT_DIR"; dir: string }
  | { type: "SET_LAST_INPUT_DIR"; dir: string }
  | { type: "SET_PANE_SIZE"; key: string; size: number };

function isSeverity(value: string): value is Severity {
  return (SEVERITIES as readonly string[]).includes(value);
}

function isKnownEncoding(value: string): boolean {
  return CODEPAGES.some((codepage) => codepage.value === value);
}

export function settingsReducer(state: SettingsState, action: SettingsAction): SettingsState {
  switch (action.type) {
    case "RESTORE":
      return {
        severityThreshold: isSeverity(action.settings.severityThreshold)
          ? action.settings.severityThreshold
          : state.severityThreshold,
        defaultEncoding: isKnownEncoding(action.settings.defaultEncoding)
          ? action.settings.defaultEncoding
          : "",
        copybookPaths: [...action.settings.copybookPaths],
        fixOutDir: action.settings.fixOutDir,
        lastInputDir: action.settings.lastInputDir,
        paneSizes: { ...action.settings.paneSizes },
        restored: true,
      };

    case "SET_THRESHOLD":
      return { ...state, severityThreshold: action.threshold };

    case "SET_ENCODING":
      return { ...state, defaultEncoding: action.encoding };

    case "SET_COPYBOOK_PATHS":
      return { ...state, copybookPaths: [...action.paths] };

    case "SET_FIX_OUT_DIR":
      return { ...state, fixOutDir: action.dir };

    case "SET_LAST_INPUT_DIR":
      return { ...state, lastInputDir: action.dir };

    case "SET_PANE_SIZE":
      return { ...state, paneSizes: { ...state.paneSizes, [action.key]: action.size } };

    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

/** The state in the shape the settings file stores. */
export function toAppSettings(state: SettingsState): AppSettings {
  return {
    ...emptyAppSettings(),
    severityThreshold: state.severityThreshold,
    defaultEncoding: state.defaultEncoding,
    copybookPaths: [...state.copybookPaths],
    fixOutDir: state.fixOutDir,
    lastInputDir: state.lastInputDir,
    paneSizes: { ...state.paneSizes },
  };
}

const StateContext = createContext<SettingsState | null>(null);
const DispatchContext = createContext<Dispatch<SettingsAction> | null>(null);

export interface SettingsProviderProps {
  children: ReactNode;
  initial?: SettingsState;
}

export function SettingsProvider({ children, initial }: SettingsProviderProps): ReactElement {
  const [state, dispatch] = useReducer(settingsReducer, initial ?? initialSettingsState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export function useSettings(): SettingsState {
  const state = useContext(StateContext);
  if (state === null) {
    throw new Error("useSettings must be used inside SettingsProvider");
  }
  return state;
}

export function useSettingsDispatch(): Dispatch<SettingsAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useSettingsDispatch must be used inside SettingsProvider");
  }
  return dispatch;
}
