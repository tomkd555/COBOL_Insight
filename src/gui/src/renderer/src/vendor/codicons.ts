/**
 * The codicon icon font, on its own.
 *
 * The shell's icons are codicon glyphs, but the shell does not yet open a Monaco editor. Importing
 * vendor/monacoEditor for the font alone would pull the whole ~6MB editor into the bundle, so the
 * font registration is imported here by itself. When the source editor arrives, that module brings
 * the same registration with it and this one becomes redundant.
 *
 * The @font-face rule references codicon.ttf relatively, so it also loads from file://.
 */

import "monaco-editor/features/codicon/register";

/**
 * Registers the font. The side-effect import above is what does the work; this function exists so
 * the registration is a deliberate call rather than an import a bundler might treat as removable.
 */
export function registerCodicons(): void {
  // Nothing further: importing this module has already installed the @font-face rule.
}
