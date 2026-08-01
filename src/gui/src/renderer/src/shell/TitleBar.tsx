import type { ReactElement } from "react";
import { Button } from "../components/Button";

export interface TitleBarProps {
  appName?: string;
  /** 解析実行中か。実行中はスピナーと対象・キャンセルを表示する。 */
  isRunning?: boolean;
  /** 実行中の対象表示(例「SYK006.cbl（13 / 19 ファイル）」)。 */
  runningLabel?: string;
  onCancelRun?: () => void;
}

/**
 * 最上部 44px のタイトルバー。左にアプリ名、実行中は右に回転スピナー・対象・キャンセルを出す。
 * アプリ名は文書全体で唯一の h1 とし、各画面のタイトルはその下の h2 に置く。
 */
export function TitleBar({
  appName = "COBOL Insight",
  isRunning = false,
  runningLabel,
  onCancelRun,
}: TitleBarProps): ReactElement {
  return (
    <header className="ci-titlebar">
      <h1 className="ci-titlebar__brand">{appName}</h1>
      <div className="ci-titlebar__spacer" />
      {isRunning ? (
        <div className="ci-titlebar__running" role="status" aria-live="polite">
          <span className="ci-titlebar__spinner" aria-hidden="true" />
          <span className="ci-titlebar__running-text">
            解析実行中{runningLabel ? ` ― ${runningLabel}` : ""}
          </span>
          {onCancelRun ? (
            <Button onClick={onCancelRun} aria-label="解析をキャンセル">
              キャンセル
            </Button>
          ) : null}
        </div>
      ) : null}
    </header>
  );
}
