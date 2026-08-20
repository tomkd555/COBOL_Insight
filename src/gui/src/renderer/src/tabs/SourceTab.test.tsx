import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import type {
  CobolInsightApi,
  SaveResult,
  SourceTextResult,
  TranspileArtifacts,
} from "../../../shared/engine-api";
import { Shell } from "../shell/Shell";
import {
  ProjectProvider,
  initialProjectState,
  type ProjectState,
} from "../state/projectStore";
import { SettingsProvider } from "../state/settingsStore";
import {
  WorkbenchProvider,
  initialWorkbenchState,
  sourceTab,
  sourceTabId,
  type WorkbenchState,
} from "../state/workbenchStore";
import { FIXTURE_CATALOG } from "../data/__fixtures__/catalog";
import { SAMPLE_INVENTORY } from "../data/__fixtures__/samples";
import type { FakeEditor } from "../screens/viewer/monacoFake";

/**
 * Monaco は Worker と実 DOM の計測を要するため jsdom では動かない。描画ライブラリの入口を
 * 差し替え、本文の面が実際に組み上がるところまでを試験の対象にする。偽物の type() が
 * 利用者の入力にあたる。
 */
const monacoStore = vi.hoisted(() => ({ editors: [] as FakeEditor[] }));

vi.mock("../vendor/monacoEditor", async () => {
  const { createMonacoFake } = await import("../screens/viewer/monacoFake");
  return { monacoEditor: () => createMonacoFake(monacoStore) };
});

vi.mock("../vendor/monacoLanguages", () => ({
  GENERATED_LANGUAGE_ID: { python: "python", java: "java" },
  registerGeneratedLanguages: () => undefined,
}));

const COBOL_PATH = "cobol/SYK001.cbl";
const COPYBOOK_PATH = "copybook/SYKCPY1.cpy";

const SOURCE: SourceTextResult = {
  text: "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. SYK001.\n",
  codepage: "windows-31j",
  truncated: false,
  unsupported: false,
};

/** 走査と検出を終えた状態。 */
const ANALYZED: ProjectState = {
  ...initialProjectState,
  mode: "results",
  inputDir: "C:\\資産",
  dbPath: "C:\\out\\cobol-insight.db",
  inventory: { status: "ready", items: [...SAMPLE_INVENTORY] },
  findings: { status: "ready", items: [] },
  sqlAdvice: { status: "ready", items: [] },
  catalog: FIXTURE_CATALOG,
};

/** engine の save が返す要約。 */
function saveResult(overrides: Partial<SaveResult> = {}): SaveResult {
  return {
    written: true,
    path: "C:/資産/cobol/SYK001.cbl",
    changedLineFrom: 1,
    changedLineTo: 1,
    reparseErrors: [],
    error: "",
    exitCode: 0,
    ...overrides,
  };
}

/** main 側の口。この試験が使う分だけを差し替える。 */
function stubApi(overrides: Partial<CobolInsightApi> = {}): void {
  const stub: Partial<CobolInsightApi> = {
    getOutputPaths: vi.fn().mockRejectedValue(new Error("試験では成果物の位置を持たない")),
    readSettings: vi.fn().mockRejectedValue(new Error("試験では設定を持たない")),
    readSourceText: vi.fn().mockResolvedValue(SOURCE),
    saveSource: vi.fn().mockResolvedValue(saveResult()),
    ...overrides,
  };
  window.cobolInsight = stub as CobolInsightApi;
}

/** 資産1件をタブに開いた作業面。 */
function opened(path: string): WorkbenchState {
  return {
    ...initialWorkbenchState,
    tabs: [sourceTab(path)],
    activeTabId: sourceTabId(path),
  };
}

function renderShell(path: string = COBOL_PATH, project: ProjectState = ANALYZED): void {
  render(
    <SettingsProvider>
      <ProjectProvider initial={project}>
        <WorkbenchProvider initial={opened(path)}>
          <Shell />
        </WorkbenchProvider>
      </ProjectProvider>
    </SettingsProvider>,
  );
}

/** 本文の面が組み上がるまで待ち、その面を返す。 */
async function editorReady(): Promise<FakeEditor> {
  await waitFor(() => expect(monacoStore.editors.length).toBeGreaterThan(0));
  return monacoStore.editors[monacoStore.editors.length - 1];
}

/** 利用者の入力を模す。 */
function type(editor: FakeEditor, text: string): void {
  act(() => {
    editor.type(text);
  });
}

beforeEach(() => {
  monacoStore.editors = [];
  stubApi();
});

describe("本文を編集して書き戻す", () => {
  it("編集すると未保存の印が立ち、保存できる状態になる", async () => {
    renderShell();
    const editor = await editorReady();
    expect(editor.editable).toBe(true);
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();

    type(editor, "編集後の本文\n");

    expect(screen.getByTestId(`dirty-${sourceTabId(COBOL_PATH)}`)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    expect(screen.getByText("未保存の変更があります")).toBeInTheDocument();
  });

  it("Ctrl+S で編集後の全文を engine へ渡し、印を外す", async () => {
    const saveSource = vi.fn().mockResolvedValue(saveResult());
    stubApi({ saveSource });
    renderShell();
    const editor = await editorReady();
    type(editor, "編集後の本文\n");

    fireEvent.keyDown(window, { key: "s", ctrlKey: true });

    await waitFor(() => expect(saveSource).toHaveBeenCalledTimes(1));
    expect(saveSource).toHaveBeenCalledWith({
      inputDir: "C:\\資産",
      path: COBOL_PATH,
      editedText: "編集後の本文\n",
      copybookPaths: [],
    });
    await waitFor(() =>
      expect(screen.queryByTestId(`dirty-${sourceTabId(COBOL_PATH)}`)).toBeNull(),
    );
    expect(screen.getByText("保存済みです")).toBeInTheDocument();
  });

  it("保存の後に原本を読み直し、engine が整えた本文を画面へ戻す", async () => {
    const normalized: SourceTextResult = { ...SOURCE, text: "       正規化後の本文\n" };
    const readSourceText = vi
      .fn()
      .mockResolvedValueOnce(SOURCE)
      .mockResolvedValue(normalized);
    stubApi({ readSourceText });
    renderShell();
    const editor = await editorReady();
    type(editor, "編集後の本文\n");
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });

    await waitFor(() => expect(readSourceText).toHaveBeenCalledTimes(2));
    await waitFor(() => {
      const latest = monacoStore.editors[monacoStore.editors.length - 1];
      expect(latest.value).toBe(normalized.text);
    });
  });

  it("COBOL 本体の再パースの誤りを、面のマーカーと指摘の一覧へ出す", async () => {
    stubApi({
      saveSource: vi.fn().mockResolvedValue(
        saveResult({
          exitCode: 1,
          reparseErrors: [{ line: 2, message: "PERIOD が足りません。" }],
        }),
      ),
    });
    renderShell();
    type(await editorReady(), "編集後の本文\n");
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });

    const table = await screen.findByRole("table", { name: "検出した指摘の一覧" });
    await waitFor(() => expect(within(table).getByText("PERIOD が足りません。")).toBeInTheDocument());
    expect(within(table).getByText("保存時の検証")).toBeInTheDocument();
    await waitFor(() => {
      const latest = monacoStore.editors[monacoStore.editors.length - 1];
      expect(latest.markers).toHaveLength(1);
    });
    expect(monacoStore.editors[monacoStore.editors.length - 1].markers[0].startLineNumber).toBe(2);
  });

  it("コピー句は単体で構文解析できないため、再パースの誤りを出さない", async () => {
    stubApi({
      saveSource: vi.fn().mockResolvedValue(
        saveResult({
          exitCode: 1,
          reparseErrors: [{ line: 1, message: "DIVISION がありません。" }],
        }),
      ),
    });
    renderShell(COPYBOOK_PATH);
    type(await editorReady(), "編集後の本文\n");
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });

    await waitFor(() =>
      expect(screen.queryByTestId(`dirty-${sourceTabId(COPYBOOK_PATH)}`)).toBeNull(),
    );
    const table = screen.getByRole("table", { name: "検出した指摘の一覧" });
    expect(within(table).queryByText("DIVISION がありません。")).toBeNull();
    expect(within(table).queryByText("保存時の検証")).toBeNull();
  });

  /** 保存は数秒かかる。その間の打鍵を、保存の完了で捨てない。 */
  it("保存の間に打った文字を残し、本文を読み直さない", async () => {
    let finishSave: ((result: SaveResult) => void) | null = null;
    const saveSource = vi
      .fn()
      .mockImplementation(
        () =>
          new Promise<SaveResult>((resolve) => {
            finishSave = resolve;
          }),
      );
    const readSourceText = vi.fn().mockResolvedValue(SOURCE);
    stubApi({ saveSource, readSourceText });
    renderShell();
    const editor = await editorReady();
    type(editor, "保存する本文\n");

    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    await waitFor(() => expect(saveSource).toHaveBeenCalledTimes(1));
    expect(saveSource.mock.calls[0][0].editedText).toBe("保存する本文\n");

    // 書き戻しの最中に続きを打つ。
    type(editor, "保存する本文\n保存中の追記\n");
    act(() => {
      finishSave?.(saveResult());
    });

    await waitFor(() => expect(screen.getByRole("button", { name: "保存" })).toBeEnabled());
    expect(screen.getByTestId(`dirty-${sourceTabId(COBOL_PATH)}`)).toBeInTheDocument();
    expect(monacoStore.editors[monacoStore.editors.length - 1].value).toBe(
      "保存する本文\n保存中の追記\n",
    );
    // 読み直すと、engine が整えた本文で新しい打鍵を上書きしてしまう。
    expect(readSourceText).toHaveBeenCalledTimes(1);
  });

  it("書き戻せなかったときは編集を保ち、理由を示す", async () => {
    stubApi({
      saveSource: vi.fn().mockResolvedValue(
        saveResult({
          exitCode: 2,
          written: false,
          error: "識別欄を書き換えています。",
        }),
      ),
    });
    renderShell();
    type(await editorReady(), "編集後の本文\n");
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("識別欄を書き換えています。");
    expect(alert).toHaveTextContent("もう一度保存してください");
    expect(screen.getByTestId(`dirty-${sourceTabId(COBOL_PATH)}`)).toBeInTheDocument();
    expect(screen.getByText("未保存の変更があります")).toBeInTheDocument();
  });
});

describe("文字コードの指定", () => {
  /** 判定は engine が持つ。既定値で塗り替えると、判定できている資産まで読み替えてしまう。 */
  it("判定があれば、その文字コードで読む", async () => {
    const readSourceText = vi.fn().mockResolvedValue(SOURCE);
    stubApi({ readSourceText });
    renderShell();
    await editorReady();

    expect(readSourceText.mock.calls[0][0].codepage).toBe("windows-31j");
    expect(screen.getByRole("combobox", { name: "文字コード" })).toHaveValue("自動");
  });

  it("判定が無い資産は、設定の既定の文字コードで読む", async () => {
    const readSourceText = vi.fn().mockResolvedValue(SOURCE);
    stubApi({ readSourceText });
    // SYKENC1.cbl は復号に失敗した資産であり、codepage を持たない。
    renderShell("cobol/SYKENC1.cbl");
    await editorReady();

    expect(readSourceText.mock.calls[0][0].codepage).toBe("Shift_JIS");
    // 設定の画面が「選択欄の初期値に使う」と約束している。
    expect(screen.getByRole("combobox", { name: "文字コード" })).toHaveValue("手動: Shift_JIS");
  });

  it("手動指定は判定より優先し、その場で本文を読み直す", async () => {
    const readSourceText = vi.fn().mockResolvedValue(SOURCE);
    const saveSource = vi.fn().mockResolvedValue(saveResult());
    stubApi({ readSourceText, saveSource });
    renderShell();
    await editorReady();

    fireEvent.change(screen.getByRole("combobox", { name: "文字コード" }), {
      target: { value: "手動: UTF-8" },
    });

    await waitFor(() => expect(readSourceText).toHaveBeenCalledTimes(2));
    expect(readSourceText.mock.calls[1][0].codepage).toBe("UTF-8");

    // 書き戻しにも同じ指定を渡す。渡さないと engine は走査時の判定で符号化する。
    type(await editorReady(), "編集後の本文\n");
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    await waitFor(() => expect(saveSource).toHaveBeenCalledTimes(1));
    expect(saveSource.mock.calls[0][0].codepage).toBe("UTF-8");
  });
});

describe("解析中の本文の面", () => {
  /** 走査を始めると資産一覧はいったん空になる。そこで本文の面まで畳むと、編集中の面が消える。 */
  it("再解析の最中も編集中の面を保つ", async () => {
    const readSourceText = vi.fn().mockResolvedValue(SOURCE);
    stubApi({
      readSourceText,
      getOutputPaths: vi.fn().mockResolvedValue({
        db: "C:\\out\\cobol-insight.db",
        lintSarif: "C:\\out\\lint.sarif",
        sqlAdviseSarif: "C:\\out\\sql.sarif",
        copyExpansion: "C:\\out\\copy.json",
        userRules: "C:\\out\\user-rules.json",
        ruleConfig: "C:\\out\\rules-config.json",
      }),
      // 走査を終わらせない。第1段の最中の画面を見る。
      runScan: vi.fn().mockReturnValue(new Promise(() => undefined)),
    });
    renderShell();
    const editor = await editorReady();
    type(editor, "編集中の本文\n");

    fireEvent.click(screen.getByTestId("reanalyze"));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "解析をキャンセル" })).toBeInTheDocument(),
    );
    expect(screen.queryByText(`${COBOL_PATH} を読み込んでいます。`)).toBeNull();
    expect(monacoStore.editors[monacoStore.editors.length - 1].value).toBe("編集中の本文\n");
    expect(monacoStore.editors[monacoStore.editors.length - 1].disposed).toBe(false);
    expect(readSourceText).toHaveBeenCalledTimes(1);
  });
});

describe("未保存のタブを閉じる", () => {
  it("破棄してよいかを問い、断られたら閉じない", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    renderShell();
    type(await editorReady(), "編集後の本文\n");

    fireEvent.click(screen.getByRole("button", { name: "SYK001.cbl を閉じる" }));

    expect(confirm).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("tab", { name: COBOL_PATH })).toBeInTheDocument();

    confirm.mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "SYK001.cbl を閉じる" }));
    expect(screen.queryByRole("tab", { name: COBOL_PATH })).toBeNull();
    confirm.mockRestore();
  });

  it("編集していないタブは問わずに閉じる", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    renderShell();
    await editorReady();

    fireEvent.click(screen.getByRole("button", { name: "SYK001.cbl を閉じる" }));

    expect(confirm).not.toHaveBeenCalled();
    expect(screen.queryByRole("tab", { name: COBOL_PATH })).toBeNull();
    confirm.mockRestore();
  });
});

describe("逐語対訳の分割", () => {
  const ARTIFACTS: TranspileArtifacts = {
    files: [{ name: "syk001.py", language: "python", text: "def main():\n    pass\n" }],
    lineMap: [
      {
        id: 1,
        cobolLineStart: 1,
        cobolLineEnd: 2,
        genFile: "syk001.py",
        genLineStart: 1,
        genLineEnd: 2,
        kind: "1:N",
        note: "",
        anchorId: "a1",
      },
    ],
  };

  it("既定では畳んでおり、開くと対訳の面を並べる", async () => {
    const readTranspileArtifacts = vi.fn().mockResolvedValue(ARTIFACTS);
    stubApi({ readTranspileArtifacts });
    renderShell();
    await editorReady();
    expect(screen.queryByRole("region", { name: "逐語対訳" })).toBeNull();
    expect(readTranspileArtifacts).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId("toggle-translation"));

    expect(await screen.findByRole("region", { name: "逐語対訳" })).toBeInTheDocument();
    await waitFor(() =>
      expect(readTranspileArtifacts).toHaveBeenCalledWith({
        outDir: "C:\\out\\transpile",
        dbPath: "C:\\out\\cobol-insight.db",
        cobolRelPath: COBOL_PATH,
      }),
    );
    await waitFor(() =>
      expect(monacoStore.editors.some((editor) => editor.language === "python")).toBe(true),
    );

    fireEvent.click(screen.getByTestId("toggle-translation"));
    expect(screen.queryByRole("region", { name: "逐語対訳" })).toBeNull();
  });

  it("行の対応が空なら対訳を生成し、読み直す", async () => {
    const readTranspileArtifacts = vi
      .fn()
      .mockResolvedValueOnce({ files: [], lineMap: [] })
      .mockResolvedValue(ARTIFACTS);
    const runTranspile = vi.fn().mockResolvedValue({
      subcommand: "translate",
      exitCode: 0,
      summary: null,
      stdout: "",
      stderr: "",
      outputs: {},
    });
    stubApi({ readTranspileArtifacts, runTranspile });
    renderShell();
    await editorReady();

    fireEvent.click(screen.getByTestId("toggle-translation"));

    await waitFor(() => expect(runTranspile).toHaveBeenCalledTimes(1));
    expect(runTranspile).toHaveBeenCalledWith({
      inputDir: "C:\\資産",
      copybookPaths: [],
      db: "C:\\out\\cobol-insight.db",
      language: "both",
      outDir: "C:\\out\\transpile",
    });
    await waitFor(() => expect(readTranspileArtifacts).toHaveBeenCalledTimes(2));
  });

  it("COBOL 本体でない資産では対訳を生成しない", async () => {
    const readTranspileArtifacts = vi.fn().mockResolvedValue(ARTIFACTS);
    stubApi({ readTranspileArtifacts });
    renderShell(COPYBOOK_PATH);
    await editorReady();

    fireEvent.click(screen.getByTestId("toggle-translation"));

    expect(
      await screen.findByRole("region", { name: "この資産は逐語対訳の対象ではありません" }),
    ).toBeInTheDocument();
    expect(readTranspileArtifacts).not.toHaveBeenCalled();
  });
});
