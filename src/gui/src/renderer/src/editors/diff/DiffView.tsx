import { useEffect, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "../../vendor/monacoEditor";
import { registerLanguages } from "../../vendor/monacoLanguages";
import { CODE_FONT } from "../../vendor/monarch";

export interface DiffViewProps {
  /** The text on the left. */
  original: string;
  /** The text on the right. */
  modified: string;
  /** The Monaco language id both sides are highlighted in. */
  languageId: string;
  ariaLabel: string;
}

/**
 * Two texts side by side, as Monaco's diff editor.
 *
 * The difference itself is Monaco's to compute: nothing here decides what changed. Both sides are
 * read-only — the left is the original on disk and the right is either the engine's proposal or an
 * edit that is being compared against the file, and neither is edited from this view.
 *
 * The two models belong to this view and are disposed with it. They are deliberately not put in the
 * tab model registry, which exists so that an editable document keeps its undo stack.
 */
export function DiffView({ original, modified, languageId, ariaLabel }: DiffViewProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monacoApi.editor.IStandaloneDiffEditor | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const monaco = monacoEditor();
    registerLanguages(monaco);
    const editor = monaco.editor.createDiffEditor(container, {
      ...CODE_FONT,
      automaticLayout: true,
      readOnly: true,
      originalEditable: false,
      renderSideBySide: true,
      minimap: { enabled: false },
      wordWrap: "off",
      folding: false,
      contextmenu: false,
    });
    editorRef.current = editor;
    return () => {
      const model = editor.getModel();
      editorRef.current = null;
      editor.dispose();
      model?.original.dispose();
      model?.modified.dispose();
    };
  }, []);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    const monaco = monacoEditor();
    const previous = editor.getModel();
    editor.setModel({
      original: monaco.editor.createModel(original, languageId),
      modified: monaco.editor.createModel(modified, languageId),
    });
    previous?.original.dispose();
    previous?.modified.dispose();
  }, [original, modified, languageId]);

  return <div className="ci-diff" ref={containerRef} role="group" aria-label={ariaLabel} />;
}
