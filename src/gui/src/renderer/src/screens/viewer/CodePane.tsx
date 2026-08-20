import { useEffect, useMemo, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "../../vendor/monacoEditor";
import { registerGeneratedLanguages } from "../../vendor/monacoLanguages";
import { COBOL_INSIGHT_THEME, registerCobolLanguage } from "./cobolMonarch";
import {
  buildLineDecorations,
  metricsEqual,
  type EditorMetrics,
  type ExpansionZone,
  type FindingLine,
  type LineColumnRange,
} from "./viewerModel";

/** 未指定のときに使う空の指摘。参照を固定して useMemo の依存を安定させる。 */
const NO_FINDINGS: readonly FindingLine[] = [];

/** 未指定のときに使う空の差し込み。 */
const NO_EXPANSIONS: readonly ExpansionZone[] = [];

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
  /** 指摘のある行。重大度の記号と色で示す。 */
  findings?: readonly FindingLine[];
  /** COPY 文の位置へ差し込む展開。原本の行の間へ置き、行番号の並びは変えない。 */
  expansions?: readonly ExpansionZone[];
  /** グリフ余白を出すか。指摘の記号を置くペインで真にする。 */
  glyphMargin?: boolean;
  /** 桁見出しをそろえるための実測寸法。値が変わったときだけ呼ぶ。 */
  onMetrics?: (metrics: EditorMetrics) => void;
  /** カーソル位置が変わったときに呼ぶ。相互ハイライトの起点と、ステータスバーの表示に使う。 */
  onCursorLine: (line: number, column: number) => void;
  ariaLabel: string;
}

/** 見出しが占める行数(表題と注記で1行ずつ)。差し込みの高さと行番号の位置合わせに用いる。 */
const EXPANSION_HEAD_LINES = 2;

/** 展開の見出しと本文を組む。Monaco のビューゾーンへ渡す DOM であり、React は関与しない。 */
function expansionNode(zone: ExpansionZone): HTMLElement {
  const root = document.createElement("div");
  root.className = "ci-code__expansion";
  root.setAttribute("role", "group");
  root.setAttribute("aria-label", zone.ariaLabel);

  const title = document.createElement("div");
  title.className = "ci-code__expansion-title";
  title.textContent = zone.title;
  const note = document.createElement("div");
  note.className = "ci-code__expansion-note";
  note.textContent = zone.note;
  root.append(title, note);

  for (const line of zone.lines) {
    const row = document.createElement("div");
    row.className = line.restored
      ? "ci-code__expansion-line ci-code__expansion-line--restored"
      : "ci-code__expansion-line";
    // 空文字の行でも高さを保ち、コピー句の行番号との対応を崩さない。
    row.textContent = line.text === "" ? " " : line.text;
    root.append(row);
  }
  return root;
}

/** 展開の行番号を、原本の行番号ガターと同じ位置へ置く。値はコピー句の中での行番号である。 */
function expansionMarginNode(zone: ExpansionZone): HTMLElement {
  const root = document.createElement("div");
  root.className = "ci-code__expansion-margin";
  // 見出しの行数分は空ける。以降が展開行と1対1で並ぶ。
  for (let index = 0; index < EXPANSION_HEAD_LINES; index += 1) {
    const head = document.createElement("div");
    head.className = "ci-code__expansion-lineno";
    head.textContent = " ";
    root.append(head);
  }
  for (const line of zone.lines) {
    const number = document.createElement("div");
    number.className = "ci-code__expansion-lineno";
    number.textContent = String(line.copybookLine);
    root.append(number);
  }
  return root;
}

/**
 * Monaco による読み取り専用のコード表示面。vs のソースはローカル同梱で、CDN は参照しない。
 * 表示内容の決定(指摘行・強調行・注記行・識別欄の範囲・COPY 展開の中身)は viewerModel の純関数に
 * 委ね、ここが持つのはエディタの生成と破棄、本文の差し替え、装飾と差し込みの適用、カーソル行と
 * 実測寸法の通知だけである。
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
  findings,
  expansions,
  glyphMargin,
  onMetrics,
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
  const metricsRef = useRef(onMetrics);
  metricsRef.current = onMetrics;
  const lastMetrics = useRef<EditorMetrics | null>(null);

  const decorations = useMemo(
    () =>
      buildLineDecorations({
        linkedLines,
        notedLines,
        focusLine,
        identification: identification ?? [],
        findings: findings ?? NO_FINDINGS,
      }),
    [linkedLines, notedLines, focusLine, identification, findings],
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
      // readOnly は編集の反映を止め、domReadOnly は入力欄(textarea)自体を読み取り専用にする。
      // 表示専用の面なので両方を指定する。
      readOnly: true,
      domReadOnly: true,
      automaticLayout: true,
      minimap: { enabled: false },
      fontFamily: "'BIZ UDGothic','MS Gothic',monospace",
      fontSize: 12,
      lineHeight: 19,
      // 行番号欄を5桁(99,999 行)分で固定し、資産の行数の違いで本文の左端が動かないようにする。
      lineNumbersMinChars: 5,
      rulers: [...(rulersRef.current ?? [])],
      renderLineHighlight: "none",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      renderWhitespace: "none",
      occurrencesHighlight: "off",
      folding: false,
      glyphMargin: glyphMargin === true,
      contextmenu: false,
      ariaLabel,
    });
    editorRef.current = editor;
    // 作り直した直後も現在の装飾を保つ(装飾の内容が変わらない場合は下の効果が走らない)。
    collectionRef.current = editor.createDecorationsCollection(decorationsRef.current);
    const subscription = editor.onDidChangeCursorPosition((event) => {
      cursorRef.current(event.position.lineNumber, event.position.column);
    });
    // 桁見出しの位置は Monaco の実測に従う。ガター幅・字送りは配置時に、水平位置はスクロールで変わる。
    const publishMetrics = (): void => {
      const notify = metricsRef.current;
      if (notify === undefined) {
        return;
      }
      const font = editor.getOption(monaco.editor.EditorOption.fontInfo);
      const next: EditorMetrics = {
        contentLeft: editor.getLayoutInfo().contentLeft,
        charWidth: font.typicalHalfwidthCharacterWidth,
        scrollLeft: editor.getScrollLeft(),
      };
      if (metricsEqual(lastMetrics.current, next)) {
        return;
      }
      lastMetrics.current = next;
      notify(next);
    };
    publishMetrics();
    const layoutSubscription = editor.onDidLayoutChange(publishMetrics);
    const scrollSubscription = editor.onDidScrollChange(publishMetrics);
    return () => {
      subscription.dispose();
      layoutSubscription.dispose();
      scrollSubscription.dispose();
      lastMetrics.current = null;
      collectionRef.current = null;
      editorRef.current = null;
      editor.dispose();
    };
  }, [languageId, ariaLabel, glyphMargin]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor !== null && editor.getValue() !== text) {
      editor.setValue(text);
    }
  }, [text]);

  /*
   * COPY 展開をビューゾーンとして差し込む。ビューゾーンは行の間に置かれる領域であり、原本の
   * 行番号を消費しないため、差し込んでも行番号は飛ばず重複しない。字送りと行の高さは Monaco の
   * 実測値をそのまま与え、桁がコード面とそろって見えるようにする。
   *
   * 依存にエディタの生成条件(言語・ariaLabel・グリフ余白)と本文を含める。エディタを作り直した
   * ときと本文を差し替えたときに、差し込みも組み直す必要があるためである。
   */
  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    const zones = expansions ?? NO_EXPANSIONS;
    const monaco = monacoEditor();
    const font = editor.getOption(monaco.editor.EditorOption.fontInfo);
    const ids: string[] = [];
    editor.changeViewZones((accessor) => {
      for (const zone of zones) {
        const domNode = expansionNode(zone);
        const marginDomNode = expansionMarginNode(zone);
        for (const node of [domNode, marginDomNode]) {
          node.style.fontFamily = font.fontFamily;
          node.style.fontSize = `${font.fontSize}px`;
          node.style.lineHeight = `${font.lineHeight}px`;
        }
        ids.push(
          accessor.addZone({
            afterLineNumber: zone.afterLine,
            // 見出しと展開行。行の高さはエディタと同じであり、桁定規の間隔も保たれる。
            heightInLines: zone.lines.length + EXPANSION_HEAD_LINES,
            domNode,
            marginDomNode,
          }),
        );
      }
    });
    return () => {
      editorRef.current?.changeViewZones((accessor) => {
        for (const id of ids) {
          accessor.removeZone(id);
        }
      });
    };
  }, [expansions, text, languageId, ariaLabel, glyphMargin]);

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
