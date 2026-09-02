/**
 * The codicon icon font, on its own.
 *
 * The shell's icons are codicon glyphs, but the shell does not open a Monaco editor until an asset
 * is opened. The font face comes from the feature import below; the per-icon rules
 * (`.codicon-files::before { content: … }`) do not, because Monaco's theme service writes them only
 * when its first editor container is registered. Until then every icon in the shell was blank. So
 * the same rules are generated here from Monaco's icon map, once, before the first paint.
 *
 * The @font-face rule references codicon.ttf relatively, so it also loads from file://.
 */

import "monaco-editor/features/codicon/register";
import { getCodiconFontCharacters } from "monaco-editor/base/common/codiconsUtil.js";

/** Registers the font face and the icon rules. */
export function registerCodicons(): void {
  const rules = Object.entries(getCodiconFontCharacters()).map(
    ([name, codePoint]) =>
      `.codicon-${name}::before { content: "\\${codePoint.toString(16)}"; }`,
  );
  const style = document.createElement("style");
  style.textContent = rules.join("\n");
  document.head.appendChild(style);
}
