import { useEffect, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "../../vendor/monacoEditor";
import { COBOL_INSIGHT_THEME, COBOL_LANGUAGE_ID, registerCobolLanguage } from "../viewer/cobolMonarch";

export interface DiffPaneProps {
  /** 左ペイン(修正前)の本文。原本のテキストをそのまま渡す。 */
  originalText: string;
  /** 右ペイン(修正後)の本文。engine が書いた修正後ソースのテキストをそのまま渡す。 */
  fixedText: string;
  /** 領域としての名前(表示中のファイルを含める)。 */
  ariaLabel: string;
}

/**
 * Monaco DiffEditor による読み取り専用の左右2ペイン差分表示。左=原本、右=修正後で、engine が出した
 * 原本/修正後テキストの対をそのまま渡す(裁定 A3)。GUI は unified diff を再計算しない。
 *
 * 固定形式 COBOL の桁は空白の有無で意味が変わるため、空白の差を無視しない
 * (ignoreTrimWhitespace: false)。文法とテーマはソースビューアと同じ COBOL Monarch を共有する。
 */
export function DiffPane({ originalText, fixedText, ariaLabel }: DiffPaneProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monacoApi.editor.IStandaloneDiffEditor | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const monaco = monacoEditor();
    registerCobolLanguage(monaco);
    const editor = monaco.editor.createDiffEditor(container, {
      theme: COBOL_INSIGHT_THEME,
      readOnly: true,
      originalEditable: false,
      renderSideBySide: true,
      automaticLayout: true,
      ignoreTrimWhitespace: false,
      minimap: { enabled: false },
      fontFamily: "'BIZ UDGothic','MS Gothic',monospace",
      fontSize: 12,
      lineHeight: 19,
      lineNumbersMinChars: 5,
      renderLineHighlight: "none",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      folding: false,
      glyphMargin: false,
      contextmenu: false,
      originalAriaLabel: "修正前（原本）",
      modifiedAriaLabel: "修正後",
    });
    editorRef.current = editor;
    return () => {
      editorRef.current = null;
      editor.dispose();
    };
  }, []);

  // 本文が変わるたびにモデルを作り直す。破棄をこの効果の後始末に置き、モデルを漏らさない。
  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    const monaco = monacoEditor();
    const original = monaco.editor.createModel(originalText, COBOL_LANGUAGE_ID);
    const modified = monaco.editor.createModel(fixedText, COBOL_LANGUAGE_ID);
    editor.setModel({ original, modified });
    return () => {
      original.dispose();
      modified.dispose();
    };
  }, [originalText, fixedText]);

  return <div className="ci-diff__editor" ref={containerRef} role="group" aria-label={ariaLabel} />;
}
