import type { ReactElement } from "react";
import { Button } from "./Button";

export interface EmptyStateProps {
  /** 中央上部の記号・アイコン(装飾)。 */
  icon?: string;
  title: string;
  description?: string;
  /** 主アクションのラベル。省略時はボタンを出さない。 */
  actionLabel?: string;
  onAction?: () => void;
  /** ボタン下の補足(文字コード判定の案内など)。 */
  note?: string;
}

/**
 * 空状態プレースホルダ。破線枠の中に見出し・説明・任意の主アクションを置き、
 * 次に取るべき操作へ誘導する。領域として aria-label にタイトルを与える。
 */
export function EmptyState({ icon, title, description, actionLabel, onAction, note }: EmptyStateProps): ReactElement {
  return (
    <div className="ci-empty" role="region" aria-label={title}>
      <div className="ci-empty__box">
        {icon ? (
          <div className="ci-empty__icon" aria-hidden="true">
            {icon}
          </div>
        ) : null}
        <p className="ci-empty__title">{title}</p>
        {description ? <p className="ci-empty__desc">{description}</p> : null}
        {actionLabel ? (
          <Button variant="primary" onClick={onAction}>
            {actionLabel}
          </Button>
        ) : null}
        {note ? <p className="ci-empty__note">{note}</p> : null}
      </div>
    </div>
  );
}
