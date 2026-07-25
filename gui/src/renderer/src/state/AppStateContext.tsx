/**
 * アプリ状態の Context と hooks。useReducer で AppState を保持し、状態(useAppState)と
 * dispatch(useAppDispatch)を分離して提供する。各画面(WF-3 以降)はこの hooks を共有点にして
 * 状態を読み、action をディスパッチする。initialState を上書きできるので、テストや復元で
 * 任意のモード/画面から描画できる。
 */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactElement,
  type ReactNode,
} from "react";
import { appReducer, type Action } from "./appReducer";
import { initialState, type AppState } from "./appState";

const StateContext = createContext<AppState | null>(null);
const DispatchContext = createContext<Dispatch<Action> | null>(null);

export interface AppStateProviderProps {
  children: ReactNode;
  /** 初期状態の上書き。省略時は空状態(initialState)から始める。 */
  initialState?: AppState;
}

export function AppStateProvider({ children, initialState: seed }: AppStateProviderProps): ReactElement {
  const [state, dispatch] = useReducer(appReducer, seed ?? initialState);
  return (
    <StateContext.Provider value={state}>
      <DispatchContext.Provider value={dispatch}>{children}</DispatchContext.Provider>
    </StateContext.Provider>
  );
}

export function useAppState(): AppState {
  const state = useContext(StateContext);
  if (state === null) {
    throw new Error("useAppState は AppStateProvider の内側で使う");
  }
  return state;
}

export function useAppDispatch(): Dispatch<Action> {
  const dispatch = useContext(DispatchContext);
  if (dispatch === null) {
    throw new Error("useAppDispatch は AppStateProvider の内側で使う");
  }
  return dispatch;
}
