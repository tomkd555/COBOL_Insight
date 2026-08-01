import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ViewerScreen } from "./ViewerScreen";
import { AppStateProvider, useAppState } from "../../state/AppStateContext";
import { SPLIT_PANES, initialState, type AppState } from "../../state/appState";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";
import {
  SAMPLE_COBOL_TEXT,
  SAMPLE_COPYBOOK_TEXT,
  SAMPLE_COPY_EXPANSION,
  SAMPLE_GENERATED_FILES,
  SAMPLE_LINE_MAP,
} from "./fixtures";
import type {
  CobolInsightApi,
  EngineResult,
  SarifFinding,
  SourceTextRequest,
  SourceTextResult,
} from "../../../../shared/engine-api";

/** 偽の Monaco エディタ1台の観測結果。 */
interface FakeDecoration {
  range: { startLineNumber: number };
  options: {
    className?: string;
    inlineClassName?: string;
    glyphMarginClassName?: string;
    hoverMessage?: { value: string };
    after?: { content: string };
  };
}

/** 差し込んだビューゾーン1件(COPY 展開)。 */
interface FakeViewZone {
  id: string;
  afterLineNumber: number;
  heightInLines: number;
  domNode: HTMLElement;
  marginDomNode: HTMLElement;
}

interface FakeEditor {
  language: string;
  ariaLabel: string;
  rulers: number[];
  glyphMargin: boolean;
  value: string;
  decorations: FakeDecoration[];
  zones: FakeViewZone[];
  revealed: number[];
  disposed: boolean;
  cursorHandler: ((event: { position: { lineNumber: number } }) => void) | null;
}

/**
 * Monaco は Worker と実 DOM 計測を要するため jsdom では動かない。描画ライブラリの入口
 * (vendor/monacoEditor・vendor/monacoLanguages)を差し替え、生成したエディタへ渡った本文・言語・
 * 桁ルーラ・装飾・スクロール指示を記録する偽物にする。これにより CodePane の効果を実際に走らせた
 * まま「どの行が強調されたか」「カーソル行の変化が画面へ伝わるか」を検証できる。
 */
const monacoStore = vi.hoisted(() => ({ editors: [] as FakeEditor[] }));

vi.mock("../../vendor/monacoEditor", () => ({
  monacoEditor: () => ({
    languages: {
      register: () => undefined,
      setMonarchTokensProvider: () => undefined,
      setLanguageConfiguration: () => undefined,
    },
    editor: {
      // 実測寸法の取得に使う列挙(monaco.editor.EditorOption)。値は本物と同じである必要がない。
      EditorOption: { fontInfo: "fontInfo" },
      defineTheme: () => undefined,
      create: (
        _container: HTMLElement,
        options: {
          value?: string;
          language?: string;
          rulers?: number[];
          ariaLabel?: string;
          glyphMargin?: boolean;
        },
      ) => {
        const editor: FakeEditor = {
          language: options.language ?? "",
          ariaLabel: options.ariaLabel ?? "",
          rulers: options.rulers ?? [],
          glyphMargin: options.glyphMargin ?? false,
          value: options.value ?? "",
          decorations: [],
          zones: [],
          revealed: [],
          disposed: false,
          cursorHandler: null,
        };
        monacoStore.editors.push(editor);
        let nextZoneId = 0;
        return {
          getValue: () => editor.value,
          setValue: (value: string) => {
            editor.value = value;
          },
          createDecorationsCollection: (initial: FakeEditor["decorations"]) => {
            editor.decorations = initial;
            return {
              set: (next: FakeEditor["decorations"]) => {
                editor.decorations = next;
              },
            };
          },
          onDidChangeCursorPosition: (handler: (event: { position: { lineNumber: number } }) => void) => {
            editor.cursorHandler = handler;
            return {
              dispose: () => {
                editor.cursorHandler = null;
              },
            };
          },
          // ビューゾーン(COPY 展開の差し込み)。追加・削除を記録し、行番号は消費しない。
          changeViewZones: (
            change: (accessor: {
              addZone: (zone: Omit<FakeViewZone, "id">) => string;
              removeZone: (id: string) => void;
            }) => void,
          ) => {
            change({
              addZone: (zone) => {
                const id = `zone-${(nextZoneId += 1)}`;
                editor.zones.push({ ...zone, id });
                return id;
              },
              removeZone: (id: string) => {
                editor.zones = editor.zones.filter((zone) => zone.id !== id);
              },
            });
          },
          // 桁見出しの位置合わせと展開行の字送りに使う実測寸法。jsdom では実寸を測れないため固定値を返す。
          getLayoutInfo: () => ({ contentLeft: 60 }),
          getOption: () => ({
            typicalHalfwidthCharacterWidth: 7,
            fontFamily: "'BIZ UDGothic',monospace",
            fontSize: 12,
            lineHeight: 19,
          }),
          getScrollLeft: () => 0,
          onDidLayoutChange: () => ({ dispose: () => undefined }),
          onDidScrollChange: () => ({ dispose: () => undefined }),
          revealLineInCenter: (line: number) => {
            editor.revealed.push(line);
          },
          dispose: () => {
            editor.disposed = true;
          },
        };
      },
    },
  }),
}));

vi.mock("../../vendor/monacoLanguages", () => ({
  GENERATED_LANGUAGE_ID: { python: "python", java: "java" },
  registerGeneratedLanguages: () => undefined,
}));

const INPUT_DIR = "C:\\資産\\SYK";
const DB_PATH = "C:\\proj\\cobol-insight.db";
const COBOL = "cobol/SYK001.cbl";

function textResult(text: string, overrides: Partial<SourceTextResult> = {}): SourceTextResult {
  return { text, codepage: "UTF-8", truncated: false, unsupported: false, ...overrides };
}

function transpileResult(): EngineResult {
  return {
    subcommand: "transpile",
    exitCode: 0,
    summary: null,
    stdout: "",
    stderr: "",
    outputs: { outDir: "C:\\proj\\transpile" },
  };
}

let readSourceText: ReturnType<typeof vi.fn>;
let readTranspileArtifacts: ReturnType<typeof vi.fn>;
let readCopyExpansion: ReturnType<typeof vi.fn>;
let runTranspile: ReturnType<typeof vi.fn>;

beforeEach(() => {
  monacoStore.editors = [];
  readSourceText = vi.fn((request: SourceTextRequest) => {
    if (request.path === COBOL) {
      return Promise.resolve(textResult(SAMPLE_COBOL_TEXT));
    }
    if (request.path.includes("SYKCPY1")) {
      return Promise.resolve(textResult(SAMPLE_COPYBOOK_TEXT));
    }
    return Promise.reject(new Error(`見つからない: ${request.path}`));
  });
  readTranspileArtifacts = vi
    .fn()
    .mockResolvedValue({ files: SAMPLE_GENERATED_FILES, lineMap: SAMPLE_LINE_MAP });
  readCopyExpansion = vi.fn().mockResolvedValue(SAMPLE_COPY_EXPANSION);
  runTranspile = vi.fn().mockResolvedValue(transpileResult());
  window.cobolInsight = {
    readSourceText,
    readTranspileArtifacts,
    readCopyExpansion,
    runTranspile,
  } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** 解析実行が済んだ状態(mode=results・資産一覧あり・COBOL を選択)。 */
function analyzedState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    mode: "results",
    screen: "viewer",
    project: { inputDir: INPUT_DIR, dbPath: DB_PATH, copybookPaths: ["D:\\共通コピー句"] },
    inventory: { status: "ready", items: SAMPLE_INVENTORY },
    sourceFile: COBOL,
    ...overrides,
  };
}

/** 現在の画面と強調行を観測できる器。 */
function Harness(): ReactElement {
  const state = useAppState();
  return (
    <>
      <p data-testid="screen">{state.screen}</p>
      <p data-testid="linked-cobol">{state.linkedCobolLines.join(",")}</p>
      <p data-testid="linked-generated">{state.linkedTranspileLines.join(",")}</p>
      <ViewerScreen />
    </>
  );
}

function renderViewer(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <Harness />
    </AppStateProvider>,
  );
}

/** 生存しているエディタのうち、指定した言語のもの。 */
function editorOf(language: string): FakeEditor {
  const editor = monacoStore.editors.filter((entry) => !entry.disposed && entry.language === language).at(-1);
  if (editor === undefined) {
    throw new Error(`${language} のエディタが無い`);
  }
  return editor;
}

/** 対訳ペインの表示完了を待つ。 */
async function waitForPanes(): Promise<void> {
  await waitFor(() => expect(editorOf("cobol-fixed").value).toContain("IDENTIFICATION"));
  await waitFor(() => expect(editorOf("python").value).toContain("main_proc"));
}

/** カーソル行の移動を模す。 */
function moveCursor(language: string, line: number): void {
  const handler = editorOf(language).cursorHandler;
  if (handler === null) {
    throw new Error(`${language} のカーソル通知が無い`);
  }
  act(() => {
    handler({ position: { lineNumber: line } });
  });
}

/** 装飾から、指定したクラスの付いた行番号を取り出す。 */
function decoratedLines(language: string, className: string): number[] {
  return editorOf(language)
    .decorations.filter((decoration) => decoration.options.className === className)
    .map((decoration) => decoration.range.startLineNumber);
}

describe("ViewerScreen(ソースビューア)の4状態", () => {
  it("解析未実行では誘導を出し、ソースを読まない", () => {
    renderViewer();
    expect(screen.getByRole("region", { name: "解析結果がありません" })).toBeInTheDocument();
    expect(readSourceText).not.toHaveBeenCalled();
    expect(readTranspileArtifacts).not.toHaveBeenCalled();
  });

  it("解析実行中はソースの準備中として示す", () => {
    renderViewer({ ...initialState, mode: "running", screen: "viewer" });
    expect(screen.getByRole("status")).toHaveTextContent("ソースを準備しています…");
    expect(readSourceText).not.toHaveBeenCalled();
  });

  it("資産フォルダが未確定なら資産エクスプローラーへ誘導する", () => {
    renderViewer(analyzedState({ project: { inputDir: null, dbPath: null, copybookPaths: [] } }));
    expect(screen.getByRole("region", { name: "資産フォルダが選ばれていません" })).toBeInTheDocument();
  });

  it("ファイル未選択ではジャンプで開く画面である旨を示し、選択肢は出す", () => {
    renderViewer(analyzedState({ sourceFile: "" }));
    expect(screen.getByRole("region", { name: "ファイルが選択されていません" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "表示するファイル" })).toBeInTheDocument();
    expect(readSourceText).not.toHaveBeenCalled();
  });

  it("解析済みなら本文と対訳を読み、左右のペインへ載せる", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    expect(readSourceText).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      path: COBOL,
      // SAMPLE_INVENTORY の検出コードページは engine の charset 名(windows-31j)である。
      codepage: "windows-31j",
    });
    expect(readTranspileArtifacts).toHaveBeenCalledWith({
      outDir: "C:\\proj\\transpile",
      dbPath: DB_PATH,
      cobolRelPath: COBOL,
    });
    expect(editorOf("cobol-fixed").rulers).toEqual([6, 7, 11, 72, 80]);
    expect(runTranspile).not.toHaveBeenCalled();
  });

  it("本文の読取失敗は空表示に潰さず、理由を示す", async () => {
    readSourceText.mockRejectedValue(new Error("読取権限がない"));
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", { name: "ソースを読み取れませんでした" });
    expect(region).toHaveTextContent("読取権限がない");
  });

  it("復号非対応のコードページはその旨をコードページ付きで示す", async () => {
    readSourceText.mockResolvedValue(
      textResult("", { codepage: "x-IBM930", unsupported: true }),
    );
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", { name: "このコードページは表示できません" });
    expect(region).toHaveTextContent("x-IBM930");
  });

  it("対訳の取得失敗は理由を示し、ソース側の表示は保つ", async () => {
    readTranspileArtifacts.mockRejectedValue(new Error("LINE_MAP を読めない"));
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", { name: "逐語対訳を取得できませんでした" });
    expect(region).toHaveTextContent("LINE_MAP を読めない");
    expect(editorOf("cobol-fixed").value).toContain("IDENTIFICATION");
  });
});

describe("ViewerScreen の逐語対訳", () => {
  it("対応表が空なら transpile を起動して生成し、読み直す", async () => {
    readTranspileArtifacts
      .mockResolvedValueOnce({ files: [], lineMap: [] })
      .mockResolvedValue({ files: SAMPLE_GENERATED_FILES, lineMap: SAMPLE_LINE_MAP });
    renderViewer(analyzedState());
    await waitForPanes();
    expect(runTranspile).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      copybookPaths: ["D:\\共通コピー句"],
      db: DB_PATH,
      language: "both",
      outDir: "C:\\proj\\transpile",
    });
    expect(readTranspileArtifacts).toHaveBeenCalledTimes(2);
  });

  it("生成し直しても対応が無ければ、対訳を表示できない旨を示す", async () => {
    readTranspileArtifacts.mockResolvedValue({ files: [], lineMap: [] });
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", { name: "逐語対訳が生成されていません" });
    expect(region).toHaveTextContent("行の対応表に対応が無い");
    expect(runTranspile).toHaveBeenCalledTimes(1);
  });

  it("対応表はあるのに生成物が無い場合は、対応表が無い場合と区別して示す", async () => {
    readTranspileArtifacts.mockResolvedValue({ files: [], lineMap: SAMPLE_LINE_MAP });
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", {
      name: "生成物が出力先に見つかりません",
    });
    expect(region).toHaveTextContent("C:\\proj\\transpile");
    expect(region).toHaveTextContent("行の対応表はある");
    // 対応表がある状態では自動で生成し直さない(利用者の操作で作り直す)。
    expect(runTranspile).not.toHaveBeenCalled();
  });

  it("生成物が無い状態から、再生成の操作で対訳を作り直して表示する", async () => {
    readTranspileArtifacts
      .mockResolvedValueOnce({ files: [], lineMap: SAMPLE_LINE_MAP })
      .mockResolvedValue({ files: SAMPLE_GENERATED_FILES, lineMap: SAMPLE_LINE_MAP });
    renderViewer(analyzedState());
    fireEvent.click(await screen.findByRole("button", { name: "逐語対訳を再生成する" }));
    await waitForPanes();
    expect(runTranspile).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      copybookPaths: ["D:\\共通コピー句"],
      db: DB_PATH,
      language: "both",
      outDir: "C:\\proj\\transpile",
    });
  });

  it("対応表が無い状態でも、再生成の操作で作り直せる", async () => {
    readTranspileArtifacts.mockResolvedValue({ files: [], lineMap: [] });
    renderViewer(analyzedState());
    await screen.findByRole("region", { name: "逐語対訳が生成されていません" });
    expect(runTranspile).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "逐語対訳を再生成する" }));
    await waitFor(() => expect(runTranspile).toHaveBeenCalledTimes(2));
  });

  it("言語を切り替えると Java の生成物へ差し替える", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "Java" }));
    await waitFor(() => expect(editorOf("java").value).toContain("class Syk001"));
    expect(screen.getByRole("button", { name: "Java" })).toHaveAttribute("aria-pressed", "true");
  });

  it("同じ言語に生成物が複数あれば選択できる", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const select = screen.getByRole("combobox", { name: "表示する生成物" });
    expect(select).toHaveValue("syk001.py");
    fireEvent.change(select, { target: { value: "syk001_record.py" } });
    await waitFor(() => expect(editorOf("python").value).toContain("Syk1Rec"));
  });

  it("逐語対訳の対象でない資産では対象外である旨を示し、transpile を起動しない", async () => {
    renderViewer(analyzedState({ sourceFile: "jcl/SYKD010.jcl" }));
    expect(
      await screen.findByRole("region", { name: "この資産は逐語対訳の対象ではありません" }),
    ).toBeInTheDocument();
    expect(readTranspileArtifacts).not.toHaveBeenCalled();
    expect(runTranspile).not.toHaveBeenCalled();
  });
});

describe("ViewerScreen の相互ハイライト", () => {
  it("COBOL 側のカーソル行から、対応する生成行を強調する", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    moveCursor("cobol-fixed", 12);
    await waitFor(() => expect(screen.getByTestId("linked-generated")).toHaveTextContent("15,16,17,18"));
    expect(screen.getByTestId("linked-cobol")).toHaveTextContent("12");
    expect(decoratedLines("python", "ci-code__line--linked")).toEqual([15, 16, 17, 18]);
  });

  it("生成側のカーソル行から、対応する COBOL 行を強調する", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    moveCursor("python", 16);
    await waitFor(() => expect(screen.getByTestId("linked-cobol")).toHaveTextContent("12"));
    expect(decoratedLines("cobol-fixed", "ci-code__line--linked")).toEqual([12]);
  });

  it("反対側のペインを対応行までスクロールする", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    moveCursor("cobol-fixed", 12);
    await waitFor(() => expect(editorOf("python").revealed).toContain(15));
  });

  it("対応が無い行ではその旨を示し、強調を外す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    moveCursor("cobol-fixed", 12);
    await waitFor(() => expect(screen.getByTestId("linked-generated")).toHaveTextContent("15"));
    moveCursor("cobol-fixed", 4);
    await waitFor(() =>
      expect(screen.getByText(/カーソル行に対応する行はない/)).toBeInTheDocument(),
    );
    expect(screen.getByTestId("linked-generated")).toHaveTextContent("");
  });

  it("注記のある行を両ペインで印付けし、注記を一覧に出す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const notes = screen.getByRole("region", { name: "直訳不能の注記" });
    expect(notes).toHaveTextContent("直訳不能の注記 1 件");
    expect(notes).toHaveTextContent("GO TO は直訳できないため");
    expect(notes).toHaveTextContent("1対多");
    expect(decoratedLines("cobol-fixed", "ci-code__line--noted")).toEqual([12]);
    expect(decoratedLines("python", "ci-code__line--noted")).toEqual([15, 16, 17, 18]);
  });

  it("注記の一覧から、その対応行へ強調を移す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "COBOL 12 行 → 生成 15〜18 行" }));
    await waitFor(() => expect(screen.getByTestId("linked-cobol")).toHaveTextContent("12"));
    expect(screen.getByTestId("linked-generated")).toHaveTextContent("15,16,17,18");
  });

  it("識別欄(73桁以降)を本体と区別して装飾する", async () => {
    const long = `${"A".repeat(72)}ID000010`;
    readSourceText.mockResolvedValue(textResult(`${SAMPLE_COBOL_TEXT}\n${long}`));
    renderViewer(analyzedState());
    await waitForPanes();
    const identification = editorOf("cobol-fixed").decorations.filter(
      (decoration) => decoration.options.inlineClassName === "ci-code__identification",
    );
    expect(identification).toHaveLength(1);
    expect(identification[0].range.startLineNumber).toBe(13);
  });
});

describe("ViewerScreen の指摘ハイライト", () => {
  /** 表示中の資産に 11 行目(高 1 件)・12 行目(中と警告の 2 件)、別資産に 1 件の指摘を置く。 */
  const FINDINGS: readonly SarifFinding[] = [
    { ruleId: "R001", level: "error", message: "未初期化の WS-検証金額 を参照している。", file: COBOL, startLine: 11, startColumn: 1 },
    { ruleId: "R009", level: "warning", message: "GO TO 文が構造化フローから外れる。", file: COBOL, startLine: 12, startColumn: 1 },
    { ruleId: "R008", level: "warning", message: "PERFORM が THRU 句を持たない。", file: COBOL, startLine: 12, startColumn: 12 },
    { ruleId: "R002", level: "note", message: "参照されない項目。", file: "cobol/SYK002.cbl", startLine: 3, startColumn: 1 },
  ];

  function withFindings(): AppState {
    return analyzedState({ findings: { status: "ready", items: FINDINGS } });
  }

  it("表示中の資産の指摘だけを、重大度別の行装飾として COBOL 側へ渡す", async () => {
    renderViewer(withFindings());
    await waitForPanes();
    expect(decoratedLines("cobol-fixed", "ci-code__line--finding-high")).toEqual([11]);
    expect(decoratedLines("cobol-fixed", "ci-code__line--finding-medium")).toEqual([12]);
    // 別の資産の指摘(SYK002 の 3 行目)は装飾しない。
    expect(decoratedLines("cobol-fixed", "ci-code__line--finding-low")).toEqual([]);
    // 対訳側は指摘の対象ではない。
    expect(decoratedLines("python", "ci-code__line--finding-high")).toEqual([]);
  });

  it("重大度の記号をグリフ余白へ出す(色に頼らない二重符号化)", async () => {
    renderViewer(withFindings());
    await waitForPanes();
    expect(editorOf("cobol-fixed").glyphMargin).toBe(true);
    const glyphs = editorOf("cobol-fixed").decorations.filter(
      (decoration) => decoration.options.glyphMarginClassName !== undefined,
    );
    expect(glyphs.map((decoration) => decoration.options.glyphMarginClassName)).toEqual([
      "ci-code__glyph ci-code__glyph--high",
      "ci-code__glyph ci-code__glyph--medium",
    ]);
  });

  it("ホバーでルール ID・ルール名・根拠が読め、複数件の行には件数を添える", async () => {
    renderViewer(withFindings());
    await waitForPanes();
    const multi = editorOf("cobol-fixed").decorations.find(
      (decoration) => decoration.range.startLineNumber === 12 && decoration.options.after !== undefined,
    );
    expect(multi?.options.after?.content).toBe(" ◆2");
    expect(multi?.options.hoverMessage?.value).toContain("この行の指摘 2 件");
    expect(multi?.options.hoverMessage?.value).toContain("R008");
    expect(multi?.options.hoverMessage?.value).toContain("PERFORM単独段落名の直接指定");
    expect(multi?.options.hoverMessage?.value).toContain("PERFORM が THRU 句を持たない。");
  });

  it("この資産の指摘の件数を見出しへ出す", async () => {
    renderViewer(withFindings());
    await waitForPanes();
    expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent(
      "この資産の指摘: 3 件",
    );
  });

  it("凡例に重大度 4 段を記号付きで並べる", async () => {
    renderViewer(withFindings());
    await waitForPanes();
    const legend = screen.getByRole("list", { name: "ハイライトの凡例" });
    expect(legend).toHaveTextContent("指摘のある行");
    expect(legend).toHaveTextContent("●高");
    expect(legend).toHaveTextContent("◆中");
    expect(legend).toHaveTextContent("■低");
    expect(legend).toHaveTextContent("▲警告");
  });

  it("指摘が無ければ指摘の装飾を出さない", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    expect(
      editorOf("cobol-fixed").decorations.filter(
        (decoration) => decoration.options.glyphMarginClassName !== undefined,
      ),
    ).toEqual([]);
  });
});

describe("ViewerScreen の桁見出し", () => {
  it("見出しを Monaco の実測寸法から求めた桁位置へ置く", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const columns = screen.getByRole("img", { name: /固定形式の欄割り/ });
    const marks = [...columns.querySelectorAll(".ci-viewer__column")];
    // 偽エディタは contentLeft=60・字送り 7px を返す。8桁目=60+49、73桁目=60+504 である。
    expect(marks.map((mark) => (mark as HTMLElement).style.left)).toEqual([
      "109px",
      "109px",
      "564px",
      "564px",
    ]);
    expect(marks.map((mark) => mark.textContent)).toEqual([
      "1-6 一連番号 / 7 標識",
      "8 本体（A/B 領域）",
      "72",
      "73-80 識別欄",
    ]);
  });
});

describe("ViewerScreen の見出し階層", () => {
  it("2 つのペインの題目を h3 とし、注記の小見出しを h4 に置く", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const paneTitles = screen.getAllByRole("heading", { level: 3 });
    expect(paneTitles.map((title) => title.textContent)).toEqual([
      expect.stringContaining("COBOL ソース（固定形式 80 桁）"),
      "逐語対訳",
    ]);
    expect(screen.getByRole("heading", { level: 4 })).toHaveTextContent("直訳不能の注記");
  });
});

describe("ViewerScreen のペイン幅", () => {
  /** 桁見出しの左位置(画素)。 */
  function columnLefts(): string[] {
    const columns = screen.getByRole("img", { name: /固定形式の欄割り/ });
    return [...columns.querySelectorAll(".ci-viewer__column")].map(
      (mark) => (mark as HTMLElement).style.left,
    );
  }

  /** 対訳ペインへ渡っている幅。 */
  function translationWidth(): string {
    const viewer = document.querySelector(".ci-viewer");
    if (viewer === null) {
      throw new Error("ソースビューアの枠が無い");
    }
    return (viewer as HTMLElement).style.getPropertyValue("--ci-viewer-translation-w");
  }

  it("原本と対訳の境界に分割ハンドルを置く", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const handle = screen.getByRole("separator", { name: "逐語対訳ペインの幅" });
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", String(SPLIT_PANES.viewerTranslation.initial));
    expect(handle).toHaveAttribute("aria-valuemin", String(SPLIT_PANES.viewerTranslation.min));
    expect(handle).toHaveAttribute("aria-valuemax", String(SPLIT_PANES.viewerTranslation.max));
    expect(translationWidth()).toBe(`${SPLIT_PANES.viewerTranslation.initial}px`);
  });

  it("End キーで対訳ペインを下限まで詰め、COBOL 原本を広げられる", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const handle = screen.getByRole("separator", { name: "逐語対訳ペインの幅" });
    fireEvent.keyDown(handle, { key: "Home" });
    expect(translationWidth()).toBe(`${SPLIT_PANES.viewerTranslation.min}px`);
  });

  it("ペイン幅を変えても桁見出しの位置は Monaco の実測寸法のままである", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const before = columnLefts();
    fireEvent.keyDown(screen.getByRole("separator", { name: "逐語対訳ペインの幅" }), {
      key: "ArrowRight",
    });
    await waitFor(() => expect(translationWidth()).not.toBe(`${SPLIT_PANES.viewerTranslation.initial}px`));
    expect(columnLefts()).toEqual(before);
  });

  it("ファイルを切り替えても幅を保つ", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.keyDown(screen.getByRole("separator", { name: "逐語対訳ペインの幅" }), { key: "End" });
    fireEvent.change(screen.getByLabelText("表示するファイル"), {
      target: { value: SAMPLE_INVENTORY[0].path },
    });
    await waitFor(() => expect(translationWidth()).toBe(`${SPLIT_PANES.viewerTranslation.max}px`));
  });
});

describe("ViewerScreen のジャンプ受領", () => {
  it("ジャンプ先の行を強調し、その行までスクロールする", async () => {
    renderViewer(
      analyzedState({
        sourceLine: 11,
        sourceFrom: "指摘一覧 から cobol/SYK001.cbl:11 へジャンプ",
      }),
    );
    await waitForPanes();
    expect(decoratedLines("cobol-fixed", "ci-code__line--focus")).toEqual([11]);
    expect(editorOf("cobol-fixed").revealed).toContain(11);
  });

  it("ジャンプ先の行の対応を、開いた時点で強調する", async () => {
    renderViewer(analyzedState({ sourceLine: 11 }));
    await waitForPanes();
    await waitFor(() => expect(screen.getByTestId("linked-generated")).toHaveTextContent("13"));
  });

  it("遷移元を示し、その画面へ戻る導線を出す", async () => {
    renderViewer(
      analyzedState({
        sourceLine: 11,
        sourceFrom: "指摘一覧 から cobol/SYK001.cbl:11 へジャンプ",
      }),
    );
    await waitForPanes();
    expect(screen.getByText("指摘一覧 から cobol/SYK001.cbl:11 へジャンプ")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "指摘一覧へ戻る" }));
    expect(screen.getByTestId("screen")).toHaveTextContent("findings");
  });

  it("ジャンプで開いていないときは戻り導線を出さない", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    expect(screen.queryByRole("button", { name: /へ戻る/ })).not.toBeInTheDocument();
  });

  it("ファイル選択を変えると、その資産の本文を読み直す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.change(screen.getByRole("combobox", { name: "表示するファイル" }), {
      target: { value: "cobol/SYK002.cbl" },
    });
    await waitFor(() =>
      expect(readSourceText).toHaveBeenLastCalledWith({
        inputDir: INPUT_DIR,
        path: "cobol/SYK002.cbl",
        codepage: "windows-31j",
      }),
    );
  });
});

describe("ViewerScreen のコピー句のインライン展開", () => {
  /** COBOL 側へ差し込まれたビューゾーンのうち先頭のもの。 */
  function firstZone(): FakeViewZone {
    const zone = editorOf("cobol-fixed").zones[0];
    if (zone === undefined) {
      throw new Error("差し込みが無い");
    }
    return zone;
  }

  /** 差し込みの本文(見出しを除く各行)。 */
  function zoneLines(zone: FakeViewZone): string[] {
    return [...zone.domNode.querySelectorAll(".ci-code__expansion-line")].map(
      (line) => line.textContent ?? "",
    );
  }

  /** 展開の切替を押す。 */
  async function openExpansion(): Promise<void> {
    fireEvent.click(screen.getByRole("button", { name: "コピー句を展開" }));
    await waitFor(() => expect(editorOf("cobol-fixed").zones).toHaveLength(1));
  }

  it("閉じているあいだは対応表を読まず、件数と展開の効き目を示す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent(
      "COPY 文 1 件 ― 展開すると取り込んだ行を COPY 文の位置へ差し込む",
    );
    expect(readCopyExpansion).not.toHaveBeenCalled();
    expect(editorOf("cobol-fixed").zones).toEqual([]);
  });

  it("展開すると COPY 文の直後へ、REPLACING 適用後の行を差し込む", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    expect(readCopyExpansion).toHaveBeenCalledWith("C:\\proj\\cobol-insight-copy-expansion.json");
    const zone = firstZone();
    expect(zone.afterLineNumber).toBe(7);
    // 見出しの2行と展開の4行。
    expect(zone.heightInLines).toBe(6);
    expect(zone.domNode.textContent).toContain("COPY SYKCPY1 の展開");
    expect(zone.domNode.textContent).toContain("copybook/SYKCPY1.cpy");
    expect(zoneLines(zone)[1]).toBe("       01  ORD1-REC.");
    expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent(
      "COPY 文 1 件のうち 1 件を展開中",
    );
  });

  it("展開行の出所を、コピー句名と行番号で示す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    const zone = firstZone();
    expect(zone.domNode.getAttribute("aria-label")).toBe("7 行の COPY SYKCPY1 の展開 4 行");
    // 行番号はコピー句の中での行番号であり、原本の行番号ガターの位置へ置く。
    expect(
      [...zone.marginDomNode.querySelectorAll(".ci-code__expansion-lineno")].map(
        (line) => line.textContent,
      ),
    ).toEqual([" ", " ", "1", "2", "3", "4"]);
  });

  it("差し込みは原本の本文を変えない(行番号の並びを崩さない)", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    expect(editorOf("cobol-fixed").value).toBe(SAMPLE_COBOL_TEXT);
  });

  it("前処理で空になった注記行を、原本から一連番号欄を除いて補う", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    expect(readSourceText).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      path: "copybook/SYKCPY1.cpy",
      codepage: "UTF-8",
    });
    expect(zoneLines(firstZone())[0]).toBe("      * 受注レコード");
    expect(firstZone().domNode.textContent).toContain("注記行は原本から補う");
  });

  it("原本のコピー句を読めないときは空行のまま示し、空に見える理由を添える", async () => {
    readSourceText.mockImplementation((request: SourceTextRequest) =>
      request.path === COBOL
        ? Promise.resolve(textResult(SAMPLE_COBOL_TEXT))
        : Promise.reject(new Error("ない")),
    );
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    expect(zoneLines(firstZone())[0]).toBe(" ");
    expect(firstZone().domNode.textContent).toContain("注記行は空のまま");
  });

  it("展開データが無い COPY 文には、対象外である旨だけを差し込む", async () => {
    readCopyExpansion.mockResolvedValue({ programs: [] });
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    const zone = firstZone();
    expect(zone.heightInLines).toBe(2);
    expect(zone.domNode.textContent).toContain("展開データが無い");
    expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent(
      "COPY 文 1 件のうち 0 件を展開中（1 件は展開データが無い）",
    );
  });

  it("対応表を取得できなければ理由を示し、差し込まずにソース表示を保つ", async () => {
    readCopyExpansion.mockRejectedValue(new Error("対応表が無い"));
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "コピー句を展開" }));
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent(
        "コピー句の展開を取得できない（対応表が無い）",
      ),
    );
    expect(editorOf("cobol-fixed").zones).toEqual([]);
    expect(editorOf("cobol-fixed").value).toContain("IDENTIFICATION");
  });

  it("折りたたみへ戻すと差し込みを外す", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    await openExpansion();
    const toggle = screen.getByRole("button", { name: "コピー句の展開を閉じる" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(toggle);
    await waitFor(() => expect(editorOf("cobol-fixed").zones).toEqual([]));
    expect(screen.getByRole("button", { name: "コピー句を展開" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("COPY 文が無い資産では展開の操作を出さない", async () => {
    readSourceText.mockResolvedValue(textResult("       MOVE A TO B."));
    renderViewer(analyzedState());
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "COBOL ソース" })).toHaveTextContent("COPY 文なし"),
    );
    expect(screen.queryByRole("button", { name: "コピー句を展開" })).not.toBeInTheDocument();
  });
});
