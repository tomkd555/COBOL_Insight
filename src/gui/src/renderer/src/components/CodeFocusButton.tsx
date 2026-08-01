import type { ReactElement } from "react";
import { Button } from "./Button";

export interface CodeFocusButtonProps {
  /** コードを最大化しているか。 */
  active: boolean;
  /** 最大化の切替。 */
  onToggle: () => void;
  /**
   * 畳む領域の呼び名(「一覧」「逐語対訳」など)。戻す操作の文言に使い、何が戻るかを示す。
   */
  target: string;
}

/**
 * コードの最大化を切り替えるボタン。Ctrl+B と同じ操作を、キーを知らない利用者にも見える形で置く。
 *
 * 置き場所は畳む領域の外に限る。畳む領域の中に置くと、最大化した時点でボタンごと消えて戻せなくなる。
 */
export function CodeFocusButton({ active, onToggle, target }: CodeFocusButtonProps): ReactElement {
  return (
    <Button
      className="ci-code-focus"
      aria-pressed={active}
      title="Ctrl+B"
      onClick={onToggle}
    >
      {active ? `${target}を戻す` : "コードを最大化"}
    </Button>
  );
}
