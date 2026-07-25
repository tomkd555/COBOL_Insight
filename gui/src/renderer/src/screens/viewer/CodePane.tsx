import { useEffect, useMemo, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "../../vendor/monacoEditor";
import { registerGeneratedLanguages } from "../../vendor/monacoLanguages";
import { COBOL_INSIGHT_THEME, registerCobolLanguage } from "./cobolMonarch";
import { buildLineDecorations, type LineColumnRange } from "./viewerModel";

export interface CodePaneProps {
  /** 表示に使う monaco の言語 ID(COBOL 固定形式・python・java)。 */
  languageId: string;
  /** 表示する本文。読み取り専用で表示する。 */
  text: string;
  /** 桁ルーラの位置。固定形式の欄割りを示すために用いる。 */
  rulers?: readonly number[];
  /** 相互ハイライトで強調する行。 */
  linkedLines: readonly number[];
  /** 直訳できなかった注記を持つ行。 */
  notedLines: readonly number[];
  /** ジャンプ先の行。設定した行を中央へスクロールし、他より強く示す。 */
  focusLine: number | null;
  /** 中央へスクロールするだけの行。反対側のペインで選んだ対応行を見せるために用いる。 */
  revealLine?: number | null;
  /** 識別欄(73〜80桁)の範囲。本体と区別して弱く示す。 */
  identification?: readonly LineColumnRange[];
  /** カーソル行が変わったときに呼ぶ。相互ハイライトの起点になる。 */
  onCursorLine: (line: number) => void;
  ariaLabel: string;
}

/**
 * Monaco による読み取り専用のコード表示面。vs のソースはローカル同梱で、CDN は参照しない。
 * 表示内容の決定(強調行・注記行・識別欄の範囲)は viewerModel の純関数に委ね、ここが持つのは
 * エディタの生成と破棄、本文の差し替え、装飾の適用、カーソル行の通知だけである。
 *
 * 言語を切り替えたときはエディタを作り直す。言語ごとに桁ルーラと本文が同時に変わるため、
 * モデルの言語だけを差し替える経路を別に持つ必要がない。
 */
export function CodePane({
  languageId,
  text,
  rulers,
  linkedLines,
  notedLines,
  focusLine,
  revealLine,
  identification,
  onCursorLine,
  ariaLabel,
}: CodePaneProps): ReactElement {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monacoApi.editor.IStandaloneCodeEditor | null>(null);
  const collectionRef = useRef<monacoApi.editor.IEditorDecorationsCollection | null>(null);
  const textRef = useRef(text);
  textRef.current = text;
  const cursorRef = useRef(onCursorLine);
  cursorRef.current = onCursorLine;
  const rulersRef = useRef(rulers);
  rulersRef.current = rulers;

  const decorations = useMemo(
    () =>
      buildLineDecorations({
        linkedLines,
        notedLines,
        focusLine,
        identification: identification ?? [],
      }),
    [linkedLines, notedLines, focusLine, identification],
  );
  const decorationsRef = useRef(decorations);
  decorationsRef.current = decorations;

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const monaco = monacoEditor();
    registerCobolLanguage(monaco);
    registerGeneratedLanguages(monaco);
    const editor = monaco.editor.create(container, {
      value: textRef.current,
      language: languageId,
      theme: COBOL_INSIGHT_THEME,
      readOnly: true,
      domReadOnly: true,
      automaticLayout: true,
      minimap: { enabled: false },
      fontFamily: "'BIZ UDGothic','MS Gothic',monospace",
      fontSize: 12,
      lineHeight: 19,
      lineNumbersMinChars: 5,
      rulers: [...(rulersRef.current ?? [])],
      renderLineHighlight: "none",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      renderWhitespace: "none",
      occurrencesHighlight: "off",
      folding: false,
      glyphMargin: false,
      contextmenu: false,
      ariaLabel,
    });
    editorRef.current = editor;
    // 作り直した直後も現在の装飾を保つ(装飾の内容が変わらない場合は下の効果が走らない)。
    collectionRef.current = editor.createDecorationsCollection(decorationsRef.current);
    const subscription = editor.onDidChangeCursorPosition((event) => {
      cursorRef.current(event.position.lineNumber);
    });
    return () => {
      subscription.dispose();
      collectionRef.current = null;
      editorRef.current = null;
      editor.dispose();
    };
  }, [languageId, ariaLabel]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor !== null && editor.getValue() !== text) {
      editor.setValue(text);
    }
  }, [text]);

  useEffect(() => {
    collectionRef.current?.set(decorations);
  }, [decorations]);

  useEffect(() => {
    if (focusLine !== null) {
      editorRef.current?.revealLineInCenter(focusLine);
    }
  }, [focusLine, text]);

  useEffect(() => {
    if (revealLine !== null && revealLine !== undefined) {
      editorRef.current?.revealLineInCenter(revealLine);
    }
  }, [revealLine]);

  return <div className="ci-code" ref={containerRef} role="group" aria-label={ariaLabel} />;
}
