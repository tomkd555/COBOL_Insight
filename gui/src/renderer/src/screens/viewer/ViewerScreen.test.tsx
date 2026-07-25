import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ViewerScreen } from "./ViewerScreen";
import { AppStateProvider, useAppState } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";
import {
  SAMPLE_COBOL_TEXT,
  SAMPLE_COPYBOOK_TEXT,
  SAMPLE_GENERATED_FILES,
  SAMPLE_LINE_MAP,
} from "./fixtures";
import type {
  CobolInsightApi,
  EngineResult,
  SourceTextRequest,
  SourceTextResult,
} from "../../../../shared/engine-api";

/** 偽の Monaco エディタ1台の観測結果。 */
interface FakeEditor {
  language: string;
  ariaLabel: string;
  rulers: number[];
  value: string;
  decorations: { range: { startLineNumber: number }; options: { className?: string; inlineClassName?: string } }[];
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
      defineTheme: () => undefined,
      create: (
        _container: HTMLElement,
        options: { value?: string; language?: string; rulers?: number[]; ariaLabel?: string },
      ) => {
        const editor: FakeEditor = {
          language: options.language ?? "",
          ariaLabel: options.ariaLabel ?? "",
          rulers: options.rulers ?? [],
          value: options.value ?? "",
          decorations: [],
          revealed: [],
          disposed: false,
          cursorHandler: null,
        };
        monacoStore.editors.push(editor);
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
  runTranspile = vi.fn().mockResolvedValue(transpileResult());
  window.cobolInsight = {
    readSourceText,
    readTranspileArtifacts,
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
    expect(region).toHaveTextContent("対応表(LINE_MAP)に対応が無い");
    expect(runTranspile).toHaveBeenCalledTimes(1);
  });

  it("対応表はあるのに生成物が無い場合は、対応表が無い場合と区別して示す", async () => {
    readTranspileArtifacts.mockResolvedValue({ files: [], lineMap: SAMPLE_LINE_MAP });
    renderViewer(analyzedState());
    const region = await screen.findByRole("region", {
      name: "生成物が出力先に見つかりません",
    });
    expect(region).toHaveTextContent("C:\\proj\\transpile");
    expect(region).toHaveTextContent("対応表(LINE_MAP)はある");
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
    fireEvent.click(screen.getByRole("button", { name: "← 指摘一覧へ戻る" }));
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

describe("ViewerScreen のコピー句展開", () => {
  it("COPY 文の件数と原文を示し、閉じている間は本文を読まない", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    const copy = screen.getByRole("region", { name: "コピー句の展開" });
    expect(copy).toHaveTextContent("COPY 文 1 件");
    expect(copy).toHaveTextContent("7 行: COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.");
    expect(readSourceText).toHaveBeenCalledTimes(1);
  });

  it("展開するとコピー句の本文を読み、REPLACING は原文として添える", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "＋ 展開表示" }));
    await waitFor(() => expect(screen.getByText(/SYK1-KEY/)).toBeInTheDocument());
    expect(readSourceText).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      path: "copybook/SYKCPY1.cpy",
      codepage: "UTF-8",
    });
    const copy = screen.getByRole("region", { name: "コピー句の展開" });
    expect(copy).toHaveTextContent("REPLACING LEADING ==SYK1== BY ==ORD1==");
    expect(copy).toHaveTextContent("置換後の姿は示さない");
  });

  it("折りたたみへ戻せる", async () => {
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "＋ 展開表示" }));
    const toggle = await screen.findByRole("button", { name: "− 折りたたみ" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(toggle);
    expect(screen.getByRole("button", { name: "＋ 展開表示" })).toHaveAttribute("aria-expanded", "false");
  });

  it("どの探索先にも無いコピー句は、探索した場所を添えて見つからない旨を示す", async () => {
    readSourceText.mockImplementation((request: SourceTextRequest) =>
      request.path === COBOL
        ? Promise.resolve(textResult(SAMPLE_COBOL_TEXT))
        : Promise.reject(new Error("ない")),
    );
    renderViewer(analyzedState());
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "＋ 展開表示" }));
    await waitFor(() => expect(screen.getByText(/コピー句が見つからない/)).toBeInTheDocument());
    expect(screen.getByText(/D:\\共通コピー句\\SYKCPY1.cpy/)).toBeInTheDocument();
  });

  it("探索先が1つも無い場合は、空の場所を並べず探索先が無い旨を示す", async () => {
    readSourceText.mockImplementation((request: SourceTextRequest) =>
      request.path === COBOL
        ? Promise.resolve(textResult(SAMPLE_COBOL_TEXT))
        : Promise.reject(new Error("ない")),
    );
    // 資産一覧にコピー句が無く、コピー句検索パスも未設定なら探索候補は 0 件になる。
    renderViewer(
      analyzedState({
        project: { inputDir: INPUT_DIR, dbPath: DB_PATH, copybookPaths: [] },
        inventory: {
          status: "ready",
          items: SAMPLE_INVENTORY.filter((item) => item.type !== "COPYBOOK"),
        },
      }),
    );
    await waitForPanes();
    fireEvent.click(screen.getByRole("button", { name: "＋ 展開表示" }));
    await waitFor(() =>
      expect(screen.getByText(/コピー句の探索先が無い/)).toBeInTheDocument(),
    );
    expect(screen.queryByText(/探索した場所: 。/)).not.toBeInTheDocument();
  });

  it("COPY 文が無い資産では展開の操作を出さない", async () => {
    readSourceText.mockResolvedValue(textResult("       MOVE A TO B."));
    renderViewer(analyzedState());
    await waitFor(() => expect(screen.getByText("このソースに COPY 文はない。")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "＋ 展開表示" })).not.toBeInTheDocument();
  });
});
