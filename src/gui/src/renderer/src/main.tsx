import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./assets/fonts.css";
import "./styles/theme.css";
import "./styles/tokens.project.css";
import "./styles/base.css";
import "./shell/Shell.css";
import "./components/components.css";
import "./sidebar/sidebar.css";
import "./panel/panel.css";
import "./dialogs/dialogs.css";
// 資産のタブ(原本と逐語対訳)と修正案のタブが使う部品のスタイル。
import "./screens/viewer/viewer.css";
import "./screens/diff/diff.css";
// 呼出関係図・レポート・設定の部品は、まだタブへ移していない。
// 移す回まで、その部品のスタイルをここで読み込んでおく。
import "./screens/graph/graph.css";
import "./screens/report/report.css";
import "./screens/settings/settings.css";

const container = document.getElementById("root");
if (container === null) {
  throw new Error("ルート要素 #root が index.html に存在しない");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
