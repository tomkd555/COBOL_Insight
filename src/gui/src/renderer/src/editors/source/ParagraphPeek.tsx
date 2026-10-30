import { useEffect, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { text } from "../../i18n/text";
import { RULER_COLUMNS, SEQUENCE_COLUMNS } from "../../model/columns";
import { monacoEditor } from "../../vendor/monacoEditor";
import { existingModel } from "../../vendor/monacoModels";
import { CODE_FONT } from "../../vendor/monarch";

export interface ParagraphPeekProps {
  /** The source tab whose text is shown. */
  tabId: string;
  /** The lines of the paragraph to show, one-based with both ends included; null shows none. */
  range: { readonly start: number; readonly end: number } | null;
}

/**
 * The text of one paragraph, beside the source it belongs to.
 *
 * It is a second editor on the same model as the main one, read-only: the paragraph is shown as the
 * text now stands, an edit in the main editor arrives here as it is typed, and nothing is copied.
 * The main editor keeps its own scroll position, which is the point of showing the paragraph here.
 */
export function ParagraphPeek({ tabId, range }: ParagraphPeekProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monacoApi.editor.IStandaloneCodeEditor | null>(null);
  const markRef = useRef<monacoApi.editor.IEditorDecorationsCollection | null>(null);
  /** Brings the paragraph on show to the top of the pane. Does nothing while none is shown. */
  const revealRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const editor = monacoEditor().editor.create(container, {
      model: null,
      ...CODE_FONT,
      automaticLayout: true,
      readOnly: true,
      domReadOnly: true,
      minimap: { enabled: false },
      lineNumbersMinChars: 5,
      rulers: [...RULER_COLUMNS],
      renderLineHighlight: "none",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      occurrencesHighlight: "off",
      folding: false,
      glyphMargin: false,
      contextmenu: false,
      // Tab leaves the editor, so the keyboard passes through the pane.
      tabFocusMode: true,
      ariaLabel: text.sourceView.peek,
    });
    editorRef.current = editor;
    markRef.current = editor.createDecorationsCollection([]);
    // How far the text can scroll depends on the height, and the pane takes its height after the
    // paragraph has been brought to the top; so it is brought there again whenever the size changes.
    const laidOut = editor.onDidLayoutChange(() => revealRef.current());
    return () => {
      laidOut.dispose();
      markRef.current = null;
      editorRef.current = null;
      // The model belongs to the tab; only this view of it goes.
      editor.setModel(null);
      editor.dispose();
    };
  }, []);

  const start = range?.start ?? null;
  const end = range?.end ?? null;

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    const model = start === null || end === null ? null : existingModel(tabId);
    if (model === null || start === null || end === null) {
      revealRef.current = () => undefined;
      editor.setModel(null);
      return;
    }
    const monaco = monacoEditor();
    if (editor.getModel() !== model) {
      editor.setModel(model);
    }
    const lines = new monaco.Range(start, 1, Math.min(end, model.getLineCount()), 1);
    markRef.current?.set([{ range: lines, options: { isWholeLine: true, className: "ci-peek__range" } }]);
    revealRef.current = (): void => {
      // The paragraph's first line at the very top: the pane is a few lines high, and every line
      // above the paragraph is one line of it out of view.
      editor.setScrollTop(editor.getTopForLineNumber(start));
      const cell = editor.getOption(monaco.editor.EditorOption.fontInfo).typicalHalfwidthCharacterWidth;
      // The pane is narrow, so it opens past the sequence area.
      editor.setScrollLeft(SEQUENCE_COLUMNS * cell);
    };
    revealRef.current();
  }, [tabId, start, end]);

  return <div className="ci-peek" ref={containerRef} data-testid="paragraph-peek" />;
}
