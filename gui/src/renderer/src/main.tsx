import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./assets/fonts.css";
import "./styles/tokens.css";
import "./styles/base.css";
import "./shell/Shell.css";
import "./components/components.css";
import "./screens/explorer/explorer.css";
import "./screens/graph/graph.css";
import "./screens/findings/findings.css";
import "./screens/viewer/viewer.css";
import "./screens/diff/diff.css";
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
