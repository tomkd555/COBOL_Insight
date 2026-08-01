/**
 * 画面の定義。タブと画面ルーティングの単一の正とする。
 */

export type ScreenId =
  | "explorer"
  | "import"
  | "graph"
  | "findings"
  | "viewer"
  | "sql"
  | "diff"
  | "report"
  | "settings";

export interface ScreenTab {
  readonly id: ScreenId;
  readonly label: string;
}

export const SCREENS: readonly ScreenTab[] = [
  { id: "explorer", label: "資産エクスプローラー" },
  { id: "import", label: "端末取込" },
  { id: "graph", label: "呼出関係図" },
  { id: "findings", label: "指摘一覧" },
  { id: "viewer", label: "ソースビューア" },
  { id: "sql", label: "SQL助言" },
  { id: "diff", label: "修正案の差分" },
  { id: "report", label: "レポート出力" },
  { id: "settings", label: "設定" },
];
