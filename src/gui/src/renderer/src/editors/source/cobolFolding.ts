/**
 * The outline of the text an editor model holds, and the folding ranges Monaco asks for.
 *
 * The outline is read by the folding provider, by the pane beside the code and by the press on a
 * paragraph name, each of which asks for it whenever it likes. It is scanned once per version of
 * the model and kept beside the model, so the three share one scan.
 *
 * The provider is registered once, as the quick fix is: Monaco holds on to the provider it was
 * given, and which model it is asked about arrives with every call.
 */

import type * as monacoApi from "monaco-editor/editor/editor.api";
import { scanCobol, type CobolOutline } from "../../model/cobolOutline";
import { boundariesOf, type EditorCodepage } from "../../model/columns";
import { LANGUAGE_ID } from "../../vendor/monarch";

type Model = monacoApi.editor.ITextModel;

const codepages = new WeakMap<Model, EditorCodepage>();
const outlines = new WeakMap<Model, { version: number; outline: CobolOutline }>();

/**
 * Says which codepage the model's text is in. The areas of a line holding double-byte characters
 * are byte columns, so the offsets the scan reads them at depend on it.
 */
export function setOutlineCodepage(model: Model, codepage: EditorCodepage): void {
  if (codepages.get(model) !== codepage) {
    codepages.set(model, codepage);
    outlines.delete(model);
  }
}

/** The outline of the model's text as it now stands. */
export function outlineOf(model: Model): CobolOutline {
  const version = model.getVersionId();
  const kept = outlines.get(model);
  if (kept !== undefined && kept.version === version) {
    return kept.outline;
  }
  const codepage = codepages.get(model) ?? "UTF-8";
  const outline = scanCobol(model.getLinesContent(), (line) => boundariesOf(line, codepage));
  outlines.set(model, { version, outline });
  return outline;
}

let registered = false;

/** Registers the folding provider for COBOL. Repeated calls do nothing. */
export function registerCobolFolding(monaco: typeof monacoApi): void {
  if (registered) {
    return;
  }
  monaco.languages.registerFoldingRangeProvider(LANGUAGE_ID.cobol, {
    provideFoldingRanges: (model) =>
      outlineOf(model).folds.map((fold) => ({
        start: fold.start,
        end: fold.end,
        kind: fold.comment ? monaco.languages.FoldingRangeKind.Comment : undefined,
      })),
  });
  registered = true;
}
