import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type * as monacoApi from "monaco-editor/editor/editor.api";
import type { DecodeResult } from "../../../../shared/ipc";
import { CODEPAGES } from "../../../../shared/codepage";
import { api, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { artifactItems, useProject, useProjectDispatch } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { draftOf, sourceTabId, useWorkbench, useWorkbenchDispatch } from "../../state/workbenchStore";
import { useSetEditorStatus } from "../../state/editorStatusStore";
import { openDocument, rememberDocument } from "../../model/openDocuments";
import { areaRanges, boundariesOf, editorCodepageOf, type EditorCodepage } from "../../model/columns";
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
import { CODE_FONT, languageIdFor } from "../../vendor/monarch";
import { existingModel, modelFor, resetModel } from "../../vendor/monacoModels";
import { useCopyZones } from "./copyZones";
import { registerQuickFix, setQuickFixTarget } from "./quickFix";

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
 * (model/columns), because asking the engine after every keystroke is not an option.
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
  const projectDispatch = useProjectDispatch();
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

  const tabId = sourceTabId(path);
  const baseDir = project.inputDir;
  const dbPath = project.dbPath;
  const rawOverride = project.codepageOverrides[path] ?? "";
  const requested = rawOverride === "" ? settings.defaultEncoding : rawOverride;

  const codepage: EditorCodepage = editorCodepageOf(
    load.status === "ready" ? load.result.codepage : requested,
  );
  const languageId = languageIdFor(path);
  const editable = load.status === "ready";

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
        rememberDocument(tabId, path, result);
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
   * are registered once, so what changes afterwards reaches them through this ref rather than by
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
    const editor = monaco.editor.create(container, {
      value: "",
      language: languageIdFor(""),
      ...CODE_FONT,
      automaticLayout: true,
      minimap: { enabled: false },
      // Five digits (99,999 lines) so the body's left edge does not move between assets.
      lineNumbersMinChars: 5,
      // The end of the sequence area, of the indicator, of area A, and of the body.
      rulers: [6, 7, 11, 72],
      renderLineHighlight: "none",
      scrollBeyondLastLine: false,
      wordWrap: "off",
      renderWhitespace: "none",
      occurrencesHighlight: "off",
      folding: false,
      glyphMargin: true,
      contextmenu: false,
      ariaLabel: text.sourceView.editor,
    });
    editorRef.current = editor;
    areasRef.current = editor.createDecorationsCollection([]);
    glyphsRef.current = editor.createDecorationsCollection([]);

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
      // stands — as decoded, or as last written — is not an edit.
      workbenchDispatch({
        type: "SET_DRAFT",
        id,
        draft: value === openDocument(id)?.text ? null : value,
      });
    });

    return () => {
      cursor.dispose();
      content.dispose();
      areasRef.current = null;
      glyphsRef.current = null;
      editorRef.current = null;
      // The models outlive the editor: they belong to the tabs, not to this instance.
      editor.setModel(null);
      editor.dispose();
      setStatus(null);
    };
    // The editor is created once. Everything that changes afterwards is applied by the effects below.
  }, [setStatus, workbenchDispatch]);

  /* ---------------------------------------------------------------- the model */

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) {
      return;
    }
    if (load.status !== "ready" || load.tabId !== tabId) {
      // Nothing to show for this tab: it is still being decoded, or its decode failed, or the
      // result on hand belongs to the tab that was here a moment ago. Another tab's model must not
      // stay under this tab's heading, where it would take this tab's markers and this tab's edits.
      if (activeTabRef.current !== tabId) {
        activeTabRef.current = "";
        editor.setModel(null);
      }
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
    editor.setModel(model);
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
  }, [load, tabId, languageId, codepage, setStatus]);

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
      editorRef.current?.revealLineInCenter(line);
      editorRef.current?.setPosition({ lineNumber: line, column: 1 });
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
        options: { inlineClassName: range.className },
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
        .map((finding) => ({
          range: new monaco.Range(finding.startLine, 1, finding.startLine, 1),
          options: {
            glyphMarginClassName: glyphClassOf(ruleOf(project.rules, finding.ruleId).severity),
            glyphMarginHoverMessage: { value: finding.message },
          },
        })),
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

  /* ---------------------------------------------------------------- the tab's own controls */

  const dirty = draftOf(workbench, tabId) !== null;

  const reopen = (charset: string): void => {
    projectDispatch({ type: "SET_CODEPAGE", path, charset });
  };

  const discard = (): void => {
    resetModel(tabId, openDocument(tabId)?.text ?? "");
    workbenchDispatch({ type: "SET_DRAFT", id: tabId, draft: null });
  };

  return (
    <div className="ci-source" data-testid={`source-${path}`}>
      <div className="ci-source__meta">
        <label className="ci-source__control">
          <span className="ci-source__control-label">{text.sourceView.reopenLabel}</span>
          <select
            className="ci-select"
            value={rawOverride}
            onChange={(event) => reopen(event.target.value)}
            data-testid="source-codepage"
          >
            <option value="">{text.sourceView.reopenAuto}</option>
            {CODEPAGES.map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </select>
        </label>
        {load.status === "ready" ? (
          <span>
            {text.sourceView.codepage} {load.result.codepage}
            {load.result.detected ? `（${text.sourceView.detected}）` : ""}
          </span>
        ) : null}
        {editable ? null : <span className="ci-source__badge">{text.sourceView.readOnly}</span>}
        <div className="ci-source__spacer" />
        {dirty ? (
          <button
            type="button"
            className="ci-button"
            onClick={discard}
            data-testid="source-discard"
          >
            {text.sourceView.discard}
          </button>
        ) : null}
      </div>

      {load.status === "error" ? (
        <p className="ci-source__state ci-source__state--error" role="alert">
          {text.sourceView.error}
          <span className="ci-source__reason">{load.message}</span>
        </p>
      ) : null}

      <div className="ci-code" ref={containerRef} data-testid="source-code" />
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
