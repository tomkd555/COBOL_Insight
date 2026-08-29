/**
 * The Monaco editor, bundled locally; no CDN is referenced.
 *
 * The worker is imported through Vite's `?worker&inline` and started from a Blob
 * (URL.createObjectURL). In a distribution the renderer is loaded from file://, and a worker cannot
 * be started from a file:// script URL, so a separate worker file would simply fail to launch. That
 * is why index.html's CSP allows worker-src 'self' blob: (and no external origin).
 *
 * The per-language service workers (json/css/html/typescript) are not used: COBOL, Python and Java
 * are highlighted with Monarch, which is syntax only, so the one base worker suffices.
 *
 * The import paths follow monaco-editor 0.56's exports map ("./*" -> "./esm/vs/*.js"): the API is
 * "monaco-editor/editor/editor.api" and the worker is "monaco-editor/editor/editor.worker". The
 * package's default entry point is avoided because it registers every language.
 */

import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker&inline";
// The icon glyphs (codicon): the @font-face rule and codicon.ttf. They are not part of the
// editor.api import, and without them the shell's icons render as blank boxes. The font is
// referenced relatively, so it also loads from file://.
import "monaco-editor/features/codicon/register";

/**
 * The globals Monaco reads when it creates a worker. ciMonaco is the handle the offscreen render
 * smoke types into: the editor's model lives inside Monaco and cannot be reached from the DOM.
 */
interface MonacoWorkerHost {
  MonacoEnvironment: monaco.Environment;
  ciMonaco: typeof monaco;
}

let configured = false;

/** The Monaco API with worker creation wired up. The wiring happens once. */
export function monacoEditor(): typeof monaco {
  if (!configured) {
    const host = self as unknown as MonacoWorkerHost;
    host.MonacoEnvironment = { getWorker: () => new EditorWorker() };
    host.ciMonaco = monaco;
    configured = true;
  }
  return monaco;
}

/**
 * Registers the codicon font without starting the editor. The shell's icons are codicon glyphs, so
 * the font has to be present before the first paint even where no editor is open.
 *
 * Importing this module is what registers the font; this function exists so the import is a
 * deliberate call rather than a side effect a bundler might drop.
 */
export function monacoCodicons(): void {
  // The side-effect import at the top of this module has already installed the @font-face rule.
}
