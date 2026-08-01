/**
 * 各画面のメタ情報。画面ラベルと実行中の見出しを画面ごとに持つ。
 * 空状態の文言はここに置かない。状況ごとに文言を変えるため、各画面が自前の EmptyState を組む。
 * 画面ラベルはソースビューアがジャンプ元の提示に用いる。
 */

import type { ScreenId } from "../shell/screens";

export interface ScreenMeta {
  readonly id: ScreenId;
  /** 画面ラベル(タブ名と一致)。 */
  readonly label: string;
  /** 実行中の見出し。 */
  readonly runningTitle: string;
}

export const SCREEN_META: Record<ScreenId, ScreenMeta> = {
  explorer: {
    id: "explorer",
    label: "資産エクスプローラー",
    runningTitle: "資産を解析しています…",
  },
  import: {
    id: "import",
    label: "端末取込",
    runningTitle: "資産を解析しています…",
  },
  graph: {
    id: "graph",
    label: "呼出関係図",
    runningTitle: "呼出関係を構築しています…",
  },
  findings: {
    id: "findings",
    label: "指摘一覧",
    runningTitle: "指摘を検出しています…",
  },
  viewer: {
    id: "viewer",
    label: "ソースビューア",
    runningTitle: "ソースを準備しています…",
  },
  sql: {
    id: "sql",
    label: "SQL助言",
    runningTitle: "SQL を解析しています…",
  },
  diff: {
    id: "diff",
    label: "修正案の差分",
    runningTitle: "修正案を生成しています…",
  },
  report: {
    id: "report",
    label: "レポート出力",
    runningTitle: "レポートを準備しています…",
  },
  settings: {
    id: "settings",
    label: "設定",
    runningTitle: "設定を読み込んでいます…",
  },
};
