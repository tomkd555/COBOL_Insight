import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ReportScreen } from "./ReportScreen";
import { AppStateProvider, useAppDispatch, useAppState } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import {
  SAMPLE_REPORT_HTML,
  SAMPLE_REPORT_SUMMARY,
  SAMPLE_REPORT_TEXT,
  reportResult,
} from "./fixtures";
import type { CobolInsightApi, ReportRequest } from "../../../../shared/engine-api";

const INPUT_DIR = "C:\\資産\\SYK";
const DB_PATH = "C:\\proj\\cobol-insight.db";
const HTML_PATH = "C:\\proj\\cobol-insight-report.html";
const TEXT_PATH = "C:\\proj\\cobol-insight-report.txt";
/** main が userData 基準で決めるルール設定ファイルの位置。 */
const RULE_CONFIG_PATH = "C:\\data\\rules-config.json";

let runReport: ReturnType<typeof vi.fn>;
let readReportHtml: ReturnType<typeof vi.fn>;
let readReportText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  runReport = vi.fn().mockResolvedValue(reportResult());
  readReportHtml = vi.fn().mockResolvedValue(SAMPLE_REPORT_HTML);
  readReportText = vi.fn().mockResolvedValue(SAMPLE_REPORT_TEXT);
  window.cobolInsight = {
    getOutputPaths: vi.fn().mockResolvedValue({ ruleConfig: RULE_CONFIG_PATH }),
    runReport,
    readReportHtml,
    readReportText,
  } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** 解析実行が済んだ状態(mode=results)。 */
function analyzedState(overrides: Partial<AppState> = {}): AppState {
  return {
    ...initialState,
    mode: "results",
    screen: "report",
    project: { inputDir: INPUT_DIR, dbPath: DB_PATH, copybookPaths: ["D:\\共通コピー句"] },
    ...overrides,
  };
}

function renderReport(seed?: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <ReportScreen />
    </AppStateProvider>,
  );
}

/** タブ移動で画面を捨てる器。形式と出力先が AppState に残ることを観測する。 */
function TabHarness(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  return (
    <>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "explorer" })}>
        資産タブ
      </button>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "report" })}>
        レポートタブ
      </button>
      {state.screen === "report" ? <ReportScreen /> : <p>資産エクスプローラー</p>}
    </>
  );
}

/** 書き出しを実行してプレビューが出るまで待つ。 */
async function writeAndWait(): Promise<void> {
  fireEvent.click(screen.getByRole("button", { name: "レポートを書き出す" }));
  await waitFor(() =>
    expect(screen.getByTitle("HTML レポートのプレビュー")).toBeInTheDocument(),
  );
}

describe("ReportScreen の4状態", () => {
  it("解析未実行は空状態を出し report を起動しない", () => {
    renderReport();
    expect(screen.getByText("出力できる解析結果がありません")).toBeInTheDocument();
    expect(runReport).not.toHaveBeenCalled();
  });

  it("解析実行中は実行中インジケータを出す", () => {
    renderReport({ ...initialState, mode: "running" });
    expect(screen.getByRole("status")).toHaveTextContent("レポートを準備しています…");
  });

  it("資産フォルダ未選択は専用の案内を出す", () => {
    renderReport({ ...initialState, mode: "results" });
    expect(screen.getByText("資産フォルダが選ばれていません")).toBeInTheDocument();
  });

  it("解析済みでは書き出しを待ち、自動では起動しない", () => {
    renderReport(analyzedState());
    expect(screen.getByText("レポートはまだ書き出されていません")).toBeInTheDocument();
    expect(runReport).not.toHaveBeenCalled();
  });

  it("書き出しの失敗は理由を示す", async () => {
    runReport.mockRejectedValue(new Error("出力先に書き込み権限がありません"));
    renderReport(analyzedState());
    fireEvent.click(screen.getByRole("button", { name: "レポートを書き出す" }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("出力先に書き込み権限がありません"),
    );
    expect(screen.getByText("レポートはまだ書き出されていません")).toBeInTheDocument();
  });

  it("成果物の読取の失敗も理由を示す", async () => {
    readReportText.mockRejectedValue(new Error("テキストが無い"));
    renderReport(analyzedState());
    fireEvent.click(screen.getByRole("button", { name: "レポートを書き出す" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("テキストが無い"));
  });
});

describe("ReportScreen の書き出し", () => {
  it("engine を出力先ファイルつきで起動する", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    const request = runReport.mock.calls[0][0] as ReportRequest;
    expect(request).toEqual({
      inputDir: INPUT_DIR,
      copybookPaths: ["D:\\共通コピー句"],
      db: DB_PATH,
      htmlFile: HTML_PATH,
      textFile: TEXT_PATH,
      ruleConfigFile: RULE_CONFIG_PATH,
    });
  });

  it("ルールの有効・無効は設定ファイルで渡し、無効にした件数を画面へ示す", async () => {
    renderReport(analyzedState({ rulesDisabled: { R009: true, R025: false, R002: true } }));
    await writeAndWait();
    const request = runReport.mock.calls[0][0] as ReportRequest;
    expect(request.ruleConfigFile).toBe(RULE_CONFIG_PATH);
    expect(screen.getByText(/無効化した 2 件のルールは検出から除く/)).toBeInTheDocument();
  });

  it("出力先フォルダを変えるとその配下へ書き出す", async () => {
    renderReport(analyzedState());
    fireEvent.change(screen.getByLabelText("出力先フォルダ"), {
      target: { value: "D:\\出力" },
    });
    await writeAndWait();
    const request = runReport.mock.calls[0][0] as ReportRequest;
    expect(request.htmlFile).toBe("D:\\出力\\cobol-insight-report.html");
    expect(request.textFile).toBe("D:\\出力\\cobol-insight-report.txt");
  });

  it("出力先フォルダの既定はプロジェクトファイルの置き場所である", () => {
    renderReport(analyzedState());
    expect(screen.getByLabelText("出力先フォルダ")).toHaveValue("C:\\proj\\");
  });

  it("engine が書いた HTML とテキストの両方を読む", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    expect(readReportHtml).toHaveBeenCalledWith(HTML_PATH);
    expect(readReportText).toHaveBeenCalledWith(TEXT_PATH);
  });
});

describe("ReportScreen の状態の保持", () => {
  it("形式と出力先はタブを移動して戻っても保たれる", async () => {
    render(
      <AppStateProvider initialState={analyzedState()}>
        <TabHarness />
      </AppStateProvider>,
    );
    fireEvent.change(screen.getByLabelText("出力先フォルダ"), { target: { value: "D:\\出力" } });
    fireEvent.click(screen.getByRole("button", { name: "テキスト" }));

    fireEvent.click(screen.getByRole("button", { name: "資産タブ" }));
    fireEvent.click(screen.getByRole("button", { name: "レポートタブ" }));
    expect(screen.getByLabelText("出力先フォルダ")).toHaveValue("D:\\出力");
    expect(screen.getByRole("button", { name: "テキスト" })).toHaveAttribute("aria-pressed", "true");
  });

  it("出力先を空にすると既定のプロジェクトファイルの置き場所へ戻す", async () => {
    renderReport(analyzedState({ reportPath: "D:\\出力" }));
    expect(screen.getByLabelText("出力先フォルダ")).toHaveValue("D:\\出力");
    fireEvent.change(screen.getByLabelText("出力先フォルダ"), { target: { value: "" } });
    expect(screen.getByLabelText("出力先フォルダ")).toHaveValue("C:\\proj\\");
    await writeAndWait();
    expect((runReport.mock.calls[0][0] as ReportRequest).htmlFile).toBe(HTML_PATH);
  });
});

describe("ReportScreen のプレビュー", () => {
  it("HTML は sandbox 付き iframe の srcdoc へ入れ、スクリプトを許さない", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    const frame = screen.getByTitle("HTML レポートのプレビュー");
    expect(frame).toHaveAttribute("sandbox", "");
    expect(frame).toHaveAttribute("srcdoc", SAMPLE_REPORT_HTML);
  });

  it("形式を切り替えるとテキストを等幅で示し、engine を再起動しない", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    fireEvent.click(screen.getByRole("button", { name: "テキスト" }));
    expect(screen.getByLabelText("テキストレポートのプレビュー")).toHaveTextContent(
      "COBOL Insight 解析レポート",
    );
    expect(screen.queryByTitle("HTML レポートのプレビュー")).not.toBeInTheDocument();
    expect(runReport).toHaveBeenCalledTimes(1);
  });

  it("表示中のファイルのパスを示す", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    expect(screen.getByText(`プレビュー ― ${HTML_PATH}`)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "テキスト" }));
    expect(screen.getByText(`プレビュー ― ${TEXT_PATH}`)).toBeInTheDocument();
  });

  it("サマリの実件数を指標として示す", async () => {
    renderReport(analyzedState());
    await writeAndWait();
    expect(screen.getByText("資産")).toBeInTheDocument();
    expect(screen.getByText("19")).toBeInTheDocument();
    expect(screen.getByText("24")).toBeInTheDocument();
    expect(screen.getByText("ノード 33 ・ エッジ 41")).toBeInTheDocument();
  });

  it("終了コードが非ゼロなら警告を添えたうえで本体を示す", async () => {
    runReport.mockResolvedValue(reportResult({ ...SAMPLE_REPORT_SUMMARY, exitCode: 2 }));
    renderReport(analyzedState());
    await writeAndWait();
    expect(screen.getByRole("alert")).toHaveTextContent("解析できていない");
    expect(screen.getByTitle("HTML レポートのプレビュー")).toBeInTheDocument();
  });
});
