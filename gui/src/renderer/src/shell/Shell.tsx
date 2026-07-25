import type { ReactElement, ReactNode } from "react";
import type { ScreenId, ScreenTab } from "./screens";
import { TitleBar } from "./TitleBar";
import { TabBar } from "./TabBar";
import { ProgressBar } from "./ProgressBar";
import { StatusBar } from "./StatusBar";
import { Toast } from "../components/Toast";

export interface ShellProps {
  tabs: readonly ScreenTab[];
  activeScreen: ScreenId;
  onSelectScreen: (id: ScreenId) => void;

  isRunning?: boolean;
  runningLabel?: string;
  onCancelRun?: () => void;

  statusLeft: string;
  statusCounts: string;

  toast?: string | null;
  onToastDismiss?: () => void;

  /** 中央コンテンツ領域に重ねる画面オーバーレイ(通常はアクティブな Screen 1つ)。 */
  children: ReactNode;
}

/**
 * アプリの固定シェル。上から タイトルバー / タブバー / 実行中進捗バー / 中央コンテンツ領域 /
 * ステータスバー の5段で構成する。中央領域は position:relative で、各画面を絶対配置の
 * オーバーレイとして重ねる。トーストはシェル全体に対する fixed 要素として最後に置く。
 */
export function Shell({
  tabs,
  activeScreen,
  onSelectScreen,
  isRunning = false,
  runningLabel,
  onCancelRun,
  statusLeft,
  statusCounts,
  toast = null,
  onToastDismiss,
  children,
}: ShellProps): ReactElement {
  return (
    <div className="ci-shell">
      <TitleBar isRunning={isRunning} runningLabel={runningLabel} onCancelRun={onCancelRun} />
      <TabBar tabs={tabs} activeId={activeScreen} onSelect={onSelectScreen} />
      {isRunning ? <ProgressBar /> : null}
      <main className="ci-shell__content">{children}</main>
      <StatusBar left={statusLeft} counts={statusCounts} />
      <Toast message={toast} onDismiss={onToastDismiss} />
    </div>
  );
}
