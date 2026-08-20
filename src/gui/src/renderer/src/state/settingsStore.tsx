/**
 * 保存する設定。重大度のしきい値・既定の文字コード・コピー句探索パスを settings.json へ保存する。
 *
 * ルールの有効・無効はここに持たない。engine が rules-config.json を読んで各ルールの enabled を
 * 決めるため、画面が控えを持つと engine の答えと食い違いうる。ルールのタブが設定ファイルを直に
 * 書き、書いたあとで一覧を engine から取り直す。
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
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
  /** 側パネル・下部パネルの寸法。保存の対象で、復元は workbenchStore へ渡す。 */
  readonly paneSizes: Readonly<Record<string, number>>;
}

export const initialSettingsState: SettingsState = {
  loaded: false,
  severityThreshold: "warning",
  defaultEncoding: "手動: Shift_JIS",
  copybookPaths: [],
  paneSizes: {},
};

export type SettingsAction =
  | { type: "RESTORE"; settings: AppSettings }
  | { type: "SET_THRESHOLD"; severity: Severity }
  | { type: "SET_DEFAULT_ENCODING"; value: string }
  | { type: "SET_COPYBOOK_PATHS"; paths: readonly string[] }
  | { type: "SET_PANE_SIZES"; sizes: Readonly<Record<string, number>> };

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
        paneSizes: { ...action.settings.paneSizes },
      };
    }

    case "SET_THRESHOLD":
      return { ...state, severityThreshold: action.severity };

    case "SET_DEFAULT_ENCODING":
      return { ...state, defaultEncoding: action.value };

    case "SET_COPYBOOK_PATHS":
      return { ...state, copybookPaths: [...action.paths] };

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
