/**
 * 8画面の正本。design/COBOL Insight.dc.html の navItems(explorer/graph/findings/
 * viewer/sql/diff/report/settings)を移植し、タブと画面ルーティングの単一の正とする。
 */

export type ScreenId =
  | "explorer"
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
  { id: "graph", label: "呼出関係図" },
  { id: "findings", label: "指摘一覧" },
  { id: "viewer", label: "ソースビューア" },
  { id: "sql", label: "SQL助言" },
  { id: "diff", label: "diff" },
  { id: "report", label: "レポート出力" },
  { id: "settings", label: "設定" },
];
