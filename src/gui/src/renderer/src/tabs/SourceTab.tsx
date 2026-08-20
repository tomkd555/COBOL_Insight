import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactElement,
} from "react";
import type {
  AssetInventoryItem,
  LineMapEntry,
  TranspileGeneratedFile,
} from "../../../shared/engine-api";
import { Button } from "../components/Button";
import { EmptyState } from "../components/EmptyState";
import { SplitHandle } from "../components/SplitHandle";
import { CodePane } from "../screens/viewer/CodePane";
import { COBOL_LANGUAGE_ID } from "../screens/viewer/cobolMonarch";
import { sourceCodepageOf } from "../screens/viewer/columns";
import { detectCopyStatements } from "../screens/viewer/copybookLookup";
import {
  buildLineMapIndex,
  linkFromCobolLine,
  linkFromGeneratedLine,
  notedCobolLines,
  notedGeneratedLines,
  translationNotes,
  type LinkedLines,
  type TranslationNote,
} from "../screens/viewer/lineMapIndex";
import { useSourceDocument } from "../screens/viewer/useSourceDocument";
import {
  COBOL_RULERS,
  COLUMN_MARKS,
  availableGeneratedLanguages,
  buildExpansionZones,
  columnLeftPx,
  copyExpansionSummary,
  findingLinesOf,
  generatedFilesFor,
  generatedLanguageOf,
  identificationRanges,
  isTranspileTarget,
  linkSummary,
  selectGeneratedFile,
  type EditorMetrics,
  type ExpansionZone,
  type FindingLine,
  type SourceLang,
} from "../screens/viewer/viewerModel";
import { messageOf } from "../services/analysis";
import { MANUAL_ENCODING_OPTIONS, charsetOf } from "../data/encodings";
import { artifactItems, useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings } from "../state/settingsStore";
import {
  draftOf,
  sourceTabId,
  useWorkbench,
  useWorkbenchDispatch,
} from "../state/workbenchStore";
import { TranslationPane } from "./TranslationPane";
import { reparseMarkers, saveOutcomeOf } from "./saveModel";
import { useCopyExpansion } from "./useCopyExpansion";
import { useTranspile } from "./useTranspile";

/** 未指定のときに使う空の値。参照を固定して useMemo と useEffect の依存を安定させる。 */
const NO_LINES: readonly number[] = [];
const NO_ZONES: readonly ExpansionZone[] = [];
const NO_FILES: readonly TranspileGeneratedFile[] = [];
const NO_LINE_MAP: readonly LineMapEntry[] = [];

/** 文字コードを engine の判定へ委ねる選択肢。手動指定の語彙は設定の画面と同じものを使う。 */
const AUTO_CODEPAGE = "自動";
const CODEPAGE_OPTIONS: readonly string[] = [AUTO_CODEPAGE, ...MANUAL_ENCODING_OPTIONS];

/** 対訳ペインの寸法。min はペインが役目を果たす最小、oppositeMin は原本へ必ず残す最小である。 */
const TRANSLATION_LIMITS = { initial: 460, min: 320, oppositeMin: 520 } as const;

export interface SourceTabProps {
  /** 資産の相対パス。 */
  path: string;
  /** 開いた直後に見せる行。 */
  line: number | null;
  /** カーソル位置が変わったときに呼ぶ。ステータスバーが受け取る。 */
  onCursor: (line: number, column: number) => void;
}

/**
 * 資産1件を編集する面。本文の復号と書き戻しは engine が担い、この面は固定形式の桁ルーラ・識別欄の
 * 弱め・指摘の記号・COPY 展開の差し込みを重ね、Ctrl+S で書き戻しを起こす。
 *
 * 編集中の本文は作業面の store が持つ。選んでいないタブは描かないため、この面が持つとタブを
 * 切り替えるだけで編集が消える。
 */
export function SourceTab({ path, line, onCursor }: SourceTabProps): ReactElement {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const settings = useSettings();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();

  const tabId = sourceTabId(path);
  const inventory = artifactItems(project.inventory);
  const item = useMemo(
    () => inventory.find((candidate) => candidate.path === path) ?? null,
    [inventory, path],
  );
  /**
   * 直前まで分かっていたこの資産の情報。解析を始めると資産一覧はいったん空になるため、そこで
   * 文字コードを見失うと本文を読み直す羽目になり、編集中の面が読み込み中の表示へ差し替わる。
   */
  const lastItem = useRef<AssetInventoryItem | null>(null);
  if (item !== null) {
    lastItem.current = item;
  }
  const known = item ?? lastItem.current;

  const [reloadKey, setReloadKey] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [metrics, setMetrics] = useState<EditorMetrics | null>(null);
  const [copybookOpen, setCopybookOpen] = useState(false);
  const [translationOpen, setTranslationOpen] = useState(false);
  const [translationWidth, setTranslationWidth] = useState<number>(TRANSLATION_LIMITS.initial);
  const [lang, setLang] = useState<SourceLang>("py");
  const [generatedName, setGeneratedName] = useState<string | null>(null);
  const [linked, setLinked] = useState<LinkedLines>({
    cobolLines: [],
    generatedLines: [],
    links: [],
  });
  /** 相互ハイライトの起点。反対側のペインだけをスクロールするために持つ。 */
  const [origin, setOrigin] = useState<"cobol" | "generated" | "note" | null>(null);

  /**
   * 選択欄に出す文字コード。手動指定があればそれ、無ければ判定に委ねる。判定できなかった資産では
   * 設定の既定値を初期値にする(設定の画面がそう約束している)。判定できている資産を既定値で
   * 塗り替えることはしない。
   */
  const detectedCodepage = known?.codepage ?? null;
  const codepageChoice =
    project.codepageOverrides[path] ??
    (detectedCodepage === null ? settings.defaultEncoding : AUTO_CODEPAGE);
  /** 選んだ文字コード。自動のときは null になり、判定の値で読む。 */
  const chosenCharset = charsetOf(codepageChoice);
  const codepage = chosenCharset ?? detectedCodepage;

  const document = useSourceDocument(true, project.inputDir, path, codepage, reloadKey);
  const documentText = document.status === "ready" ? document.text : "";
  const draft = draftOf(workbench, tabId);
  const text = draft ?? documentText;
  const dirty = draft !== null;
  /** いま画面にある編集後の本文。保存の間に打った文字を、保存の完了で捨てないために持つ。 */
  const draftRef = useRef<string | null>(draft);
  draftRef.current = draft;

  const sourceCodepage =
    document.status === "ready" ? sourceCodepageOf(document.codepage) : "UTF-8";
  const identification = useMemo(
    () => identificationRanges(text, sourceCodepage),
    [text, sourceCodepage],
  );
  // COPY 文は engine が最後に読んだ本文から数える。展開の対応表は走査時の行番号を指しており、
  // 打鍵のたびに数え直すと、対応表とコピー句の原本を読み直す往復が打鍵ごとに走る。
  const copyStatements = useMemo(
    () => detectCopyStatements(documentText, sourceCodepage),
    [documentText, sourceCodepage],
  );

  const findings: readonly FindingLine[] = useMemo(() => {
    if (!project.catalog.loaded) return [];
    return findingLinesOf(
      project.catalog,
      [...artifactItems(project.findings), ...artifactItems(project.sqlAdvice)],
      path,
    );
  }, [project.catalog, project.findings, project.sqlAdvice, path]);
  const findingCount = findings.reduce((total, entry) => total + entry.count, 0);

  const markers = useMemo(
    () => reparseMarkers(project.saveFindings.filter((finding) => finding.file === path)),
    [project.saveFindings, path],
  );

  const transpileTarget = isTranspileTarget(known);
  const { state: transpile, regenerate } = useTranspile(
    translationOpen && transpileTarget,
    project.inputDir,
    project.dbPath,
    path,
    settings.copybookPaths,
  );
  const files = transpile.status === "ready" ? transpile.files : NO_FILES;
  const lineMap = transpile.status === "ready" ? transpile.lineMap : NO_LINE_MAP;
  const language = generatedLanguageOf(lang);
  const languageFiles = useMemo(() => generatedFilesFor(files, language), [files, language]);
  const generated = useMemo(
    () => selectGeneratedFile(files, language, generatedName),
    [files, language, generatedName],
  );
  const generatedLanguages = useMemo(() => availableGeneratedLanguages(files), [files]);
  const index = useMemo(
    () => buildLineMapIndex(lineMap, generated?.name ?? ""),
    [lineMap, generated],
  );
  const notes = useMemo(() => translationNotes(index), [index]);
  const notedCobol = useMemo(() => notedCobolLines(index), [index]);
  const notedGenerated = useMemo(() => notedGeneratedLines(index), [index]);

  // 対応表を得た時点で、開いた行(無ければ先頭行)の対応を強調しておく。
  useEffect(() => {
    setOrigin(null);
    setLinked(linkFromCobolLine(index, line ?? 1));
  }, [index, line]);

  const expansion = useCopyExpansion(
    copybookOpen,
    project.inputDir,
    project.dbPath,
    path,
    copyStatements,
    inventory,
    settings.copybookPaths,
    codepage,
  );
  const expansionZones = useMemo(
    () =>
      expansion.status === "ready"
        ? buildExpansionZones({
            statements: copyStatements,
            expansions: expansion.expansions,
            copybookLines: expansion.copybookLines,
            codepage: sourceCodepage,
          })
        : NO_ZONES,
    [expansion, copyStatements, sourceCodepage],
  );

  const save = useCallback(async (): Promise<void> => {
    const inputDir = project.inputDir;
    // 書き戻すのは、この時点の本文である。保存の間に打った文字は次の保存の対象になる。
    const snapshot = draft;
    if (snapshot === null || inputDir === null || saving) {
      return;
    }
    setSaving(true);
    try {
      const result = await window.cobolInsight.saveSource({
        inputDir,
        path,
        editedText: snapshot,
        copybookPaths: [...settings.copybookPaths],
        // 画面が選んでいる文字コードを渡す。自動のときは渡さず、engine が走査時の記録と
        // 自動判別で決める。
        ...(chosenCharset === null ? {} : { codepage: chosenCharset }),
      });
      const outcome = saveOutcomeOf(path, known?.type ?? "UNKNOWN", result);
      projectDispatch({
        type: "LOG",
        text: outcome.message,
        failed: outcome.kind === "failed",
      });
      if (outcome.kind === "failed") {
        // 原本は変わっていない。編集を保ったまま理由だけを示す。
        setSaveError(outcome.message);
        return;
      }
      setSaveError(null);
      projectDispatch({ type: "SET_SAVE_FINDINGS", path, findings: outcome.diagnostics });
      // 保存の間に打った文字は捨てない。書き戻した本文のままのときだけ未保存の印を外し、engine が
      // 原本の改行様式や桁へそろえた結果を画面の本文へ戻す。続きを打っていたら、その編集を残す。
      if (draftRef.current === snapshot) {
        workbenchDispatch({ type: "SET_DRAFT", id: tabId, text: null });
        workbenchDispatch({ type: "SAVED", path, at: Date.now() });
        setReloadKey((count) => count + 1);
      }
    } catch (error) {
      const message = `${path} を保存できませんでした。${messageOf(error)} もう一度保存してください。`;
      setSaveError(message);
      projectDispatch({ type: "LOG", text: message, failed: true });
    } finally {
      setSaving(false);
    }
  }, [draft, saving, project.inputDir, path, known, chosenCharset, settings.copybookPaths, projectDispatch, workbenchDispatch, tabId]);

  // Ctrl+S で書き戻す。選んでいるタブだけが描かれるため、対象は常にこの資産である。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      if (!event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.key !== "s" && event.key !== "S") return;
      event.preventDefault();
      void save();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [save]);

  function applyLinked(next: LinkedLines, from: "cobol" | "generated" | "note"): void {
    setOrigin(from);
    setLinked(next);
  }

  function onCobolCursor(cursorLine: number, column: number): void {
    onCursor(cursorLine, column);
    if (translationOpen) {
      applyLinked(linkFromCobolLine(index, cursorLine), "cobol");
    }
  }

  function onSelectNote(note: TranslationNote): void {
    applyLinked(linkFromCobolLine(index, note.cobol.start), "note");
  }

  if (document.status === "idle" || document.status === "loading") {
    return <p className="ci-editor__note">{path} を読み込んでいます。</p>;
  }

  if (document.status === "unsupported") {
    return (
      <EmptyState
        icon="⚠"
        title="この文字コードは画面で表示できません"
        description={`${path} は ${document.codepage} と判定されています。EBCDIC の資産は解析エンジンが読み取ります。`}
      />
    );
  }

  if (document.status === "error") {
    return (
      <div className="ci-banner ci-banner--error" role="alert">
        {path} を読み取れませんでした。{document.message} 文字コードの指定を確かめて、開き直してください。
      </div>
    );
  }

  const cobolReveal =
    origin === "generated" || origin === "note" ? (linked.cobolLines[0] ?? null) : null;
  const generatedReveal =
    origin === "cobol" || origin === "note" ? (linked.generatedLines[0] ?? null) : null;
  // 対訳ペインの幅と、原本へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  const paneStyle = {
    "--ci-viewer-translation-w": `${translationWidth}px`,
    "--ci-opposite-min": `${TRANSLATION_LIMITS.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-source" style={paneStyle}>
      <div className="ci-source__toolbar">
        <Button
          variant="primary"
          disabled={!dirty || saving}
          title="Ctrl+S"
          onClick={() => void save()}
        >
          {saving ? "保存しています…" : "保存"}
        </Button>
        <span className="ci-source__state" role="status">
          {dirty ? "未保存の変更があります" : "保存済みです"}
        </span>
        <label className="ci-source__codepage">
          文字コード
          <select
            className="ci-source__codepage-select"
            aria-label="文字コード"
            value={codepageChoice}
            onChange={(event) =>
              projectDispatch({ type: "SET_CODEPAGE", path, charset: event.target.value })
            }
          >
            {CODEPAGE_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        {copyStatements.length === 0 ? null : (
          <Button
            aria-expanded={copybookOpen}
            onClick={() => setCopybookOpen((open) => !open)}
          >
            {copybookOpen ? "コピー句の展開を閉じる" : "コピー句を展開"}
          </Button>
        )}
        <Button
          aria-pressed={translationOpen}
          data-testid="toggle-translation"
          onClick={() => setTranslationOpen((open) => !open)}
        >
          {translationOpen ? "逐語対訳を閉じる" : "逐語対訳を開く"}
        </Button>
        <span className="ci-viewer__pane-spacer" />
        <span className="ci-viewer__pane-meta">
          {`${document.codepage} で表示 ｜ この資産の指摘: ${findingCount} 件`}
        </span>
      </div>

      {saveError === null ? null : (
        <div className="ci-banner ci-banner--error" role="alert">
          {saveError}
        </div>
      )}
      {document.truncated ? (
        <p className="ci-editor__note">
          先頭の一部だけを読んでいます。この本文は編集できません。
        </p>
      ) : null}

      <div className="ci-viewer__panes">
        <section className="ci-viewer__pane" aria-label="原本">
          <header className="ci-viewer__pane-head">
            <h3 className="ci-viewer__pane-title">{`固定形式 80 桁 ― ${path}`}</h3>
            {copyStatements.length === 0 ? null : (
              <span className="ci-viewer__pane-meta" role="status">
                {copyExpansionSummary(copyStatements, expansion)}
              </span>
            )}
            <span className="ci-viewer__pane-spacer" />
            {translationOpen ? (
              <span className="ci-viewer__pane-meta ci-viewer__pane-status" role="status">
                {linkSummary(linked.cobolLines, linked.generatedLines)}
              </span>
            ) : null}
          </header>
          {/* 見出しの位置は Monaco の実測寸法から求める。測る前(本文の表示前)はラベルを出さない。 */}
          <div
            className="ci-viewer__columns"
            role="img"
            aria-label="固定形式の欄割り ― 1〜6桁 一連番号欄、7桁目 標識欄、8〜72桁 本体（A/B 領域）、73〜80桁 識別欄"
          >
            {metrics === null
              ? null
              : COLUMN_MARKS.map((mark) => (
                  <span
                    key={mark.name}
                    className={`ci-viewer__column ci-viewer__column--${mark.name} ci-viewer__column--${mark.anchor}`}
                    style={{ left: `${columnLeftPx(metrics, mark.column)}px` }}
                  >
                    {mark.label}
                  </span>
                ))}
          </div>
          <div className="ci-viewer__pane-body">
            <CodePane
              languageId={COBOL_LANGUAGE_ID}
              text={text}
              rulers={COBOL_RULERS}
              linkedLines={translationOpen ? linked.cobolLines : NO_LINES}
              notedLines={translationOpen ? notedCobol : NO_LINES}
              focusLine={line}
              revealLine={cobolReveal}
              identification={identification}
              findings={findings}
              expansions={expansionZones}
              markers={markers}
              glyphMargin
              onMetrics={setMetrics}
              onCursorLine={onCobolCursor}
              // 途中までしか読めていない本文を書き戻すと、残りを切り落とす。読み取り専用にする。
              onChange={
                document.truncated
                  ? undefined
                  : (value) =>
                      workbenchDispatch({
                        type: "SET_DRAFT",
                        id: tabId,
                        text: value === documentText ? null : value,
                      })
              }
              ariaLabel={`${path} の本文`}
            />
          </div>
        </section>

        {translationOpen ? (
          <>
            <SplitHandle
              size={translationWidth}
              min={TRANSLATION_LIMITS.min}
              oppositeMin={TRANSLATION_LIMITS.oppositeMin}
              ariaLabel="逐語対訳ペインの幅"
              onSizeChange={setTranslationWidth}
            />
            <TranslationPane
              lang={lang}
              onLangChange={setLang}
              language={language}
              transpile={transpile}
              target={transpileTarget}
              dbPath={project.dbPath}
              generated={generated}
              languageFiles={languageFiles}
              onGeneratedNameChange={setGeneratedName}
              generatedLanguages={generatedLanguages}
              lineMapCount={lineMap.length}
              linkedLines={linked.generatedLines}
              notedLines={notedGenerated}
              revealLine={generatedReveal}
              onCursorLine={(cursorLine) =>
                applyLinked(linkFromGeneratedLine(index, cursorLine), "generated")
              }
              notes={notes}
              onSelectNote={onSelectNote}
              onRegenerate={() => void regenerate()}
            />
          </>
        ) : null}
      </div>
    </div>
  );
}
