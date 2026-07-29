import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ExplorerScreen } from "./ExplorerScreen";
import { ScreenRouter } from "../ScreenRouter";
import { AppStateProvider, useAppState, useAppDispatch } from "../../state/AppStateContext";
import { SPLIT_PANES, initialState, type AppState } from "../../state/appState";
import { deriveStatus } from "../../state/status";
import { SAMPLE_INVENTORY } from "./fixtures";
import { SAMPLE_FINDINGS, SAMPLE_SQL_FINDINGS } from "../findings/fixtures";
import type { CobolInsightApi, EngineResult } from "../../../../shared/engine-api";

const scanResult: EngineResult = {
  subcommand: "scan",
  exitCode: 0,
  summary: {},
  stdout: "",
  stderr: "",
  outputs: { db: "proj.db" },
};

const lintResult: EngineResult = {
  subcommand: "lint",
  exitCode: 0,
  summary: {},
  stdout: "",
  stderr: "",
  outputs: { sarif: "lint.sarif" },
};

const sqlResult: EngineResult = {
  subcommand: "sql-advise",
  exitCode: 0,
  summary: {},
  stdout: "",
  stderr: "",
  outputs: { sarif: "sql.sarif" },
};

/** インポートダイアログ(main の selectInputFolder)が返す資産フォルダ。 */
const SELECTED_DIR = "C:\\資産\\SYK";

let runScan: ReturnType<typeof vi.fn>;
let readAssetInventory: ReturnType<typeof vi.fn>;
let runLint: ReturnType<typeof vi.fn>;
let runSqlAdvise: ReturnType<typeof vi.fn>;
let readSarif: ReturnType<typeof vi.fn>;
let selectInputFolder: ReturnType<typeof vi.fn>;
let readSourceText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  runScan = vi.fn().mockResolvedValue(scanResult);
  readAssetInventory = vi.fn().mockResolvedValue([...SAMPLE_INVENTORY]);
  runLint = vi.fn().mockResolvedValue(lintResult);
  runSqlAdvise = vi.fn().mockResolvedValue(sqlResult);
  readSarif = vi.fn().mockImplementation((path: string) =>
    Promise.resolve(path === "sql.sarif" ? [...SAMPLE_SQL_FINDINGS] : [...SAMPLE_FINDINGS]),
  );
  selectInputFolder = vi.fn().mockResolvedValue(SELECTED_DIR);
  readSourceText = vi.fn().mockResolvedValue({
    text: "001000 IDENTIFICATION DIVISION.\n001100 PROGRAM-ID. SYK001.",
    codepage: "Shift_JIS",
    truncated: true,
    unsupported: false,
  });
  window.cobolInsight = {
    runScan,
    readAssetInventory,
    runLint,
    runSqlAdvise,
    readSarif,
    selectInputFolder,
    readSourceText,
  } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

function renderExplorer(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <ExplorerScreen />
    </AppStateProvider>,
  );
}

/** 設定の初期値を上書きした空状態。 */
function seedState(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

/**
 * 画面ルーティングとステータスバーを含むラッパー。タブ移動で画面がアンマウントされる実アプリと
 * 同じ条件を作り、状態の保持と件数表示を観測する。
 */
function Harness(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  return (
    <>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "findings" })}>
        指摘一覧タブ
      </button>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "explorer" })}>
        資産タブ
      </button>
      <p data-testid="status">{deriveStatus(state).counts}</p>
      <ScreenRouter screen={state.screen} />
    </>
  );
}

function renderApp(): void {
  render(
    <AppStateProvider>
      <Harness />
    </AppStateProvider>,
  );
}

/** インポート(フォルダ選択ダイアログ)を実行し、解析実行が有効になるまで待つ。 */
async function importFolder(): Promise<void> {
  fireEvent.click(screen.getByRole("button", { name: "インポート" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "解析実行" })).toBeEnabled());
}

/** インポート→解析実行 の順に操作し、一覧が出るまで待つ。 */
async function importAndRun(): Promise<void> {
  await importFolder();
  fireEvent.click(screen.getByRole("button", { name: "解析実行" }));
  await screen.findByRole("button", { name: /SYK001\.cbl/ });
}

describe("ExplorerScreen(資産エクスプローラー container)", () => {
  it("空状態ではインポート誘導を出し、解析実行は無効", () => {
    renderExplorer();
    expect(screen.getByRole("region", { name: "資産がまだインポートされていません" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解析実行" })).toBeDisabled();
  });

  it("ボタンの記号は目で見えるだけで、読み上げ名には混ざらない", () => {
    renderExplorer();
    const run = screen.getByRole("button", { name: "解析実行" });
    // 表示文字はスモークテストが押下対象を選ぶ手がかりでもあるため、厳密に照合する。
    expect(run.textContent).toBe("▶ 解析実行");
    expect(run).toHaveAccessibleName("解析実行");
    const importButton = screen.getByRole("button", { name: "インポート" });
    expect(importButton.textContent).toBe("＋ インポート");
    expect(importButton).toHaveAccessibleName("インポート");
  });

  it("空状態の誘導はフォルダの取込だけを示す", () => {
    renderExplorer();
    expect(screen.getByRole("button", { name: "フォルダをインポート" })).toBeInTheDocument();
  });

  it("インポートで選んだフォルダをプロジェクトの入力フォルダにする", async () => {
    renderExplorer();
    await importFolder();
    expect(selectInputFolder).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "解析実行" })).toBeEnabled();
  });

  it("インポートをキャンセルしたら入力フォルダを変えない", async () => {
    selectInputFolder.mockResolvedValue(null);
    renderExplorer();
    fireEvent.click(screen.getByRole("button", { name: "インポート" }));
    await waitFor(() => expect(selectInputFolder).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("button", { name: "解析実行" })).toBeDisabled();
  });

  it("解析実行で scan・lint・sql-advise を順に起動し資産一覧を表示する(ゲート経路)", async () => {
    renderExplorer();
    await importAndRun();

    expect(runScan).toHaveBeenCalledTimes(1);
    expect(runScan.mock.calls[0][0]).toMatchObject({ inputDir: SELECTED_DIR, codepageOverrides: {} });
    expect(readAssetInventory).toHaveBeenCalledWith("proj.db");
    await waitFor(() => expect(runSqlAdvise).toHaveBeenCalledTimes(1));
    expect(runLint).toHaveBeenCalledTimes(1);
    expect(runLint.mock.calls[0][0]).toMatchObject({ inputDir: SELECTED_DIR, disabledRules: [] });
    expect(readSarif).toHaveBeenCalledWith("lint.sarif");
    expect(readSarif).toHaveBeenCalledWith("sql.sarif");

    expect(screen.getByRole("button", { name: /SYKMAP1\.bms/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /SYKD010\.jcl/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cobol/ })).toHaveTextContent("3 ファイル");
  });

  it("実行中は解析段の進行(scan→lint→sql-advise)を実際の起動に合わせて強調する", async () => {
    // 各段の完了をテストから制御し、強調が段の進行に追従することを観測する。
    const pending: Array<() => void> = [];
    const hold = (result: EngineResult) => () =>
      new Promise<EngineResult>((resolve) => pending.push(() => resolve(result)));
    runScan.mockImplementation(hold(scanResult));
    runLint.mockImplementation(hold(lintResult));
    runSqlAdvise.mockImplementation(hold(sqlResult));

    renderExplorer();
    await importFolder();
    fireEvent.click(screen.getByRole("button", { name: "解析実行" }));

    const stageOf = (label: string): HTMLElement => screen.getByText(label);
    expect(stageOf("第1段 資産の走査と構文解析")).toHaveAttribute("aria-current", "step");
    expect(stageOf("第2段 バグ検出")).not.toHaveAttribute("aria-current");

    pending.shift()?.();
    await waitFor(() => expect(stageOf("第2段 バグ検出")).toHaveAttribute("aria-current", "step"));
    expect(stageOf("第1段 資産の走査と構文解析")).not.toHaveAttribute("aria-current");

    pending.shift()?.();
    await waitFor(() => expect(stageOf("第3段 SQL助言")).toHaveAttribute("aria-current", "step"));

    pending.shift()?.();
    await screen.findByRole("button", { name: /SYK001\.cbl/ });
  });

  it("復号失敗と構文解析失敗を区別して解析状態に出す", async () => {
    renderExplorer();
    await importAndRun();
    // SYKENC1.cbl は codepage=null(復号失敗)、SYK002.cbl は復号成功+scan 由来 finding 1 件。
    expect(screen.getByRole("button", { name: /SYKENC1\.cbl/ })).toHaveTextContent("✗ 復号失敗");
    expect(screen.getByRole("button", { name: /SYK002\.cbl/ })).toHaveTextContent("✗ 構文解析失敗");
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toHaveTextContent("✓ 解析済");
  });

  it("文字コード列は engine の charset 名を利用者向け表記へ写して出す", async () => {
    renderExplorer();
    await importAndRun();
    // SOURCE.codepage は windows-31j だが、利用者へは Shift_JIS と示す。
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toHaveTextContent("Shift_JIS");
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).not.toHaveTextContent("windows-31j");
  });

  it("指摘列は lint の指摘件数を示す(scan 由来の件数ではない)", async () => {
    renderExplorer();
    await importAndRun();
    // SAMPLE_FINDINGS の cobol/SYK001.cbl は 4 件、cobol/SYK002.cbl は 0 件。
    await waitFor(() =>
      expect(within(screen.getByRole("button", { name: /SYK001\.cbl/ })).getByText("4")).toBeInTheDocument(),
    );
    const row2 = screen.getByRole("button", { name: /SYK002\.cbl/ });
    expect(within(row2).queryByText("4")).toBeNull();
  });

  it("ステータスバーは解析後に資産・指摘・SQL助言の実件数を示す", async () => {
    renderApp();
    await importAndRun();
    await waitFor(() =>
      expect(screen.getByTestId("status")).toHaveTextContent(
        "資産 6 ・ 指摘 10 ・ SQL助言 6 ・ ルール 37 有効 ・ v1.0.0",
      ),
    );
  });

  it("他タブへ移動して戻っても資産一覧と入力フォルダを保つ", async () => {
    renderApp();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: "指摘一覧タブ" }));
    expect(screen.queryByRole("button", { name: "解析実行" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "資産タブ" }));
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解析実行" })).toBeEnabled();
    expect(screen.queryByText("該当する資産がない。")).toBeNull();
    // 再取得は起こらない。
    expect(runScan).toHaveBeenCalledTimes(1);
    expect(runLint).toHaveBeenCalledTimes(1);
  });

  it("名前フィルタで一覧を絞る", async () => {
    renderExplorer();
    await importAndRun();
    fireEvent.change(screen.getByRole("textbox", { name: "名前でフィルタ" }), { target: { value: "SYKD0" } });
    expect(screen.getByRole("button", { name: /SYKD010\.jcl/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /SYK001\.cbl/ })).toBeNull();
  });

  it("種別チップ(単一選択のラジオ)で一覧を絞る", async () => {
    renderExplorer();
    await importAndRun();
    const chip = screen.getByRole("radio", { name: "コピー句" });
    fireEvent.click(chip);
    expect(screen.getByRole("radio", { name: "コピー句" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "すべて" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("button", { name: /SYKCPY1\.cpy/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /SYK001\.cbl/ })).toBeNull();
  });

  it("行選択で右ペインに詳細と文字コード選択を出す", async () => {
    renderExplorer();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    const detail = screen.getByRole("complementary", { name: "資産の詳細と文字コード" });
    expect(within(detail).getByText("cobol/SYK001.cbl")).toBeInTheDocument();
    expect(within(detail).getByRole("combobox", { name: "文字コード" })).toHaveValue("自動判定: Shift_JIS");
    // 選択に続くプレビュー取得の完了まで待ち、非同期の状態更新をテスト内に収める。
    await waitFor(() => expect(readSourceText).toHaveBeenCalledTimes(1));
  });

  it("行選択でデコードプレビューを readSourceText の実データから出す", async () => {
    renderExplorer();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    await waitFor(() =>
      expect(readSourceText).toHaveBeenCalledWith({
        inputDir: SELECTED_DIR,
        path: "cobol/SYK001.cbl",
        // 検出コードページは engine の charset 名(windows-31j)をそのまま main へ渡す。
        codepage: "windows-31j",
        maxLines: 5,
      }),
    );
    expect(await screen.findByText("001000 IDENTIFICATION DIVISION.")).toBeInTheDocument();
  });

  it("文字コードの手動指定でプレビューを取り直し、復号非対応を明示する", async () => {
    renderExplorer();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    await waitFor(() => expect(readSourceText).toHaveBeenCalledTimes(1));

    readSourceText.mockResolvedValue({
      text: "",
      codepage: "CP930",
      truncated: false,
      unsupported: true,
    });
    fireEvent.change(screen.getByRole("combobox", { name: "文字コード" }), {
      target: { value: "手動: EBCDIC CP930" },
    });
    await waitFor(() =>
      expect(readSourceText).toHaveBeenLastCalledWith({
        inputDir: SELECTED_DIR,
        path: "cobol/SYK001.cbl",
        codepage: "CP930",
        maxLines: 5,
      }),
    );
    expect(await screen.findByText(/表示に対応していない/)).toBeInTheDocument();
  });

  it("プレビューの読取失敗は理由を示す", async () => {
    readSourceText.mockRejectedValue(new Error("資産フォルダの外にあるため読み取れません"));
    renderExplorer();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    expect(await screen.findByText(/資産フォルダの外にあるため読み取れません/)).toBeInTheDocument();
  });

  it("文字コードの手動指定は次回の解析実行の codepageOverrides に反映する", async () => {
    renderExplorer();
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    fireEvent.change(screen.getByRole("combobox", { name: "文字コード" }), {
      target: { value: "手動: EBCDIC CP930" },
    });
    fireEvent.click(screen.getByRole("button", { name: "解析実行" }));
    await waitFor(() => expect(runScan).toHaveBeenCalledTimes(2));
    expect(runScan.mock.calls[1][0].codepageOverrides).toEqual({ "cobol/SYK001.cbl": "CP930" });
  });

  it("scan が非ゼロ終了なら部分的成功の警告を出しつつ一覧を表示する", async () => {
    runScan.mockResolvedValue({ ...scanResult, exitCode: 1 });
    renderExplorer();
    await importAndRun();
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("一部の資産で構文解析に失敗"));
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toBeInTheDocument();
  });

  it("scan の起動が失敗したら失敗理由をバナーに出す", async () => {
    runScan.mockRejectedValue(new Error("入力フォルダが見つかりません"));
    renderExplorer();
    await importFolder();
    fireEvent.click(screen.getByRole("button", { name: "解析実行" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("入力フォルダが見つかりません"));
  });

  it("設定で無効化したルールを lint と sql-advise の両方へ渡す", async () => {
    renderExplorer(seedState({ rulesDisabled: { S001: true, R009: true } }));
    await importAndRun();
    await waitFor(() => expect(runSqlAdvise).toHaveBeenCalledTimes(1));
    expect(runLint.mock.calls[0][0].disabledRules).toEqual(["R009", "S001"]);
    expect(runSqlAdvise.mock.calls[0][0].disabledRules).toEqual(["R009", "S001"]);
  });

  it("既定の文字コードは検出に失敗した資産の選択欄とプレビューの初期値になる", async () => {
    renderExplorer(seedState({ defaultEncoding: "手動: Shift_JIS" }));
    await importAndRun();
    // SYKENC1.cbl は codepage=null(復号失敗)であり、手動指定を持たない。
    fireEvent.click(screen.getByRole("button", { name: /SYKENC1\.cbl/ }));
    expect(screen.getByRole("combobox", { name: "文字コード" })).toHaveValue("手動: Shift_JIS");
    await waitFor(() =>
      expect(readSourceText).toHaveBeenLastCalledWith({
        inputDir: SELECTED_DIR,
        path: "cobol/SYKENC1.cbl",
        codepage: "Shift_JIS",
        maxLines: 5,
      }),
    );
  });

  it("既定の文字コードは検出できた資産の選択欄を変えない", async () => {
    renderExplorer(seedState({ defaultEncoding: "手動: EBCDIC CP930" }));
    await importAndRun();
    fireEvent.click(screen.getByRole("button", { name: /SYK001\.cbl/ }));
    expect(screen.getByRole("combobox", { name: "文字コード" })).toHaveValue("自動判定: Shift_JIS");
    await waitFor(() => expect(readSourceText).toHaveBeenCalled());
  });

  it("lint の失敗は資産一覧を保ったまま指摘の取得失敗として示し、件数を 0 と見せない", async () => {
    runLint.mockRejectedValue(new Error("lint が異常終了しました"));
    renderApp();
    await importAndRun();
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("指摘の取得に失敗した"));
    expect(screen.getByRole("alert")).toHaveTextContent("lint が異常終了しました");
    expect(screen.getByRole("button", { name: /SYK001\.cbl/ })).toBeInTheDocument();
    expect(screen.getByTestId("status")).toHaveTextContent("指摘 ―");
    expect(screen.getByTestId("status")).not.toHaveTextContent("指摘 0");
  });
});

describe("ExplorerScreen の詳細ペインの幅", () => {
  /** 詳細ペインへ渡っている幅。 */
  function detailWidth(): string {
    const explorer = document.querySelector(".ci-explorer");
    if (explorer === null) {
      throw new Error("資産エクスプローラーの枠が無い");
    }
    return (explorer as HTMLElement).style.getPropertyValue("--ci-explorer-detail-w");
  }

  it("一覧と詳細ペインの境界に分割ハンドルを置く", () => {
    renderExplorer();
    const handle = screen.getByRole("separator", { name: "資産の詳細ペインの幅" });
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", String(SPLIT_PANES.explorerDetail.initial));
    expect(handle).toHaveAttribute("aria-valuemin", String(SPLIT_PANES.explorerDetail.min));
    expect(handle).toHaveAttribute("aria-valuemax", String(SPLIT_PANES.explorerDetail.max));
    expect(detailWidth()).toBe(`${SPLIT_PANES.explorerDetail.initial}px`);
  });

  it("← キーで詳細ペインを広げ、Home キーで下限まで詰める", () => {
    renderExplorer();
    const handle = screen.getByRole("separator", { name: "資産の詳細ペインの幅" });
    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(detailWidth()).toBe(`${SPLIT_PANES.explorerDetail.initial + 24}px`);
    fireEvent.keyDown(handle, { key: "Home" });
    expect(detailWidth()).toBe(`${SPLIT_PANES.explorerDetail.min}px`);
  });
});
