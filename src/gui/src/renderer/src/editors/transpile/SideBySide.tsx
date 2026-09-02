import { useEffect, useRef, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import { monacoEditor } from "../../vendor/monacoEditor";
import { registerLanguages } from "../../vendor/monacoLanguages";
import { CODE_FONT } from "../../vendor/monarch";

/** Maps a line of one pane onto a line of the other, or null when there is no correspondence. */
export type LineLink = (line: number) => number | null;

export interface SideBySideProps {
  left: string;
  leftLanguageId: string;
  leftLabel: string;
  right: string;
  rightLanguageId: string;
  rightLabel: string;
  /** The left pane's line to the right pane's. */
  toRight: LineLink;
  /** The right pane's line to the left pane's. */
  toLeft: LineLink;
}

/** The pair of links, as the listeners registered once need to read them. */
interface Links {
  toRight: LineLink;
  toLeft: LineLink;
}

const OPTIONS: monacoApi.editor.IStandaloneEditorConstructionOptions = {
  // Without this the editor creates a model of its own, which the first model effect would then
  // dispose a second time after `setModel` had already disposed it.
  model: null,
  ...CODE_FONT,
  automaticLayout: true,
  readOnly: true,
  domReadOnly: true,
  // The synchronisation below relies on a scroll landing within the call that asked for it, which an
  // animated scroll does not.
  smoothScrolling: false,
  minimap: { enabled: false },
  lineNumbersMinChars: 5,
  // The left pane is fixed-format COBOL: the same column rulers as the source view.
  rulers: [6, 7, 11, 72],
  renderLineHighlight: "line",
  scrollBeyondLastLine: false,
  wordWrap: "off",
  folding: false,
  contextmenu: false,
};

/**
 * Two read-only editors side by side, tied together through the line map.
 *
 * Both directions are wired, so a reader can start from either language. The guard around every
 * synchronised move is what stops the pair from pushing each other: Monaco raises the cursor and
 * scroll events synchronously from inside `setPosition` and `setScrollTop`, so the move that the
 * synchronisation itself makes is seen while the flag is still up and is ignored.
 *
 * A line the engine recorded no correspondence for moves nothing. Guessing at one — the nearest row,
 * say — would line the panes up on something the translation never claimed.
 */
export function SideBySide({
  left,
  leftLanguageId,
  leftLabel,
  right,
  rightLanguageId,
  rightLabel,
  toRight,
  toLeft,
}: SideBySideProps): ReactElement {
  const leftHost = useRef<HTMLDivElement | null>(null);
  const rightHost = useRef<HTMLDivElement | null>(null);
  const leftEditor = useRef<monacoApi.editor.IStandaloneCodeEditor | null>(null);
  const rightEditor = useRef<monacoApi.editor.IStandaloneCodeEditor | null>(null);
  const links = useRef<Links>({ toRight, toLeft });
  links.current = { toRight, toLeft };

  useEffect(() => {
    const leftNode = leftHost.current;
    const rightNode = rightHost.current;
    if (leftNode === null || rightNode === null) {
      return;
    }
    const monaco = monacoEditor();
    registerLanguages(monaco);
    const first = monaco.editor.create(leftNode, { ...OPTIONS, ariaLabel: leftLabel });
    const second = monaco.editor.create(rightNode, { ...OPTIONS, ariaLabel: rightLabel });
    leftEditor.current = first;
    rightEditor.current = second;

    let syncing = false;
    const sync = (move: () => void): void => {
      if (syncing) {
        return;
      }
      syncing = true;
      try {
        move();
      } finally {
        syncing = false;
      }
    };

    const follow = (
      source: monacoApi.editor.IStandaloneCodeEditor,
      target: monacoApi.editor.IStandaloneCodeEditor,
      link: () => LineLink,
    ): monacoApi.IDisposable[] => [
      source.onDidChangeCursorPosition((event) => {
        const line = link()(event.position.lineNumber);
        if (line === null) {
          return;
        }
        sync(() => {
          target.setPosition({ lineNumber: line, column: 1 });
          // ScrollType.Immediate (1): the default is Smooth, which would scroll outside the guard.
          target.revealLineInCenter(line, 1);
        });
      }),
      source.onDidScrollChange(() => {
        const top = source.getVisibleRanges()[0]?.startLineNumber;
        const line = top === undefined ? null : link()(top);
        if (line === null) {
          return;
        }
        sync(() => target.setScrollTop(target.getTopForLineNumber(line)));
      }),
    ];

    const listeners = [
      ...follow(first, second, () => links.current.toRight),
      ...follow(second, first, () => links.current.toLeft),
    ];

    return () => {
      for (const listener of listeners) {
        listener.dispose();
      }
      leftEditor.current = null;
      rightEditor.current = null;
      const leftModel = first.getModel();
      const rightModel = second.getModel();
      first.dispose();
      second.dispose();
      leftModel?.dispose();
      rightModel?.dispose();
    };
    // The pair is created once; the labels only name it for a screen reader.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const editor = leftEditor.current;
    if (editor === null) {
      return;
    }
    const previous = editor.getModel();
    editor.setModel(monacoEditor().editor.createModel(left, leftLanguageId));
    editor.updateOptions({ ariaLabel: leftLabel });
    previous?.dispose();
  }, [left, leftLanguageId, leftLabel]);

  useEffect(() => {
    const editor = rightEditor.current;
    if (editor === null) {
      return;
    }
    const previous = editor.getModel();
    editor.setModel(monacoEditor().editor.createModel(right, rightLanguageId));
    // The label names the generated file, which the language selector changes.
    editor.updateOptions({ ariaLabel: rightLabel });
    previous?.dispose();
  }, [right, rightLanguageId, rightLabel]);

  return (
    <div className="ci-sidebyside">
      <div className="ci-sidebyside__pane">
        <span className="ci-sidebyside__label">{leftLabel}</span>
        <div className="ci-sidebyside__code" ref={leftHost} data-testid="transpile-cobol" />
      </div>
      <div className="ci-sidebyside__pane">
        <span className="ci-sidebyside__label">{rightLabel}</span>
        <div className="ci-sidebyside__code" ref={rightHost} data-testid="transpile-generated" />
      </div>
    </div>
  );
}
