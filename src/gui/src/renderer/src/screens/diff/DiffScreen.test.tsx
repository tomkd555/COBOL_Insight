import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { DiffScreen } from "./DiffScreen";
import { AppStateProvider, useAppDispatch, useAppState } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import { SAMPLE_FINDINGS } from "../findings/fixtures";
import {
  SAMPLE_APPLY_SUMMARY,
  SAMPLE_FIXED_TEXT,
  SAMPLE_ORIGINAL_TEXT,
  SAMPLE_PREVIEW_STDOUT,
  applyResult,
  previewResult,
} from "./fixtures";
import type {
  CobolInsightApi,
  FixApplyRequest,
  FixResultRequest,
} from "../../../../shared/engine-api";

/** 偽の Monaco DiffEditor 1台の観測結果。 */
interface FakeDiffEditor {
  originalText: string;
  fixedText: string;
  language: string;
  ignoreTrimWhitespace: boolean;
  disposed: boolean;
  disposedModels: number;
}

/**
 * Monaco は Worker と実 DOM 計測を要するため jsdom では動かない。ソースビューアのテストと同じ方式で
 * 描画ライブラリの入口(vendor/monacoEditor)を差し替え、DiffEditor へ渡った原本/修正後の本文・言語・
 * 空白差の扱いを記録する偽物にする。
 */
const monacoStore = vi.hoisted(() => ({ editors: [] as FakeDiffEditor[] }));

vi.mock("../../vendor/monacoEditor", () => ({
  monacoEditor: () => ({
    languages: {
      register: () => undefined,
      setMonarchTokensProvider: () => undefined,
      setLanguageConfiguration: () => undefined,
    },
    editor: {
      defineTheme: () => undefined,
      createModel: (value: string, language: string) => ({
        value,
        language,
        dispose: () => {
          const current = monacoStore.editors.at(-1);
          if (current !== undefined) current.disposedModels += 1;
        },
      }),
      createDiffEditor: (_container: HTMLElement, options: { ignoreTrimWhitespace?: boolean }) => {
        const entry: FakeDiffEditor = {
          originalText: "",
          fixedText: "",
          language: "",
          ignoreTrimWhitespace: options.ignoreTrimWhitespace ?? true,
          disposed: false,
          disposedModels: 0,
        };
        monacoStore.editors.push(entry);
        return {
          setModel: (models: {
            original: { value: string; language: string };
            modified: { value: string };
          }) => {
            entry.originalText = models.original.value;
            entry.fixedText = models.modified.value;
            entry.language = models.original.language;
          },
          dispose: () => {
            entry.disposed = true;
          },
        };
      },
    },
  }),
}));

const INPUT_DIR = "C:\\資産\\SYK";
const DB_PATH = "C:\\proj\\cobol-insight.db";
const COBOL = "cobol/SYK007.cbl";
const COPYBOOK = "copybook/ORDREC.cpy";

let runFixPreview: ReturnType<typeof vi.fn>;
let runFixApply: ReturnType<typeof vi.fn>;
let readFixResult: ReturnType<typeof vi.fn>;

beforeEach(() => {
  monacoStore.editors = [];
  runFixPreview = vi.fn().mockResolvedValue(previewResult());
  // main は要求した出力先をそのまま outputs へ返す(collectRequestedOutputs)ため、模擬も同じ形にする。
  runFixApply = vi.fn((request: FixApplyRequest) =>
    Promise.resolve(applyResult(SAMPLE_APPLY_SUMMARY, request.outDir ?? "fix")),
  );
  readFixResult = vi.fn((request: FixResultRequest) =>
    Promise.resolve({
      relPath: request.relPath,
      originalText: SAMPLE_ORIGINAL_TEXT,
      fixedText: SAMPLE_FIXED_TEXT,
    }),
  );
  window.cobolInsight = {
    runFixPreview,
    runFixApply,
    readFixResult,
  } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** 解析実行が済んだ状態(mode=results・指摘あり)。 */
function analyzedState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    mode: "results",
    screen: "diff",
    project: { inputDir: INPUT_DIR, dbPath: DB_PATH, copybookPaths: ["D:\\共通コピー句"] },
    findings: { status: "ready", items: SAMPLE_FINDINGS },
    ...overrides,
  };
}

function renderDiff(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <DiffScreen />
    </AppStateProvider>,
  );
}

/** タブ移動で画面をアンマウントするラッパー。選択・判定・表示モードが AppState に残ることを観測する。 */
function TabHarness(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  return (
    <>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "explorer" })}>
        資産タブ
      </button>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "diff" })}>
        diff タブ
      </button>
      {state.screen === "diff" ? <DiffScreen /> : <p>資産エクスプローラー</p>}
    </>
  );
}

/** 生存している DiffEditor のうち最後のもの。 */
function currentEditor(): FakeDiffEditor {
  const editor = monacoStore.editors.filter((entry) => !entry.disposed).at(-1);
  if (editor === undefined) {
    throw new Error("DiffEditor が無い");
  }
  return editor;
}

/** 修正案の一覧が出るまで待つ。 */
async function waitForList(): Promise<void> {
  await waitFor(() => expect(screen.getByRole("listbox", { name: "修正案の一覧" })).toBeInTheDocument());
}

describe("DiffScreen の4状態", () => {
  it("解析未実行は修正案を生成するルールを添えて空状態を出す", () => {
    renderDiff();
    expect(screen.getByText("修正案がありません")).toBeInTheDocument();
    expect(screen.getByText(/R004/)).toBeInTheDocument();
    expect(runFixPreview).not.toHaveBeenCalled();
  });

  it("解析実行中は実行中インジケータを出し fix を起動しない", () => {
    renderDiff({ ...initialState, mode: "running" });
    expect(screen.getByRole("status")).toHaveTextContent("修正案を生成しています…");
    expect(runFixPreview).not.toHaveBeenCalled();
  });

  it("解析済みなら fix preview を起動して修正案の一覧を出す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    expect(runFixPreview).toHaveBeenCalledWith({
      inputDir: INPUT_DIR,
      copybookPaths: ["D:\\共通コピー句"],
    });
    expect(screen.getByText("修正案 3 件（R004 / R017 / R018 / R021）")).toBeInTheDocument();
    expect(screen.getAllByRole("option")).toHaveLength(3);
  });

  it("fix preview の失敗はエラーとして理由を示し、再試行できる", async () => {
    runFixPreview.mockRejectedValue(new Error("engine を起動できない"));
    renderDiff(analyzedState());
    await waitFor(() => expect(screen.getByText("修正案を生成できませんでした")).toBeInTheDocument());
    expect(screen.getByText(/engine を起動できない/)).toBeInTheDocument();

    runFixPreview.mockResolvedValue(previewResult());
    fireEvent.click(screen.getByRole("button", { name: "再試行" }));
    await waitForList();
  });

  it("修正案が 0 件のときは指摘 0 件と区別した文言を出す", async () => {
    runFixPreview.mockResolvedValue(
      previewResult({ fixedFiles: [], copybookFixes: [], fixCount: 0, analysisErrors: 0 }, ""),
    );
    renderDiff(analyzedState());
    await waitFor(() => expect(screen.getByText("修正案は生成されませんでした")).toBeInTheDocument());
    // 実体化のための書き出しは、修正案が無いときには起動しない。
    expect(runFixApply).not.toHaveBeenCalled();
  });
});

describe("DiffScreen の差分表示", () => {
  it("原本と修正後のテキスト対を DiffEditor へそのまま渡す", async () => {
    renderDiff(analyzedState());
    await waitFor(() => expect(currentEditor().fixedText).toBe(SAMPLE_FIXED_TEXT));
    expect(currentEditor().originalText).toBe(SAMPLE_ORIGINAL_TEXT);
    expect(currentEditor().language).toBe("cobol-fixed");
  });

  it("固定形式の桁を保つため空白の差を無視しない", async () => {
    renderDiff(analyzedState());
    await waitFor(() => expect(monacoStore.editors).toHaveLength(1));
    expect(currentEditor().ignoreTrimWhitespace).toBe(false);
  });

  it("差分は原本フォルダと検証用の書き出し先の対から読む", async () => {
    renderDiff(analyzedState());
    await waitFor(() => expect(readFixResult).toHaveBeenCalled());
    expect(readFixResult).toHaveBeenCalledWith({
      originalPath: `${INPUT_DIR}\\cobol\\SYK007.cbl`,
      fixedPath: "C:\\proj\\fix-preview\\cobol\\SYK007.cbl",
      relPath: COBOL,
    });
  });

  it("修正後ソースの実体化は検証用の作業フォルダへ書き出す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    await waitFor(() => expect(runFixApply).toHaveBeenCalled());
    const request = runFixApply.mock.calls[0][0] as FixApplyRequest;
    expect(request.outDir).toBe("C:\\proj\\fix-preview");
  });

  it("コピー句の修正は影響範囲と unified diff を示し DiffEditor を使わない", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByText(COPYBOOK));
    await waitFor(() =>
      expect(screen.getByLabelText(`統一形式の差分 ${COPYBOOK}`)).toBeInTheDocument(),
    );
    expect(screen.getByText("組み込み元プログラム: SYK001、 SYK002")).toBeInTheDocument();
    expect(screen.getByLabelText(`統一形式の差分 ${COPYBOOK}`)).toHaveTextContent(
      "+ 05 ORD-状態 PIC X(1).",
    );
    expect(monacoStore.editors.filter((entry) => !entry.disposed)).toHaveLength(0);
  });

  it("指摘の取得に失敗していても修正案の差分は示す", async () => {
    renderDiff(
      analyzedState({ mode: "error", findings: { status: "error", message: "lint が失敗した" } }),
    );
    await waitForList();
    await waitFor(() => expect(currentEditor().fixedText).toBe(SAMPLE_FIXED_TEXT));
    // 指摘が無いためルール名は示さず、ファイル単位の修正案として示す。
    expect(screen.getAllByText("修正案")).not.toHaveLength(0);
    expect(readFixResult).toHaveBeenCalledTimes(1);
  });

  it("差分の読取失敗は理由を示す", async () => {
    readFixResult.mockRejectedValue(new Error("修正後ソースが無い"));
    renderDiff(analyzedState());
    await waitFor(() => expect(screen.getByText("差分を読み取れませんでした")).toBeInTheDocument());
    expect(screen.getByText("修正後ソースが無い")).toBeInTheDocument();
  });

  it("実体化に失敗しても一覧と unified diff は示す", async () => {
    runFixApply.mockRejectedValue(new Error("書き出せない"));
    renderDiff(analyzedState());
    await waitForList();
    await waitFor(() =>
      expect(screen.getByLabelText(`統一形式の差分 ${COBOL}`)).toBeInTheDocument(),
    );
    expect(screen.getByLabelText(`統一形式の差分 ${COBOL}`)).toHaveTextContent("ON SIZE ERROR");
  });
});

describe("DiffScreen の採用・棄却", () => {
  it("採用するとカードの判定が採用済へ変わる", async () => {
    renderDiff(analyzedState());
    await waitForList();
    expect(screen.getAllByText("未判定")).toHaveLength(3);
    fireEvent.click(screen.getByRole("button", { name: "採用" }));
    expect(screen.getByText("✓ 採用済")).toBeInTheDocument();
    expect(screen.getByText("判定: 採用 1 ・ 棄却 0 ・ 未判定 2")).toBeInTheDocument();
  });

  it("棄却するとカードの判定が棄却済へ変わる", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "棄却" }));
    expect(screen.getByText("✗ 棄却済")).toBeInTheDocument();
  });

  it("同じ判定を押し直すと未判定へ戻す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "採用" }));
    fireEvent.click(screen.getByRole("button", { name: "採用" }));
    expect(screen.getAllByText("未判定")).toHaveLength(3);
  });

  it("矢印キーで修正案を選び替えられる", async () => {
    renderDiff(analyzedState());
    await waitForList();
    const list = screen.getByRole("listbox", { name: "修正案の一覧" });
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(list, { key: "ArrowDown" });
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(list, { key: "End" });
    expect(screen.getAllByRole("option")[2]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(list, { key: "Home" });
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    // 選び替えごとに readFixResult が走るため、最後の解決を待ってから終える。
    await waitFor(() => expect(readFixResult).toHaveBeenCalledTimes(3));
  });

  it("修正案を選び替えると判定は選んだ修正案のものを示す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "採用" }));
    fireEvent.click(screen.getByText(COPYBOOK));
    await waitFor(() => expect(screen.getByRole("button", { name: "採用" })).toHaveAttribute("aria-pressed", "false"));
  });
});

describe("DiffScreen の状態の保持", () => {
  it("選択・判定・表示モードはタブを移動して戻っても保たれる", async () => {
    render(
      <AppStateProvider initialState={analyzedState()}>
        <TabHarness />
      </AppStateProvider>,
    );
    await waitForList();
    fireEvent.click(screen.getByText(COPYBOOK));
    fireEvent.click(screen.getByRole("button", { name: "棄却" }));
    fireEvent.click(screen.getByRole("button", { name: "適用（書き出し）" }));

    fireEvent.click(screen.getByRole("button", { name: "資産タブ" }));
    fireEvent.click(screen.getByRole("button", { name: "diff タブ" }));
    await waitForList();
    expect(screen.getByText("✗ 棄却済")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "棄却" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "適用（書き出し）" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("選択は相対パスで保つため、修正案の並びが変わっても同じ修正案を指す", async () => {
    render(
      <AppStateProvider initialState={analyzedState({ fixSelected: COPYBOOK })}>
        <DiffScreen />
      </AppStateProvider>,
    );
    await waitForList();
    // engine が出した並びの先頭は cobol/SYK007.cbl だが、選択はコピー句のままである。
    expect(screen.getAllByRole("option")[2]).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "false");
  });
});

describe("DiffScreen の適用(書き出し)", () => {
  it("適用モードで書き出し先を示し、engine を出力先へ起動する", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "適用（書き出し）" }));
    expect(screen.getByText("C:\\proj\\fix")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "修正版を書き出す" }));
    await waitFor(() => expect(runFixApply).toHaveBeenCalledTimes(2));
    const request = runFixApply.mock.calls[1][0] as FixApplyRequest;
    expect(request.outDir).toBe("C:\\proj\\fix");
  });

  it("書き出し後は出力先・件数・コピー句の扱いを示す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "適用（書き出し）" }));
    fireEvent.click(screen.getByRole("button", { name: "修正版を書き出す" }));
    await waitFor(() =>
      expect(screen.getByText(/2 件を書き出した/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/コピー句 1 件/)).toBeInTheDocument();
  });

  it("棄却があるときは engine が全件を書き出す旨を書き出し前に示す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "棄却" }));
    fireEvent.click(screen.getByRole("button", { name: "適用（書き出し）" }));
    expect(screen.getByText(/棄却した 1 件も書き出しに含まれる/)).toBeInTheDocument();
  });

  it("書き出しの失敗は理由を残す", async () => {
    renderDiff(analyzedState());
    await waitForList();
    fireEvent.click(screen.getByRole("button", { name: "適用（書き出し）" }));
    runFixApply.mockRejectedValue(new Error("権限が無い"));
    fireEvent.click(screen.getByRole("button", { name: "修正版を書き出す" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "修正版を書き出す" })).toBeEnabled(),
    );
    expect(screen.queryByText(/件を書き出しました/)).not.toBeInTheDocument();
  });
});

describe("DiffScreen の検証・解析の警告", () => {
  it("再構文解析の失敗を件数付きで警告する", async () => {
    runFixApply.mockResolvedValue(applyResult({ ...SAMPLE_APPLY_SUMMARY, reparseFailures: 2 }));
    renderDiff(analyzedState());
    await waitForList();
    await waitFor(() =>
      expect(screen.getByText(/2 件が検証に失敗した/)).toBeInTheDocument(),
    );
  });

  it("解析段のエラーを件数付きで警告する", async () => {
    runFixPreview.mockResolvedValue(
      previewResult(
        {
          fixedFiles: [COBOL],
          copybookFixes: [],
          fixCount: 1,
          analysisErrors: 3,
        },
        SAMPLE_PREVIEW_STDOUT,
      ),
    );
    renderDiff(analyzedState({ mode: "error" }));
    await waitForList();
    expect(screen.getByText(/解析で 3 件のエラーがある/)).toBeInTheDocument();
  });
});
