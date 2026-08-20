/**
 * 保存する設定。重大度のしきい値・既定の文字コード・コピー句探索パスは settings.json、無効に
 * したルールは engine も読む rules-config.json が置き場所である。後者を分けるのは、ルールの
 * 有効・無効をファイルからも画面からも同じように扱えるようにするためである。
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import { RULE_CONFIG_VERSION, type RuleConfigFile } from "../../../shared/engine-api";
import type { AppSettings } from "../../../shared/appSettings";
import { SEVERITY_ORDER, type Severity } from "../components/severity";
import { MANUAL_ENCODING_OPTIONS } from "../data/encodings";

export interface SettingsState {
  /**
   * 保存してある設定を読み終えたか。読み終える前に保存すると、起動時の初期値で保存済みの設定を
   * 上書きしてしまうため、保存はこれが true になってから行う。
   */
  readonly loaded: boolean;
  /** 表示する重大度しきい値。これより低い重大度は指摘の一覧に出さない。 */
  readonly severityThreshold: Severity;
  /** 文字コードの検出に失敗した資産で手動指定が無い場合に用いる既定値。 */
  readonly defaultEncoding: string;
  /** コピー句探索パスの順序付き一覧(--copybook-path)。 */
  readonly copybookPaths: readonly string[];
  /** 無効にしたルール ID の集合。engine が読む rules-config.json と同じ内容を保つ。 */
  readonly disabledRules: Readonly<Record<string, boolean>>;
  /** 側パネル・下部パネルの寸法。保存の対象で、復元は workbenchStore へ渡す。 */
  readonly paneSizes: Readonly<Record<string, number>>;
}

export const initialSettingsState: SettingsState = {
  loaded: false,
  severityThreshold: "warning",
  defaultEncoding: "手動: Shift_JIS",
  copybookPaths: [],
  disabledRules: {},
  paneSizes: {},
};

export type SettingsAction =
  | { type: "RESTORE"; settings: AppSettings; disabledRules: readonly string[] }
  | { type: "SET_THRESHOLD"; severity: Severity }
  | { type: "SET_DEFAULT_ENCODING"; value: string }
  | { type: "SET_COPYBOOK_PATHS"; paths: readonly string[] }
  | { type: "TOGGLE_RULE"; id: string }
  | { type: "SET_RULES_ENABLED"; ids: readonly string[]; enabled: boolean }
  | { type: "SET_PANE_SIZES"; sizes: Readonly<Record<string, number>> };

/** 1件の有効・無効を反転した新しい集合を返す。 */
export function toggleRuleId(
  disabled: Readonly<Record<string, boolean>>,
  id: string,
): Record<string, boolean> {
  const next: Record<string, boolean> = { ...disabled };
  if (next[id] === true) {
    delete next[id];
  } else {
    next[id] = true;
  }
  return next;
}

/** 指定した ID 群を一括で有効・無効にした新しい集合を返す。 */
export function setRuleIdsEnabled(
  disabled: Readonly<Record<string, boolean>>,
  ids: readonly string[],
  enabled: boolean,
): Record<string, boolean> {
  const next: Record<string, boolean> = { ...disabled };
  for (const id of ids) {
    if (enabled) {
      delete next[id];
    } else {
      next[id] = true;
    }
  }
  return next;
}

export function settingsReducer(state: SettingsState, action: SettingsAction): SettingsState {
  switch (action.type) {
    case "RESTORE": {
      // 保存側は語彙を検査せず型だけを整えて返す。選択肢の一覧は画面が持つため、知らない
      // 重大度・文字コードはここで捨てて現在の値を残す。
      const threshold = SEVERITY_ORDER.find(
        (value) => value === action.settings.severityThreshold,
      );
      const encoding = MANUAL_ENCODING_OPTIONS.includes(action.settings.defaultEncoding)
        ? action.settings.defaultEncoding
        : state.defaultEncoding;
      return {
        ...state,
        loaded: true,
        severityThreshold: threshold ?? state.severityThreshold,
        defaultEncoding: encoding,
        copybookPaths: [...action.settings.copybookPaths],
        disabledRules: setRuleIdsEnabled({}, action.disabledRules, false),
        paneSizes: { ...action.settings.paneSizes },
      };
    }

    case "SET_THRESHOLD":
      return { ...state, severityThreshold: action.severity };

    case "SET_DEFAULT_ENCODING":
      return { ...state, defaultEncoding: action.value };

    case "SET_COPYBOOK_PATHS":
      return { ...state, copybookPaths: [...action.paths] };

    case "TOGGLE_RULE":
      return { ...state, disabledRules: toggleRuleId(state.disabledRules, action.id) };

    case "SET_RULES_ENABLED":
      return {
        ...state,
        disabledRules: setRuleIdsEnabled(state.disabledRules, action.ids, action.enabled),
      };

    case "SET_PANE_SIZES":
      return { ...state, paneSizes: { ...action.sizes } };

    default: {
      const exhaustive: never = action;
      return exhaustive;
    }
  }
}

/** 保存する設定を組む。寸法は作業面の現在値を呼び手が渡す。 */
export function toAppSettings(
  state: SettingsState,
  paneSizes: Readonly<Record<string, number>>,
): AppSettings {
  return {
    severityThreshold: state.severityThreshold,
    defaultEncoding: state.defaultEncoding,
    copybookPaths: [...state.copybookPaths],
    paneSizes: { ...paneSizes },
  };
}

/** engine が読むルールの設定を組む。 */
export function toRuleConfig(state: SettingsState): RuleConfigFile {
  return {
    version: RULE_CONFIG_VERSION,
    disabledRules: Object.keys(state.disabledRules)
      .filter((id) => state.disabledRules[id] === true)
      .sort(),
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
    throw new Error("useSettings は SettingsProvider の内側で使う");
  }
  return state;
}

export function useSettingsDispatch(): Dispatch<SettingsAction> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useSettingsDispatch は SettingsProvider の内側で使う");
  }
  return dispatch;
}
