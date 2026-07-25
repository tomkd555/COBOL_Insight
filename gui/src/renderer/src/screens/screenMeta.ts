/**
 * 各画面のプレースホルダ用メタ情報。空状態の誘導文と実行中の見出しを画面ごとに持つ。
 * WF-3 以降で各画面の本体が実装されると、results 本体はそれぞれのコンポーネントへ置き換わる。
 */

import type { ScreenId } from "../shell/screens";

export interface ScreenMeta {
  readonly id: ScreenId;
  /** 画面ラベル(タブ名と一致)。 */
  readonly label: string;
  /** 空状態のアイコン(装飾)。 */
  readonly emptyIcon?: string;
  /** 空状態の見出し。 */
  readonly emptyTitle: string;
  /** 空状態の説明。 */
  readonly emptyDesc?: string;
  /** 空状態の主アクションのラベル(資産エクスプローラーのみ)。 */
  readonly emptyAction?: string;
  /** 空状態の補足。 */
  readonly emptyNote?: string;
  /** 実行中の見出し。 */
  readonly runningTitle: string;
}

export const SCREEN_META: Record<ScreenId, ScreenMeta> = {
  explorer: {
    id: "explorer",
    label: "資産エクスプローラー",
    emptyIcon: "＋",
    emptyTitle: "資産がまだインポートされていません",
    emptyDesc: "フォルダまたはファイルを取り込み、文字コードを確認してから解析を実行します。",
    emptyAction: "フォルダ／ファイルをインポート",
    emptyNote: "文字コードは自動判定（Shift_JIS / UTF-8）または推定（EBCDIC CP930/939）。",
    runningTitle: "資産を解析しています…",
  },
  graph: {
    id: "graph",
    label: "呼出関係図",
    emptyTitle: "呼出関係図",
    emptyDesc: "解析を実行すると、ジョブ・プログラム・データセットの呼出関係をここに表示する。",
    runningTitle: "呼出関係を構築しています…",
  },
  findings: {
    id: "findings",
    label: "指摘一覧",
    emptyTitle: "指摘一覧",
    emptyDesc: "解析を実行すると、検出した指摘をここに一覧表示する。",
    runningTitle: "バグ検出を実行しています…",
  },
  viewer: {
    id: "viewer",
    label: "ソースビューア",
    emptyTitle: "ソースビューア",
    emptyDesc: "資産または指摘を選ぶと、ソースと逐語対訳をここに表示する。",
    runningTitle: "ソースを準備しています…",
  },
  sql: {
    id: "sql",
    label: "SQL助言",
    emptyTitle: "SQL助言",
    emptyDesc: "解析を実行すると、SQL 最適化の助言をここに一覧表示する。",
    runningTitle: "SQL を解析しています…",
  },
  diff: {
    id: "diff",
    label: "diff",
    emptyTitle: "修正案 diff",
    emptyDesc: "解析を実行すると、生成した修正案の差分をここに表示する。",
    runningTitle: "修正案を生成しています…",
  },
  report: {
    id: "report",
    label: "レポート出力",
    emptyTitle: "レポート出力",
    emptyDesc: "解析を実行すると、レポートのプレビューと書き出しをここで行う。",
    runningTitle: "レポートを準備しています…",
  },
  settings: {
    id: "settings",
    label: "設定",
    emptyTitle: "設定",
    emptyDesc: "既定文字コード・コピー句検索パス・検出ルール・重大度しきい値を設定する。",
    runningTitle: "設定を読み込んでいます…",
  },
};
