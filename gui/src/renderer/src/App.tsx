import type { ReactElement } from "react";
import { Shell } from "./shell/Shell";
import { Screen } from "./shell/Screen";
import { SCREENS } from "./shell/screens";
import { ScreenRouter } from "./screens/ScreenRouter";
import { RUN_STAGES } from "./components/RunningIndicator";
import { AppStateProvider, useAppState, useAppDispatch } from "./state/AppStateContext";
import type { AppState, RunStage } from "./state/appState";
import { deriveStatus } from "./state/status";

/** RUN_STAGES の文言から「第N段 」の接頭辞を外す。 */
function stageLabel(stage: RunStage): string {
  return RUN_STAGES[stage - 1].replace(/^第\d段\s*/u, "");
}

/** 資産フォルダのパス末尾の名前だけを取り出す(区切りは / \ の両方を許す)。 */
function assetFolderName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, "");
  const segments = trimmed.split(/[\\/]/);
  return segments[segments.length - 1] || trimmed;
}

/**
 * タイトルバーの実行中表示へ渡す対象・段の文言。engine の起動は IPC の invoke/handle が1回きりの
 * 応答を返すだけで、main はファイル単位の進捗を持たない。取得できる範囲、すなわち解析対象の
 * 資産フォルダ名と現在の実行段(scan/lint/sql-advise)だけを示し、取得できないファイル単位の
 * 件数は表示しない。
 */
function deriveRunningLabel(state: AppState): string | undefined {
  if (state.mode !== "running") return undefined;
  const stage = stageLabel(state.runStage);
  return state.project.inputDir === null
    ? stage
    : `${assetFolderName(state.project.inputDir)}（${stage}）`;
}

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
      runningLabel={deriveRunningLabel(state)}
      onCancelRun={
        running
          ? () => {
              // 画面の待機状態を解くだけでは engine が走り続け、次の起動と二重に動く。
              void window.cobolInsight.cancelRun();
              dispatch({ type: "CANCEL_RUN" });
            }
          : undefined
      }
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
