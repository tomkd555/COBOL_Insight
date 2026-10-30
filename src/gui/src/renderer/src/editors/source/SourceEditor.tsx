import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactElement,
} from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { DecodeResult } from "../../../../shared/ipc";
import { api, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { artifactItems, useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import {
  draftOf,
  paragraphChoiceOf,
  sourceTab,
  sourceTabId,
  useWorkbench,
  useWorkbenchDispatch,
} from "../../state/workbenchStore";
import { useSetEditorStatus } from "../../state/editorStatusStore";
import { openDocument, rememberDocument } from "../../model/openDocuments";
import { resolveAt, type CobolOutline } from "../../model/cobolOutline";
import {
  COLS_LINE,
  RECORD_COLUMNS,
  RULER_COLUMNS,
  areaRanges,
  boundariesOf,
  editorCodepageOf,
  type EditorCodepage,
} from "../../model/columns";
import {
  MARKER_OWNER,
  findingMarkers,
  glyphClassOf,
  overflowMarkers,
  reparseMarkers,
  type EditorMarker,
} from "../../model/markers";
import { ruleOf } from "../../model/ruleIndex";
import { monacoEditor } from "../../vendor/monacoEditor";
import { registerLanguages } from "../../vendor/monacoLanguages";
import { CODE_FONT, LANGUAGE_ID, languageIdFor } from "../../vendor/monarch";
import { existingModel, modelFor } from "../../vendor/monacoModels";
import { outlineOf, registerCobolFolding, setOutlineCodepage } from "./cobolFolding";
import { useCopyZones } from "./copyZones";
import { ParagraphPane } from "./ParagraphPane";
import { registerQuickFix, setQuickFixTarget } from "./quickFix";

/** The least width, in pixels, the pane beside the code is drawn at. Under it the code takes the row. */
const PANE_MIN_WIDTH = 300;

/** How long the text rests after an edit before the outline and the references are read again. */
const OUTLINE_DELAY_MS = 300;

/** The languages laid out in fixed columns, which the column scale is drawn for. */
const SCALED_LANGUAGES: ReadonlySet<string> = new Set([LANGUAGE_ID.cobol, LANGUAGE_ID.jcl, LANGUAGE_ID.bms]);

export interface SourceEditorProps {
  /** The asset's path, relative to the asset folder. */
  path: string;
  /** The line to reveal on opening, or null. */
  line: number | null;
  /** Opens the fix diff for one asset. Reached from the quick fix on a finding that has one. */
  onShowFix: (path: string) => void;
}

/**
 * How the decode of one tab stands. The tab it belongs to is part of it: this element stays mounted
 * across a tab switch, so between the switch and the decode effect the state still holds the result
 * of the tab that was here a moment ago — and a result carrying no tab would be taken for the new
 * one's, long enough to put one asset's text into another's model.
 */
type Load =
  | { status: "loading"; tabId: string }
  | { status: "ready"; tabId: string; result: DecodeResult }
  | { status: "error"; tabId: string; message: string };

/**
 * The source view for one asset.
 *
 * Decoding belongs to the engine: Node has no EBCDIC converter, so the renderer receives text rather
 * than bytes, together with the byte-column boundaries of every line. The boundaries are what the
 * area decorations are drawn from; while the text is being edited they are recomputed locally
 * (model/columns), because asking the engine after every keystroke is impractical.
 *
 * One editor serves the whole group. The editor group renders this same element for every source
 * tab, so React keeps it mounted across a switch and only the model changes — which is what lets the
 * undo stack and an unsaved edit survive the switch.
 *
 * The COPY expansions scan recorded are drawn between the lines as view zones (editors/source
 * copyZones); the translation of the same asset is its own tab (editors/transpile), because lining
 * two languages up needs the width of the whole editor area.
 */
export function SourceEditor({ path, line, onShowFix }: SourceEditorProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();
  const setStatus = useSetEditorStatus();

  const [load, setLoad] = useState<Load>({ status: "loading", tabId: sourceTabId(path) });
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monacoApi.editor.IStandaloneCodeEditor | null>(null);
  const areasRef = useRef<monacoApi.editor.IEditorDecorationsCollection | null>(null);
  const glyphsRef = useRef<monacoApi.editor.IEditorDecorationsCollection | null>(null);
  /** The tab whose model the editor currently shows, so a content change is attributed correctly. */
  const activeTabRef = useRef<string>("");
  /** How the geometry stands right now: the engine's at open, recomputed locally after an edit. */
  const [boundaries, setBoundaries] = useState<readonly (readonly [number, number, number, number])[]>([]);

  const bodyRef = useRef<HTMLDivElement | null>(null);
  const scaleRef = useRef<HTMLSpanElement | null>(null);
  const refsRef = useRef<monacoApi.editor.IEditorDecorationsCollection | null>(null);
  const outlineTimer = useRef<number | null>(null);
  /** Whether the model on screen is fixed-format COBOL, for the listeners registered once. */
  const cobolRef = useRef(false);
  /** The outline of the text on screen; null for every language except COBOL. */
  const [held, setOutline] = useState<{ tabId: string; outline: CobolOutline } | null>(null);
  /** The width that holds the gutter and 80 columns of code, once the editor has measured its font. */
  const [codeWidth, setCodeWidth] = useState<number | null>(null);
  const [bodyWidth, setBodyWidth] = useState(0);
  /** Counts the names pressed in the code, so the pane's list moves its Tab stop for each press. */
  const [namePresses, setNamePresses] = useState(0);

  const tabId = sourceTabId(path);
  // What was read for another tab is that tab's: the pane waits for this one's.
  const outline = held !== null && held.tabId === tabId ? held.outline : null;
  const tabIdRef = useRef(tabId);
  tabIdRef.current = tabId;
  /** The drafts as they stand when a decode answers; the decode effect does not run on a keystroke. */
  const workbenchRef = useRef(workbench);
  workbenchRef.current = workbench;
  const baseDir = project.inputDir;
  const dbPath = project.dbPath;
  const rawOverride = project.codepageOverrides[path] ?? "";
  const requested = rawOverride === "" ? settings.defaultEncoding : rawOverride;

  const codepage: EditorCodepage = editorCodepageOf(
    load.status === "ready" ? load.result.codepage : requested,
  );
  const languageId = languageIdFor(path);
  const editable = load.status === "ready";
  const cobol = languageId === LANGUAGE_ID.cobol;
  cobolRef.current = cobol;

  /**
   * Reads the outline of the model on screen and marks the places it names a section or paragraph.
   * Called when the model changes and, after a pause, when its text does.
   */
  const refreshOutline = useCallback((): void => {
    const editor = editorRef.current;
    const model = editor?.getModel() ?? null;
    if (editor === null || model === null || !cobolRef.current) {
      setOutline(null);
      refsRef.current?.set([]);
      return;
    }
    const monaco = monacoEditor();
    const next = outlineOf(model);
    setOutline({ tabId: tabIdRef.current, outline: next });
    refsRef.current?.set(
      next.refs.map((ref) => ({
        range: new monaco.Range(ref.line, ref.startColumn, ref.line, ref.endColumn),
        options: {
          inlineClassName: "ci-code__ref",
          stickiness: monaco.editor.TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
        },
      })),
    );
  }, []);

  /* ---------------------------------------------------------------- decode */

  useEffect(() => {
    if (baseDir === null) {
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading", tabId });
    api()
      .decode({
        baseDir,
        path,
        // An explicit override wins; otherwise the engine reads the codepage the scan recorded.
        codepage: requested === "" ? undefined : requested,
        db: dbPath ?? undefined,
      })
      .then((result) => {
        if (cancelled) return;
        if (result.error !== "") {
          setLoad({ status: "error", tabId, message: result.error });
          return;
        }
        // A re-decode under an unsaved edit keeps the stamp the edit was made against, so a file
        // changed on disk since then still raises the conflict at save.
        rememberDocument(tabId, path, result, draftOf(workbenchRef.current, tabId) !== null);
        setLoad({ status: "ready", tabId, result });
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoad({ status: "error", tabId, message: errorMessage(error) });
      });
    return () => {
      cancelled = true;
    };
  }, [baseDir, path, tabId, requested, dbPath]);

  /**
   * What the two Monaco listeners need but cannot read from the closure they were created in. They
   * are registered once, so what changes afterwards reaches them through this ref, which spares
   * tearing the editor down and rebuilding it.
   *
   * It is written by the effect that switches the model and by nothing else, so what the listeners
   * read always describes the tab whose model is on screen. The decoded text is not kept here: it
   * moves on with every save and reload, and openDocuments is where it is up to date.
   */
  const current = useRef({ codepage: "", detected: false, editor: codepage });

  /* ---------------------------------------------------------------- the editor */

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }
    const monaco = monacoEditor();
    registerLanguages(monaco);
    registerQuickFix(monaco);
    registerCobolFolding(monaco);
    const editor = monaco.editor.create(container, {
      value: "",
      language: languageIdFor(""),
      ...CODE_FONT,
      automaticLayout: true,
      minimap: { enabled: false },
      // Five digits (99,999 lines) so the body's left edge does not move between assets.
      lineNumbersMinChars: 5,
      // The end of the sequence area, of the indicator, of area A, of the body, and of the record.
      rulers: [...RULER_COLUMNS],
      // The caret line is marked here as it is in the transpile view: this is the surface being
      // edited, and it was the one without it.
      renderLineHighlight: "line",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      renderWhitespace: "none",
      occurrencesHighlight: "off",
      // Turned on for COBOL when its model is set: the folding ranges are read from its structure.
      folding: false,
      glyphMargin: true,
      contextmenu: false,
      ariaLabel: text.sourceView.editor,
    });
    editorRef.current = editor;
    areasRef.current = editor.createDecorationsCollection([]);
    glyphsRef.current = editor.createDecorationsCollection([]);
    refsRef.current = editor.createDecorationsCollection([]);

    // The code column is as wide as the gutter and 80 columns of the code face, whatever the gutter
    // holds; the scale above it starts where the code does and scrolls with it.
    const measure = (): void => {
      const layout = editor.getLayoutInfo();
      const cell = editor.getOption(monaco.editor.EditorOption.fontInfo).typicalHalfwidthCharacterWidth;
      // Two pixels are what Monaco keeps for the caret at the end of the last column.
      setCodeWidth(
        Math.ceil(layout.contentLeft + RECORD_COLUMNS * cell + 2 + layout.verticalScrollbarWidth),
      );
      if (scaleRef.current !== null) {
        scaleRef.current.style.transform = `translateX(${layout.contentLeft - editor.getScrollLeft()}px)`;
      }
    };
    measure();
    const laidOut = editor.onDidLayoutChange(measure);
    const configured = editor.onDidChangeConfiguration(measure);
    const scrolled = editor.onDidScrollChange((event) => {
      if (event.scrollLeftChanged) {
        measure();
      }
    });

    // Pressing a section or paragraph name shows its text in the pane; the editor stays where it is.
    const showParagraphAt = (position: monacoApi.IPosition | null): void => {
      const model = editor.getModel();
      if (model === null || position === null || !cobolRef.current || activeTabRef.current === "") {
        return;
      }
      const key = resolveAt(outlineOf(model), position.lineNumber, position.column);
      if (key !== null) {
        workbenchDispatch({ type: "SET_PARAGRAPH", id: activeTabRef.current, shown: key });
        setNamePresses((count) => count + 1);
      }
    };
    const pressed = editor.onMouseUp((event) => {
      const selection = editor.getSelection();
      if (
        event.target.type === monaco.editor.MouseTargetType.CONTENT_TEXT &&
        event.event.leftButton &&
        (selection === null || selection.isEmpty())
      ) {
        showParagraphAt(event.target.position);
      }
    });
    const showAction = editor.addAction({
      id: "cobolInsight.showParagraph",
      label: text.sourceView.peekCommand,
      keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.F12],
      run: () => showParagraphAt(editor.getPosition()),
    });

    const cursor = editor.onDidChangeCursorPosition((event) => {
      setStatus({
        line: event.position.lineNumber,
        column: event.position.column,
        codepage: current.current.codepage,
        detected: current.current.detected,
      });
    });
    const content = editor.onDidChangeModelContent(() => {
      const id = activeTabRef.current;
      if (id === "") {
        return;
      }
      const value = editor.getValue();
      const model = editor.getModel();
      const lines = model === null ? value.split("\n") : model.getLinesContent();
      setBoundaries(lines.map((each) => boundariesOf(each, current.current.editor)));
      // The draft map is both the unsaved text and the dirty flag; text equal to the file as it now
      // stands — as decoded, or as last written — counts as unchanged.
      workbenchDispatch({
        type: "SET_DRAFT",
        id,
        draft: value === openDocument(id)?.text ? null : value,
      });
      // The outline is read once the typing rests: its line numbers are stale in between, and
      // reading it on every keystroke would redraw the pane under the user's hands.
      if (outlineTimer.current !== null) {
        window.clearTimeout(outlineTimer.current);
      }
      outlineTimer.current = window.setTimeout(refreshOutline, OUTLINE_DELAY_MS);
    });

    return () => {
      cursor.dispose();
      content.dispose();
      laidOut.dispose();
      configured.dispose();
      scrolled.dispose();
      pressed.dispose();
      showAction.dispose();
      if (outlineTimer.current !== null) {
        window.clearTimeout(outlineTimer.current);
        outlineTimer.current = null;
      }
      areasRef.current = null;
      glyphsRef.current = null;
      refsRef.current = null;
      editorRef.current = null;
      // The models outlive the editor: they belong to the tabs.
      editor.setModel(null);
      editor.dispose();
      setStatus(null);
    };
    // The editor is created once. Everything that changes afterwards is applied by the effects below.
  }, [setStatus, workbenchDispatch, refreshOutline]);

  /* ---------------------------------------------------------------- the model */

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    if (load.status !== "ready" || load.tabId !== tabId) {
      // Nothing to show for this tab: it is still being decoded, or its decode failed, or the
      // result on hand belongs to the tab that was here a moment ago. Another tab's model must not
      // stay under this tab's heading, where it would take this tab's markers and this tab's edits;
      // nor may its caret and codepage stay in the status bar under this tab's name.
      if (activeTabRef.current !== tabId) {
        activeTabRef.current = "";
        editor.setModel(null);
        refreshOutline();
      }
      setStatus(null);
      return;
    }
    // The listeners read this; it is set before the model moves, so no keystroke sees the pair
    // half-swapped.
    current.current = {
      codepage: load.result.codepage,
      detected: load.result.detected,
      editor: codepage,
    };
    const fresh = existingModel(tabId) === null;
    const model = modelFor(tabId, load.result.text, languageId);
    if (!fresh && model.getValue() !== load.result.text && draftOf(workbench, tabId) === null) {
      // The file was re-decoded (a different codepage) and nothing was edited: take the new text.
      model.setValue(load.result.text);
    }
    activeTabRef.current = tabId;
    setOutlineCodepage(model, codepage);
    editor.updateOptions({ folding: cobol });
    editor.setModel(model);
    refreshOutline();
    setBoundaries(
      fresh
        ? load.result.lines.map((each) => each.boundaries)
        : model.getLinesContent().map((each) => boundariesOf(each, codepage)),
    );
    setStatus({
      line: editor.getPosition()?.lineNumber ?? 1,
      column: editor.getPosition()?.column ?? 1,
      codepage: load.result.codepage,
      detected: load.result.detected,
    });
    // `workbench` is deliberately left out: a draft change must not reload the model under the caret.
  }, [load, tabId, languageId, cobol, codepage, setStatus, refreshOutline]);

  /* ---------------------------------------------------------------- COPY expansion */

  // Declared after the model effect so that a tab switch has swapped the model before the zones of
  // the previous asset are taken down and the new one's are drawn.
  useCopyZones(editorRef, path, load.status === "ready");

  /* ---------------------------------------------------------------- read-only, reveal */

  useEffect(() => {
    editorRef.current?.updateOptions({ readOnly: !editable, domReadOnly: !editable });
  }, [editable]);

  useEffect(() => {
    if (line !== null && load.status === "ready") {
      // The caret first: a line inside a folded block is unfolded for the caret, then brought into view.
      editorRef.current?.setPosition({ lineNumber: line, column: 1 });
      editorRef.current?.revealLineInCenter(line);
    }
  }, [line, load.status, tabId]);

  /* ---------------------------------------------------------------- decorations */

  const modelText = load.status === "ready" ? load.result.text : "";

  useEffect(() => {
    const editor = editorRef.current;
    const model = editor?.getModel() ?? null;
    if (editor === null || model === null || areasRef.current === null) {
      return;
    }
    const monaco = monacoEditor();
    const lines = model.getLinesContent();
    areasRef.current.set(
      areaRanges(lines, boundaries, codepage).map((range) => ({
        range: new monaco.Range(range.line, range.startColumn, range.line, range.endColumn),
        // The text carries the area's classes; the band behind it is a box of its own in the layer
        // the selection is drawn in, so the text's ground stays the editor's.
        options: {
          inlineClassName: range.className,
          className: range.className
            .split(" ")
            .map((each) => `${each}-band`)
            .join(" "),
        },
      })),
    );
  }, [boundaries, codepage, modelText, tabId]);

  /* ---------------------------------------------------------------- markers */

  const findings = useMemo(
    () => [...artifactItems(project.findings), ...artifactItems(project.sqlFindings)],
    [project.findings, project.sqlFindings],
  );

  const sarif = useMemo(
    () => findingMarkers(findings, path, project.rules),
    [findings, path, project.rules],
  );

  useEffect(() => {
    applyMarkers(editorRef.current, MARKER_OWNER.sarif, sarif);
    const glyphs = glyphsRef.current;
    const editor = editorRef.current;
    if (glyphs === null || editor === null || editor.getModel() === null) {
      return;
    }
    const monaco = monacoEditor();
    glyphs.set(
      findings
        .filter((finding) => finding.file === path)
        .map((finding) => {
          const severity = ruleOf(project.rules, finding.ruleId, finding.level).severity;
          return {
            range: new monaco.Range(finding.startLine, 1, finding.startLine, 1),
            options: {
              glyphMarginClassName: glyphClassOf(severity),
              // The margin marks the severity by colour alone, which a reader who does not see colour
              // cannot read; the hover says which severity it is.
              glyphMarginHoverMessage: {
                value: `**${text.severity[severity]}** ${finding.message}`,
              },
            },
          };
        }),
    );
  }, [sarif, findings, path, project.rules, tabId, modelText]);

  useEffect(() => {
    applyMarkers(
      editorRef.current,
      MARKER_OWNER.save,
      reparseMarkers(
        project.saveFindings
          .filter((finding) => finding.file === path)
          .map((finding) => ({ line: finding.startLine, message: finding.message })),
      ),
    );
  }, [project.saveFindings, path, tabId]);

  useEffect(() => {
    const model = editorRef.current?.getModel() ?? null;
    if (model === null) {
      return;
    }
    applyMarkers(
      editorRef.current,
      MARKER_OWNER.format,
      overflowMarkers(model.getLinesContent(), codepage, text.sourceView.overflow),
    );
  }, [boundaries, codepage, tabId]);

  /* ---------------------------------------------------------------- the quick fix */

  useEffect(() => {
    const fixable = new Set(
      project.rules.entries.filter((rule) => rule.hasFix).map((rule) => rule.id),
    );
    setQuickFixTarget(fixable, () => onShowFix(path));
  }, [project.rules, path, onShowFix]);

  /* ---------------------------------------------------------------- the pane beside the code */

  useEffect(() => {
    const body = bodyRef.current;
    // jsdom has no ResizeObserver; with no width measured the pane stays out, as on a narrow window.
    if (body === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const observer = new ResizeObserver(() => setBodyWidth(body.clientWidth));
    observer.observe(body);
    setBodyWidth(body.clientWidth);
    return () => observer.disconnect();
  }, []);

  // The pane takes what the 80 columns leave. Where that is too little to read, the code keeps the row.
  const paneShown =
    cobol &&
    load.status === "ready" &&
    codeWidth !== null &&
    bodyWidth - codeWidth >= PANE_MIN_WIDTH;

  const chooseParagraph = useCallback(
    (next: { root?: string | null; shown?: string | null }): void => {
      workbenchDispatch({ type: "SET_PARAGRAPH", id: tabId, ...next });
    },
    [workbenchDispatch, tabId],
  );

  const revealLine = useCallback((target: number): void => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    editor.setPosition({ lineNumber: target, column: 1 });
    editor.revealLineInCenter(target);
    editor.focus();
  }, []);

  const openAsset = useCallback(
    (asset: string): void => {
      workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(asset) });
    },
    [workbenchDispatch],
  );

  /* ---------------------------------------------------------------- the view */

  return (
    <div className="ci-source" data-testid={`source-${path}`}>
      {/* The codepage lives in the status bar and the discard in the palette, as in VS Code; the
          only thing left above the body is the mark that says the file cannot be edited. While
          the decode is in progress the loading line below the body is what shows. */}
      {load.status !== "error" ? null : (
        <div className="ci-source__meta">
          <span className="ci-source__badge">{text.sourceView.readOnly}</span>
        </div>
      )}

      {load.status === "error" ? (
        <p className="ci-source__state ci-source__state--error" role="alert">
          {text.sourceView.error}
          <span className="ci-source__reason">{load.message}</span>
        </p>
      ) : null}

      <div
        className={`ci-source__body${paneShown ? "" : " ci-source__body--single"}`}
        ref={bodyRef}
        style={codeWidth === null ? undefined : ({ "--ci-code-width": `${codeWidth}px` } as CSSProperties)}
      >
        <div className="ci-source__code">
          {SCALED_LANGUAGES.has(languageId) && load.status === "ready" ? (
            <div className="ci-scale" aria-hidden="true">
              <span ref={scaleRef}>{COLS_LINE}</span>
            </div>
          ) : null}
          <div className="ci-code" ref={containerRef} data-testid="source-code" />
        </div>
        {paneShown && outline !== null ? (
          <ParagraphPane
            tabId={tabId}
            path={path}
            outline={outline}
            choice={paragraphChoiceOf(workbench, tabId)}
            onChoose={chooseParagraph}
            onReveal={revealLine}
            onOpenAsset={openAsset}
            selectionKey={namePresses}
          />
        ) : null}
      </div>
      {load.status === "loading" ? (
        <p className="ci-source__state">{text.sourceView.loading}</p>
      ) : null}
    </div>
  );
}

/** Sets one owner's markers, leaving the other owners' in place. */
function applyMarkers(
  editor: monacoApi.editor.IStandaloneCodeEditor | null,
  owner: string,
  markers: readonly EditorMarker[],
): void {
  const model = editor?.getModel() ?? null;
  if (model === null) {
    return;
  }
  monacoEditor().editor.setModelMarkers(model, owner, [...markers]);
}
