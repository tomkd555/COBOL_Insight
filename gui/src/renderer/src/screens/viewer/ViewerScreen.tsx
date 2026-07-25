import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import type {
  AssetInventoryItem,
  LineMapEntry,
  TranspileGeneratedFile,
} from "../../../../shared/engine-api";
import { Button } from "../../components/Button";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { GENERATED_LANGUAGE_ID } from "../../vendor/monacoLanguages";
import { previewCodepage } from "../explorer/assetView";
import { SCREEN_META } from "../screenMeta";
import { CodePane } from "./CodePane";
import { CopybookExpansion, type CopybookBody, type CopybookEntry } from "./CopybookExpansion";
import { TranslationNotes } from "./TranslationNotes";
import { ViewerToolbar } from "./ViewerToolbar";
import { COBOL_LANGUAGE_ID } from "./cobolMonarch";
import { sourceCodepageOf } from "./columns";
import {
  copybookCandidates,
  detectCopyStatements,
  type CopybookLookupOptions,
} from "./copybookLookup";
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
  COBOL_RULERS,
  LANGUAGE_TABS,
  availableGeneratedLanguages,
  generatedFilesFor,
  generatedLanguageOf,
  identificationRanges,
  isTranspileTarget,
  linkSummary,
  originScreen,
  selectGeneratedFile,
  toDocument,
  transpileOutDir,
  viewerFileOptions,
  type DocumentState,
  type TranspileState,
} from "./viewerModel";

/** 実行中に提示する段(design:373 の副見出し)。 */
const VIEWER_RUN_STAGES = ["解析と対訳生成の完了後に、ハイライトと逐語対訳を表示する"];

/**
 * 未取得のときに使う空の値。参照を固定して useMemo と useEffect の依存を安定させる
 * (毎回 [] を作ると依存が変わり続け、効果が止まらなくなる)。
 */
const EMPTY_FILES: readonly TranspileGeneratedFile[] = [];
const EMPTY_LINE_MAP: readonly LineMapEntry[] = [];
const EMPTY_INVENTORY: readonly AssetInventoryItem[] = [];
const EMPTY_COPYBOOKS: readonly CopybookEntry[] = [];
const IDLE_DOCUMENT: DocumentState = { status: "idle" };
const IDLE_TRANSPILE: TranspileState = { status: "idle" };

/** 相互ハイライトの起点。どちらのペインから引いたかで、反対側のペインだけをスクロールする。 */
type LinkOrigin = "cobol" | "generated" | "note" | null;

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** 候補の表示名(探索した場所として示すパス)。 */
function candidateLabel(inputDir: string, path: string): string {
  return `${inputDir}\\${path}`;
}

/**
 * コピー句の本文を、探索先を順に当てて読む。読めた最初の候補を採り、どれも読めなければ
 * 探索した場所を添えて見つからない旨を返す。復号非対応は「見つかったが表示できない」として区別する。
 */
async function loadCopybookBody(
  name: string,
  options: CopybookLookupOptions,
  fallbackCodepage: string | null,
): Promise<CopybookBody> {
  const candidates = copybookCandidates(name, options);
  const codepageByPath = new Map(options.inventory.map((item) => [item.path, item.codepage]));
  const tried: string[] = [];
  for (const candidate of candidates) {
    const label = candidateLabel(candidate.inputDir, candidate.path);
    tried.push(label);
    const codepage =
      candidate.origin === "inventory"
        ? (codepageByPath.get(candidate.path) ?? fallbackCodepage)
        : fallbackCodepage;
    try {
      const result = await window.cobolInsight.readSourceText({
        inputDir: candidate.inputDir,
        path: candidate.path,
        codepage,
      });
      if (result.unsupported) {
        return { status: "unsupported", path: label, codepage: result.codepage };
      }
      return { status: "ready", path: label, text: result.text, codepage: result.codepage };
    } catch {
      // この候補では読めなかった。次の探索先を当てる。
    }
  }
  return { status: "missing", tried };
}

/**
 * ソースビューア(transpile 統合)。左に COBOL 原本、右に逐語対訳の生成物を並べ、LINE_MAP の
 * 行範囲対応で相互ハイライトする。本文は main の readSourceText、対訳は transpile の生成物と
 * LINE_MAP が供給源であり、GUI は解析も復号も行わない。
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

  const [document, setDocument] = useState<DocumentState>(IDLE_DOCUMENT);
  const [transpile, setTranspile] = useState<TranspileState>(IDLE_TRANSPILE);
  const [generatedName, setGeneratedName] = useState<string | null>(null);
  const [copybooks, setCopybooks] = useState<readonly CopybookEntry[]>(EMPTY_COPYBOOKS);
  const [origin, setOrigin] = useState<LinkOrigin>(null);
  // 対応表が空の資産に対して transpile を繰り返し起動しないよう、起動済みの組を覚える。
  const generatedOnce = useRef<Set<string>>(new Set());

  // 選択ファイルまたは文字コード指定が変わるたびに本文を取り直す。古い応答は捨てる。
  useEffect(() => {
    if (!analyzed || inputDir === null || sourceFile === "") {
      setDocument(IDLE_DOCUMENT);
      return;
    }
    let current = true;
    setDocument({ status: "loading" });
    window.cobolInsight
      .readSourceText({ inputDir, path: sourceFile, codepage })
      .then((result) => {
        if (current) setDocument(toDocument(result));
      })
      .catch((error: unknown) => {
        if (current) setDocument({ status: "error", message: messageOf(error) });
      });
    return () => {
      current = false;
    };
  }, [analyzed, inputDir, sourceFile, codepage]);

  // 対訳の成果物を読む。対応表が無ければ transpile を起動して作り、読み直す。
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

  // 展開中はコピー句の本文を読む。閉じているあいだは読まない。
  useEffect(() => {
    if (!state.copybookOpen || copyStatements.length === 0) {
      setCopybooks(EMPTY_COPYBOOKS);
      return;
    }
    let current = true;
    setCopybooks(copyStatements.map((statement) => ({ statement, body: { status: "loading" } })));
    void (async () => {
      const entries: CopybookEntry[] = [];
      for (const statement of copyStatements) {
        const body = await loadCopybookBody(
          statement.name,
          { inputDir, copybookPaths, inventory },
          codepage,
        );
        entries.push({ statement, body });
      }
      if (current) setCopybooks(entries);
    })();
    return () => {
      current = false;
    };
  }, [state.copybookOpen, copyStatements, inputDir, copybookPaths, inventory, codepage]);

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
        description="資産をインポートして解析を実行すると、COBOL 原本と逐語対訳を左右に並べて表示する。"
        actionLabel="資産エクスプローラーへ"
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
        description="資産エクスプローラーで資産フォルダをインポートすると、ソースを表示できる。"
        actionLabel="資産エクスプローラーへ"
        onAction={() => dispatch({ type: "NAV", screen: "explorer" })}
      />
    );
  }

  const back = originScreen(state.sourceFrom);
  const findingCount =
    state.findings.status === "ready"
      ? state.findings.items.filter((finding) => finding.file === sourceFile).length
      : 0;
  const linkedCobol = state.linkedCobolLines;
  const linkedGenerated = state.linkedTranspileLines;
  const cobolReveal = origin === "generated" || origin === "note" ? (linkedCobol[0] ?? null) : null;
  const generatedReveal = origin === "cobol" || origin === "note" ? (linkedGenerated[0] ?? null) : null;

  return (
    <div className="ci-viewer">
      <ViewerToolbar
        file={sourceFile}
        options={options}
        onFileChange={(file) => dispatch({ type: "SET_SOURCE_FILE", file })}
        from={state.sourceFrom}
        onBack={back === null ? null : () => dispatch({ type: "NAV", screen: back })}
        backLabel={back === null ? null : SCREEN_META[back].label}
      />
      {sourceFile === "" ? (
        <EmptyState
          title="ファイルが選択されていません"
          description="この画面は通常、呼出関係図・指摘一覧・SQL助言からの「該当行へジャンプ」で開く。上のファイル選択からも表示できる。"
        />
      ) : (
        <div className="ci-viewer__panes">
          <section className="ci-viewer__pane" aria-label="COBOL ソース">
            <header className="ci-viewer__pane-head">
              <h2 className="ci-viewer__pane-title">{`COBOL ソース（固定形式 80 桁）― ${sourceFile}`}</h2>
              <span className="ci-viewer__pane-meta">
                {`この資産の指摘: ${findingCount} 件 ｜ 文字コード: ${
                  document.status === "ready" || document.status === "unsupported"
                    ? document.codepage
                    : "―"
                }`}
              </span>
            </header>
            <div className="ci-viewer__columns">
              <span className="ci-viewer__column ci-viewer__column--sequence">1-6 一連番号欄</span>
              <span className="ci-viewer__column ci-viewer__column--indicator">7 標識</span>
              <span className="ci-viewer__column ci-viewer__column--body">
                8 ── 本体（A/B 領域） ── 72
              </span>
              <span className="ci-viewer__column ci-viewer__column--identification">73-80 識別欄</span>
            </div>
            <div className="ci-viewer__pane-body">
              {document.status === "loading" ? (
                <p className="ci-viewer__loading" role="status">
                  ソース本文を読み込んでいる…
                </p>
              ) : document.status === "unsupported" ? (
                <EmptyState
                  icon="！"
                  title="このコードページは表示できません"
                  description={`${document.codepage} は表示用の復号に対応していない（EBCDIC CP930/CP939 とコードページ不明）。資産エクスプローラーで文字コードを手動指定すると表示できる場合がある。`}
                />
              ) : document.status === "error" ? (
                <EmptyState
                  icon="！"
                  title="ソースを読み取れませんでした"
                  description={document.message}
                />
              ) : document.status === "ready" ? (
                <CodePane
                  languageId={COBOL_LANGUAGE_ID}
                  text={document.text}
                  rulers={COBOL_RULERS}
                  linkedLines={linkedCobol}
                  notedLines={notedCobol}
                  focusLine={state.sourceLine}
                  revealLine={cobolReveal}
                  identification={identification}
                  onCursorLine={onCobolCursor}
                  ariaLabel={`COBOL 原本 ${sourceFile}`}
                />
              ) : null}
            </div>
            <p className="ci-viewer__status" role="status">
              {linkSummary(linkedCobol, linkedGenerated)}
            </p>
            <CopybookExpansion
              statements={copyStatements}
              open={state.copybookOpen}
              onToggle={() => dispatch({ type: "TOGGLE_COPYBOOK_OPEN" })}
              entries={copybooks}
            />
          </section>

          <section className="ci-viewer__pane" aria-label="逐語対訳">
            <header className="ci-viewer__pane-head">
              <h2 className="ci-viewer__pane-title">逐語対訳</h2>
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
                  description="資産エクスプローラーで解析を実行すると、対訳の対応表を持つプロジェクトファイルができる。"
                />
              ) : transpile.status === "loading" ? (
                <p className="ci-viewer__loading" role="status">
                  逐語対訳を生成・読込している…
                </p>
              ) : transpile.status === "error" ? (
                <EmptyState
                  icon="！"
                  title="逐語対訳を取得できませんでした"
                  description={`transpile の実行または生成物の読取に失敗した。${transpile.message}`}
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
                  description={`対応表(LINE_MAP)はあるが、対応する生成物が出力先 ${transpileOutDir(dbPath)} に無い。別の出力先で生成した対応表がプロジェクトファイルに残っている。`}
                  actionLabel="逐語対訳を再生成する"
                  onAction={() => void regenerate()}
                />
              ) : generated === null ? (
                <EmptyState
                  title="逐語対訳が生成されていません"
                  description="この資産の逐語対訳は生成されていない。対応表(LINE_MAP)に対応が無いため、対訳を表示できない。"
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
        </div>
      )}
    </div>
  );
}
