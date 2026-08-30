import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { registerCodicons } from "./vendor/codicons";
import "../../../theme.css";
import "./styles/app-tokens.css";
import "./styles/base.css";
import "./styles/shell.css";

// The shell's icons are Monaco's codicon glyphs, so the font is registered before the first paint.
registerCodicons();

const container = document.getElementById("root");
if (container === null) {
  throw new Error("#root is missing from index.html");
}
createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
