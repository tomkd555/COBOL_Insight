import type { ReactElement } from "react";
import { Shell } from "./shell/Shell";
import { Screen } from "./shell/Screen";
import { SCREENS } from "./shell/screens";
import { ScreenRouter } from "./screens/ScreenRouter";
import { AppStateProvider, useAppState, useAppDispatch } from "./state/AppStateContext";
import { deriveStatus } from "./state/status";

/**
 * シェル本体。状態(mode/screen)を読み、固定シェル(5段)へ渡す。タブ選択は NAV、実行中の
 * キャンセルは CANCEL_RUN、トーストの消滅は DISMISS_TOAST をディスパッチする。中央領域には
 * state.screen に対応する画面を、絶対配置オーバーレイとして重ねる。
 */
export function AppShell(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const status = deriveStatus(state);
  const running = state.mode === "running";

  return (
    <Shell
      tabs={SCREENS}
      activeScreen={state.screen}
      onSelectScreen={(id) => dispatch({ type: "NAV", screen: id })}
      isRunning={running}
      onCancelRun={running ? () => dispatch({ type: "CANCEL_RUN" }) : undefined}
      statusLeft={status.left}
      statusCounts={status.counts}
      toast={state.toastMsg}
      onToastDismiss={() => dispatch({ type: "DISMISS_TOAST" })}
    >
      <Screen id={state.screen}>
        <ScreenRouter screen={state.screen} />
      </Screen>
    </Shell>
  );
}

/**
 * アプリのルート。状態 Provider でシェルを包む。
 */
export function App(): ReactElement {
  return (
    <AppStateProvider>
      <AppShell />
    </AppStateProvider>
  );
}
