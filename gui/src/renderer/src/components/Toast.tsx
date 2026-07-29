import { useEffect, type ReactElement } from "react";

export interface ToastProps {
  /** 表示するメッセージ。null/空のときは何も描画しない。 */
  message: string | null;
  /** 自動消滅時に呼ぶ。メッセージ状態のクリアは呼び出し側の責務。 */
  onDismiss?: () => void;
  /** 自動消滅までの時間(ミリ秒)。既定 2600 は画面デザインのトースト表示時間と同じ値である。 */
  durationMs?: number;
}

/**
 * トースト通知。pop で出現し、durationMs 経過後に自動で消える。
 * aria-live=polite で読み上げ、視覚に依存せず内容を伝える。
 */
export function Toast({ message, onDismiss, durationMs = 2600 }: ToastProps): ReactElement | null {
  useEffect(() => {
    if (message === null || message === "" || onDismiss === undefined) return;
    const timer = setTimeout(onDismiss, durationMs);
    return () => clearTimeout(timer);
  }, [message, durationMs, onDismiss]);

  if (message === null || message === "") return null;
  return (
    <div className="ci-toast" role="status" aria-live="polite">
      <span className="ci-toast__message">{message}</span>
      {onDismiss ? (
        <button
          type="button"
          className="ci-toast__close"
          aria-label="通知を閉じる"
          onClick={onDismiss}
        >
          ×
        </button>
      ) : null}
    </div>
  );
}
