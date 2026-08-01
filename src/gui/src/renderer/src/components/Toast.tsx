import { useEffect, useRef, type ReactElement } from "react";

export interface ToastProps {
  /** 表示するメッセージ。null/空のときは帯を見せない。 */
  message: string | null;
  /** 自動消滅時に呼ぶ。メッセージ状態のクリアは呼び出し側の責務。 */
  onDismiss?: () => void;
  /** 自動消滅までの時間(ミリ秒)。既定 2600 は画面デザインのトースト表示時間と同じ値である。 */
  durationMs?: number;
}

/**
 * トースト通知。pop で出現し、durationMs 経過後に自動で消える。
 *
 * 読み上げの領域(role=status)はメッセージの有無にかかわらず常に DOM へ置き、本文だけを
 * 差し替える。領域そのものを本文と同時に挿入すると読み上げない支援技術があり、解析完了を
 * 伝える経路はこのトーストだけであるため、取りこぼすと完了が伝わらない。
 * 閉じるボタンは領域の外に置き、読み上げへ「通知を閉じる」が混ざらないようにする。
 */
export function Toast({ message, onDismiss, durationMs = 2600 }: ToastProps): ReactElement {
  const shown = message !== null && message !== "";

  // 呼び出し側は毎描画で新しい関数を渡す(インラインのディスパッチ)。関数の同一性を計時の
  // 依存に入れると、親が再描画するたびタイマーを張り直し、描画が続くあいだ自動で消えなくなる。
  const dismissRef = useRef(onDismiss);
  useEffect(() => {
    dismissRef.current = onDismiss;
  }, [onDismiss]);

  useEffect(() => {
    if (!shown) return;
    const timer = setTimeout(() => dismissRef.current?.(), durationMs);
    return () => clearTimeout(timer);
  }, [shown, message, durationMs]);

  return (
    <div className={shown ? "ci-toast" : "ci-toast ci-toast--empty"}>
      <span className="ci-toast__message" role="status" aria-live="polite">
        {shown ? message : ""}
      </span>
      {shown && onDismiss ? (
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
