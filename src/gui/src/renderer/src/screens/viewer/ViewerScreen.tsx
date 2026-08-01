import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactElement } from "react";
import type {
  AssetInventoryItem,
  CopyExpansion,
  LineMapEntry,
  SarifFinding,
  TranspileGeneratedFile,
} from "../../../../shared/engine-api";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SplitHandle } from "../../components/SplitHandle";
import { SPLIT_PANES } from "../../state/appState";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { GENERATED_LANGUAGE_ID } from "../../vendor/monacoLanguages";
import { previewCodepage } from "../explorer/assetView";
import { SCREEN_META } from "../screenMeta";
import { CodePane } from "./CodePane";
import { SourceInspector } from "./SourceInspector";
import { TranslationNotes } from "./TranslationNotes";
import { ViewerToolbar } from "./ViewerToolbar";
import { useSourceDocument } from "./useSourceDocument";
import { sourceCodepageOf } from "./columns";
import { detectCopyStatements } from "./copybookLookup";
import {
  buildLineMapIndex,
  linkFromCobolLine,
  linkFromGeneratedLine,
  notedCobolLines,
  notedGeneratedLines,
  translationNotes,
  type LinkedLines,
  type TranslationNote,
} from "./lineMapIndex";
import {
  LANGUAGE_TABS,
  availableGeneratedLanguages,
  buildExpansionZones,
  copyExpansionFile,
  copyExpansionSummary,
  expansionsFor,
  findingLinesOf,
  generatedFilesFor,
  generatedLanguageOf,
  identificationRanges,
  isTranspileTarget,
  linkSummary,
  originScreen,
  selectGeneratedFile,
  transpileOutDir,
  viewerFileOptions,
  type CopyExpansionState,
  type EditorMetrics,
  type ExpansionZone,
  type TranspileState,
} from "./viewerModel";

/** 実行中に提示する段(design scSrc の副見出し)。 */
const VIEWER_RUN_STAGES = ["解析と対訳生成の完了後に、ハイライトと逐語対訳を表示する"];

/**
 * 未取得のときに使う空の値。参照を固定して useMemo と useEffect の依存を安定させる
 * (毎回 [] を作ると依存が変わり続け、効果が止まらなくなる)。
 */
const EMPTY_FILES: readonly TranspileGeneratedFile[] = [];
const EMPTY_LINE_MAP: readonly LineMapEntry[] = [];
const EMPTY_INVENTORY: readonly AssetInventoryItem[] = [];
const EMPTY_ZONES: readonly ExpansionZone[] = [];
const EMPTY_FINDINGS: readonly SarifFinding[] = [];
const IDLE_TRANSPILE: TranspileState = { status: "idle" };
const IDLE_EXPANSION: CopyExpansionState = { status: "idle" };

/** 相互ハイライトの起点。どちらのペインから引いたかで、反対側のペインだけをスクロールする。 */
type LinkOrigin = "cobol" | "generated" | "note" | null;

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 展開が参照するコピー句の原本を読む。engine が解決した位置(資産フォルダからの相対パス、
 * 資産フォルダの外にあるコピー句は絶対パス)をそのまま当て、境界検査の基準として資産フォルダと
 * コピー句検索パスを順に試す。読めなかったコピー句と復号非対応のコピー句は載せない
 * (注記行を補わず、engine の展開のまま空行として示す)。
 */
async function loadCopybookLines(
  expansions: readonly CopyExpansion[],
  bases: readonly string[],
  codepageOf: (path: string) => string | null,
): Promise<Map<string, readonly string[]>> {
  const lines = new Map<string, readonly string[]>();
  for (const path of new Set(expansions.map((expansion) => expansion.copybookPath))) {
    for (const base of bases) {
      try {
        const result = await window.cobolInsight.readSourceText({
          inputDir: base,
          path,
          codepage: codepageOf(path),
        });
        if (!result.unsupported) {
          lines.set(path, result.text.split(/\r\n|\n|\r/));
        }
        break;
      } catch {
        // この基準では読めなかった。次の探索先を当てる。
      }
    }
  }
  return lines;
}

/**
 * ソースビューア(translate 統合)。左に COBOL 原本、右に逐語対訳の生成物を並べ、LINE_MAP の
 * 行範囲対応で相互ハイライトする。原本の側には lint の指摘を重ね、重大度の記号と色で示す。
 * COPY 文は、取り込まれる行を原本の COPY 文の直後へ差し込んで示す。
 * 本文は main の readSourceText、対訳は translate の生成物と LINE_MAP、指摘は SARIF、COPY 展開は
 * scan の対応表が供給源であり、GUI は解析も復号も展開も行わない。
 *
 * 4状態は実状態から導く。empty=解析未実行、running=解析実行中、results=本文と対訳を表示、
 * error=解析の部分的失敗(表示できる資産はそのまま表示する)である。本文の読取失敗・復号非対応・
 * 対訳の未生成は、いずれも空の表示に潰さずそれぞれの理由を示す。
 */
export function ViewerScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const meta = SCREEN_META.viewer;
  const { inputDir, dbPath, copybookPaths } = state.project;
  const analyzed = state.mode === "results" || state.mode === "error";
  const inventory = state.inventory.status === "ready" ? state.inventory.items : EMPTY_INVENTORY;
  const sourceFile = state.sourceFile;

  const options = useMemo(() => viewerFileOptions(inventory), [inventory]);
  const selectedItem = useMemo(
    () => inventory.find((item) => item.path === sourceFile) ?? null,
    [inventory, sourceFile],
  );
  const codepage =
    selectedItem === null
      ? null
      : previewCodepage(selectedItem, state.encodingSel, state.defaultEncoding);
  const transpileTarget = isTranspileTarget(selectedItem);

  const document = useSourceDocument(analyzed, inputDir, sourceFile, codepage);
  const [transpile, setTranspile] = useState<TranspileState>(IDLE_TRANSPILE);
  const [generatedName, setGeneratedName] = useState<string | null>(null);
  const [expansion, setExpansion] = useState<CopyExpansionState>(IDLE_EXPANSION);
  const [origin, setOrigin] = useState<LinkOrigin>(null);
  const [metrics, setMetrics] = useState<EditorMetrics | null>(null);
  // 対応表が空の資産に対して translate を繰り返し起動しないよう、起動済みの組を覚える。
  const generatedOnce = useRef<Set<string>>(new Set());

  // 対訳の成果物を読む。対応表が無ければ translate を起動して作り、読み直す。
  useEffect(() => {
    if (!analyzed || inputDir === null || dbPath === null || sourceFile === "" || !transpileTarget) {
      setTranspile(IDLE_TRANSPILE);
      return;
    }
    let current = true;
    const outDir = transpileOutDir(dbPath);
    const key = `${outDir}|${sourceFile}`;
    setTranspile({ status: "loading" });
    void (async () => {
      try {
        let artifacts = await window.cobolInsight.readTranspileArtifacts({
          outDir,
          dbPath,
          cobolRelPath: sourceFile,
        });
        if (artifacts.lineMap.length === 0 && !generatedOnce.current.has(key)) {
          generatedOnce.current.add(key);
          await window.cobolInsight.runTranspile({
            inputDir,
            copybookPaths,
            db: dbPath,
            language: "both",
            outDir,
          });
          artifacts = await window.cobolInsight.readTranspileArtifacts({
            outDir,
            dbPath,
            cobolRelPath: sourceFile,
          });
        }
        if (current) {
          setTranspile({ status: "ready", files: artifacts.files, lineMap: artifacts.lineMap });
        }
      } catch (error) {
        if (current) setTranspile({ status: "error", message: messageOf(error) });
      }
    })();
    return () => {
      current = false;
    };
  }, [analyzed, inputDir, dbPath, sourceFile, transpileTarget, copybookPaths]);

  const files = transpile.status === "ready" ? transpile.files : EMPTY_FILES;
  const lineMap = transpile.status === "ready" ? transpile.lineMap : EMPTY_LINE_MAP;
  const language = generatedLanguageOf(state.sourceLang);
  const languageFiles = useMemo(() => generatedFilesFor(files, language), [files, language]);
  const generated = useMemo(
    () => selectGeneratedFile(files, language, generatedName),
    [files, language, generatedName],
  );
  const generatedLanguages = useMemo(() => availableGeneratedLanguages(files), [files]);
  const index = useMemo(() => buildLineMapIndex(lineMap, generated?.name ?? ""), [lineMap, generated]);
  const notes = useMemo(() => translationNotes(index), [index]);
  const notedCobol = useMemo(() => notedCobolLines(index), [index]);
  const notedGenerated = useMemo(() => notedGeneratedLines(index), [index]);

  const sourceText = document.status === "ready" ? document.text : "";
  // 桁はバイトで数えるため、復号に使った文字集合が必要である(DBCS 混在行で桁がずれる)。
  const sourceCodepage = document.status === "ready" ? sourceCodepageOf(document.codepage) : "UTF-8";
  const identification = useMemo(
    () => identificationRanges(sourceText, sourceCodepage),
    [sourceText, sourceCodepage],
  );
  const copyStatements = useMemo(
    () => detectCopyStatements(sourceText, sourceCodepage),
    [sourceText, sourceCodepage],
  );
  const findings = state.findings.status === "ready" ? state.findings.items : EMPTY_FINDINGS;
  const findingLines = useMemo(() => findingLinesOf(findings, sourceFile), [findings, sourceFile]);

  // 対応表を得た時点で、ジャンプ先の行(無ければ先頭行)の対応を強調しておく。
  useEffect(() => {
    const linked = linkFromCobolLine(index, state.sourceLine ?? 1);
    setOrigin(null);
    dispatch({
      type: "SET_LINKED_LINES",
      cobolLines: [...linked.cobolLines],
      transpileLines: [...linked.generatedLines],
    });
  }, [index, state.sourceLine, dispatch]);

  // 展開中は COPY 展開の対応表と、注記行を補うためのコピー句の原本を読む。閉じているあいだは読まない。
  useEffect(() => {
    if (
      !state.copybookOpen ||
      copyStatements.length === 0 ||
      inputDir === null ||
      dbPath === null ||
      sourceFile === ""
    ) {
      setExpansion(IDLE_EXPANSION);
      return;
    }
    let current = true;
    setExpansion({ status: "loading" });
    void (async () => {
      try {
        const data = await window.cobolInsight.readCopyExpansion(copyExpansionFile(dbPath));
        const expansions = expansionsFor(data, sourceFile);
        const codepageByPath = new Map(inventory.map((item) => [item.path, item.codepage]));
        const copybookLines = await loadCopybookLines(
          expansions,
          [inputDir, ...copybookPaths],
          (path) => codepageByPath.get(path) ?? codepage,
        );
        if (current) setExpansion({ status: "ready", expansions, copybookLines });
      } catch (error) {
        if (current) setExpansion({ status: "error", message: messageOf(error) });
      }
    })();
    return () => {
      current = false;
    };
  }, [
    state.copybookOpen,
    copyStatements,
    inputDir,
    dbPath,
    sourceFile,
    copybookPaths,
    inventory,
    codepage,
  ]);

  const expansionZones = useMemo(
    () =>
      expansion.status === "ready"
        ? buildExpansionZones({
            statements: copyStatements,
            expansions: expansion.expansions,
            copybookLines: expansion.copybookLines,
            codepage: sourceCodepage,
          })
        : EMPTY_ZONES,
    [expansion, copyStatements, sourceCodepage],
  );

  /**
   * 対訳を作り直す。過去に別の出力先で生成した対応表が DB に残っている場合(生成物だけが無い状態)と、
   * 自動生成を1度試したあとの再試行の両方で使う。
   */
  async function regenerate(): Promise<void> {
    if (inputDir === null || dbPath === null || sourceFile === "") {
      return;
    }
    const outDir = transpileOutDir(dbPath);
    generatedOnce.current.add(`${outDir}|${sourceFile}`);
    setTranspile({ status: "loading" });
    try {
      await window.cobolInsight.runTranspile({
        inputDir,
        copybookPaths,
        db: dbPath,
        language: "both",
        outDir,
      });
      const artifacts = await window.cobolInsight.readTranspileArtifacts({
        outDir,
        dbPath,
        cobolRelPath: sourceFile,
      });
      setTranspile({ status: "ready", files: artifacts.files, lineMap: artifacts.lineMap });
    } catch (error) {
      setTranspile({ status: "error", message: messageOf(error) });
    }
  }

  function applyLinked(linked: LinkedLines, from: LinkOrigin): void {
    setOrigin(from);
    dispatch({
      type: "SET_LINKED_LINES",
      cobolLines: [...linked.cobolLines],
      transpileLines: [...linked.generatedLines],
    });
  }

  function onCobolCursor(line: number): void {
    applyLinked(linkFromCobolLine(index, line), "cobol");
  }

  function onGeneratedCursor(line: number): void {
    applyLinked(linkFromGeneratedLine(index, line), "generated");
  }

  function onSelectNote(note: TranslationNote): void {
    applyLinked(linkFromCobolLine(index, note.cobol.start), "note");
  }

  if (state.mode === "empty") {
    return (
      <EmptyState
        title="解析結果がありません"
        description="資産を取り込んで解析を実行すると、COBOL 原本と逐語対訳を左右に並べて表示する。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }
  if (state.mode === "running") {
    return <RunningIndicator title={meta.runningTitle} stages={VIEWER_RUN_STAGES} />;
  }
  if (inputDir === null) {
    return (
      <EmptyState
        title="資産フォルダが選ばれていません"
        description="資産一覧で資産フォルダを取り込むと、ソースを表示できる。"
        actionLabel="資産一覧へ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }

  const back = originScreen(state.sourceFrom);
  const findingCount = findingLines.reduce((total, line) => total + line.count, 0);
  const linkedCobol = state.linkedCobolLines;
  const linkedGenerated = state.linkedTranspileLines;
  const cobolReveal = origin === "generated" || origin === "note" ? (linkedCobol[0] ?? null) : null;
  const generatedReveal = origin === "cobol" || origin === "note" ? (linkedGenerated[0] ?? null) : null;
  const translationWidth = state.paneWidths.viewerTranslation;
  // 対訳ペインの幅と、原本へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  // 最小は SPLIT_PANES の oppositeMin をそのまま流し、上限の値を CSS 側の定数として二重に持たない。
  const paneStyle = {
    "--ci-viewer-translation-w": `${translationWidth}px`,
    "--ci-opposite-min": `${SPLIT_PANES.viewerTranslation.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-viewer" style={paneStyle}>
      <ViewerToolbar
        file={sourceFile}
        options={options}
        onFileChange={(file) => dispatch({ type: "SET_SOURCE_FILE", file })}
        from={state.sourceFrom}
        onBack={back === null ? null : () => dispatch({ type: "NAV", screen: back })}
        backLabel={back === null ? null : SCREEN_META[back].label}
        codeFocus={state.codeFocus}
        onToggleCodeFocus={() => dispatch({ type: "TOGGLE_CODE_FOCUS" })}
      />
      {sourceFile === "" ? (
        <EmptyState
          title="資産が選択されていません"
          description="上の資産選択でソースを開く。呼出関係図・指摘一覧・SQL指摘の「該当行へジャンプ」からも開く。"
        />
      ) : (
        <div className="ci-viewer__panes">
          <SourceInspector
            file={sourceFile}
            document={document}
            findingCount={findingCount}
            metrics={metrics}
            onMetrics={setMetrics}
            findings={findingLines}
            identification={identification}
            focusLine={state.sourceLine}
            revealLine={cobolReveal}
            linkedLines={linkedCobol}
            notedLines={notedCobol}
            expansions={expansionZones}
            onCursorLine={onCobolCursor}
            status={linkSummary(linkedCobol, linkedGenerated)}
          >
            {copyStatements.length === 0 ? (
              <span className="ci-viewer__pane-meta">COPY 文なし</span>
            ) : (
              <>
                <Button
                  className="ci-viewer__copy-toggle"
                  aria-expanded={state.copybookOpen}
                  onClick={() => dispatch({ type: "TOGGLE_COPYBOOK_OPEN" })}
                >
                  {state.copybookOpen ? "コピー句の展開を閉じる" : "コピー句を展開"}
                </Button>
                <span className="ci-viewer__pane-meta" role="status">
                  {copyExpansionSummary(copyStatements, expansion)}
                </span>
              </>
            )}
          </SourceInspector>

          {/* 最大化しているあいだは対訳ペインをハンドルごと出さない。幅は畳む前の値を保つ。 */}
          {state.codeFocus ? null : (
            <>
              <SplitHandle
                size={translationWidth}
                min={SPLIT_PANES.viewerTranslation.min}
                oppositeMin={SPLIT_PANES.viewerTranslation.oppositeMin}
                onSizeChange={(width) =>
                  dispatch({ type: "SET_PANE_WIDTH", pane: "viewerTranslation", width })
                }
                onCommit={() => dispatch({ type: "COMMIT_PANE_SIZE" })}
                ariaLabel="逐語対訳ペインの幅"
              />

              <section
                className="ci-viewer__pane ci-viewer__pane--translation"
                aria-label="逐語対訳"
              >
                <header className="ci-viewer__pane-head">
                  <h3 className="ci-viewer__pane-title">逐語対訳</h3>
                  {LANGUAGE_TABS.map((tab) => (
                    <Button
                      key={tab.lang}
                      variant={state.sourceLang === tab.lang ? "primary" : "default"}
                      aria-pressed={state.sourceLang === tab.lang}
                      onClick={() => dispatch({ type: "SET_SOURCE_LANG", lang: tab.lang })}
                    >
                      {tab.label}
                    </Button>
                  ))}
                  {languageFiles.length > 1 ? (
                    <select
                      className="ci-viewer__gen-select"
                      aria-label="表示する生成物"
                      value={generated?.name ?? ""}
                      onChange={(event) => setGeneratedName(event.target.value)}
                    >
                      {languageFiles.map((file) => (
                        <option key={file.name} value={file.name}>
                          {file.name}
                        </option>
                      ))}
                    </select>
                  ) : null}
                  <span className="ci-viewer__pane-meta">
                    行の対応は多対一・一対多 ― カーソル行で相互ハイライト
                  </span>
                </header>
                <div className="ci-viewer__pane-body">
                  {!transpileTarget ? (
                    <EmptyState
                      title="この資産は逐語対訳の対象ではありません"
                      description="逐語対訳は COBOL 本体に対して生成する。JCL・コピー句・BMS マップは対訳を持たない。"
                    />
                  ) : dbPath === null ? (
                    <EmptyState
                      title="解析結果のプロジェクトファイルがありません"
                      description="資産一覧で解析を実行すると、対訳の対応表を持つプロジェクトファイルができる。"
                    />
                  ) : transpile.status === "loading" ? (
                    <p className="ci-viewer__loading" role="status">
                      逐語対訳を生成・読込している…
                    </p>
                  ) : transpile.status === "error" ? (
                    <EmptyState
                      icon="！"
                      title="逐語対訳を取得できませんでした"
                      description={`逐語対訳の生成または読み取りに失敗した。${transpile.message}`}
                    />
                  ) : generated === null && generatedLanguages.length > 0 ? (
                    <EmptyState
                      title="この言語の生成物がありません"
                      description="選んだ言語の生成物が無い。もう一方の言語へ切り替えると表示できる。"
                    />
                  ) : generated === null && lineMap.length > 0 ? (
                    <EmptyState
                      icon="！"
                      title="生成物が出力先に見つかりません"
                      description={`行の対応表はあるが、対応する生成物が出力先 ${transpileOutDir(dbPath)} に無い。別の出力先で生成した対応表がプロジェクトファイルに残っている。`}
                      actionLabel="逐語対訳を再生成する"
                      onAction={() => void regenerate()}
                    />
                  ) : generated === null ? (
                    <EmptyState
                      title="逐語対訳が生成されていません"
                      description="この資産の逐語対訳は生成されていない。行の対応表に対応が無いため、対訳を表示できない。"
                      actionLabel="逐語対訳を再生成する"
                      onAction={() => void regenerate()}
                    />
                  ) : (
                    <CodePane
                      languageId={GENERATED_LANGUAGE_ID[language]}
                      text={generated.text}
                      linkedLines={linkedGenerated}
                      notedLines={notedGenerated}
                      focusLine={null}
                      revealLine={generatedReveal}
                      onCursorLine={onGeneratedCursor}
                      ariaLabel={`逐語対訳 ${generated.name}`}
                    />
                  )}
                </div>
                <TranslationNotes notes={notes} onSelect={onSelectNote} />
              </section>
            </>
          )}
        </div>
      )}
    </div>
  );
}
