import type { ReactElement } from "react";

export interface StatusBarProps {
  /** 左側の状態文(最終解析時刻・実行状況など)。 */
  left: string;
  /** 右側の件数集計(資産数・指摘数・ルール数・版数など)。 */
  counts: string;
}

/**
 * 最下部 24px の青いステータスバー。左に状態、右に件数集計を出す。
 */
export function StatusBar({ left, counts }: StatusBarProps): ReactElement {
  return (
    <footer className="ci-statusbar" aria-label="ステータス">
      <span className="ci-statusbar__left">{left}</span>
      <span className="ci-statusbar__spacer" />
      <span className="ci-statusbar__counts">{counts}</span>
    </footer>
  );
}
