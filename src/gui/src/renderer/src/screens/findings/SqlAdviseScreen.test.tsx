import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { SqlAdviseScreen } from "./SqlAdviseScreen";
import { SAMPLE_SQL_FINDINGS } from "./fixtures";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";
import { ScreenRouter } from "../ScreenRouter";
import { AppStateProvider, useAppState, useAppDispatch } from "../../state/AppStateContext";
import { SPLIT_PANES, initialState, type AppState } from "../../state/appState";
import type { FakeEditor } from "../viewer/monacoFake";
import type { AssetInventoryItem, CobolInsightApi, SarifFinding } from "../../../../shared/engine-api";

/**
 * 下段の原本は Monaco が描く。jsdom では動かないため、描画ライブラリの入口を偽物へ差し替える
 * (形はソースビューアと共有する)。
 */
const monacoStore = vi.hoisted(() => ({ editors: [] as FakeEditor[] }));

vi.mock("../../vendor/monacoEditor", async () => {
  const { createMonacoFake } = await import("../viewer/monacoFake");
  return { monacoEditor: () => createMonacoFake(monacoStore) };
});

/** 指摘の対象ファイルを資産一覧へ足す(本文の復号に用いるコードページの供給源)。 */
const SQL_ASSETS: readonly AssetInventoryItem[] = [
  ...SAMPLE_INVENTORY,
  { id: 7, path: "cobol/SYK006.cbl", name: "SYK006.cbl", type: "PROGRAM", codepage: "windows-31j", byteSize: 7371, findingCount: 0 },
  { id: 8, path: "cobol/SYK007.cbl", name: "SYK007.cbl", type: "PROGRAM", codepage: "UTF-8", byteSize: 3538, findingCount: 0 },
];

/** SYK006.cbl の 145 行目に EXEC SQL 文がある原本を模した本文。 */
const SOURCE_TEXT = [
  ...Array.from({ length: 144 }, (_, i) => `00${String(i + 1).padStart(4, "0")} * 行 ${i + 1}`),
  "014500     EXEC SQL",
  "014600          SELECT * FROM SYKDB.ZAIKOM",
  "014700     END-EXEC.",
  "014800     IF SQLCODE NOT = 0",
].join("\n");

let runSqlAdvise: ReturnType<typeof vi.fn>;
let readSarif: ReturnType<typeof vi.fn>;
let readSourceText: ReturnType<typeof vi.fn>;

beforeEach(() => {
  monacoStore.editors = [];
  // この画面は CLI を起動しない。呼ばれたことを検出するためだけにモックを置く。
  runSqlAdvise = vi.fn();
  readSarif = vi.fn();
  readSourceText = vi.fn().mockResolvedValue({
    text: SOURCE_TEXT,
    codepage: "Shift_JIS",
    truncated: false,
    unsupported: false,
  });
  window.cobolInsight = { runSqlAdvise, readSarif, readSourceText } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

function Probe(): ReactElement {
  const state = useAppState();
  return <div data-testid="probe">{`${state.screen}|${state.sourceFile}|${state.sourceLine ?? ""}`}</div>;
}

function renderSql(seed: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <SqlAdviseScreen />
      <Probe />
    </AppStateProvider>,
  );
}

/**
 * 画面ルーティングを含むラッパー。タブ移動で画面がアンマウントされる実アプリと同じ条件を作り、
 * フィルタ状態が保たれることを観測する。
 */
function Harness(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  return (
    <>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "findings" })}>
        指摘一覧タブ
      </button>
      <button type="button" onClick={() => dispatch({ type: "NAV", screen: "sql" })}>
        SQL指摘タブ
      </button>
      <ScreenRouter screen={state.screen} />
    </>
  );
}

/** 解析実行が sql-lint の SARIF を収めた状態。 */
const resultsSeed: AppState = {
  ...initialState,
  screen: "sql",
  mode: "results",
  project: { inputDir: "C:\\資産\\SYK", dbPath: "proj.db", copybookPaths: [] },
  inventory: { status: "ready", items: SQL_ASSETS },
  sqlAdvice: { status: "ready", items: SAMPLE_SQL_FINDINGS },
};
const emptySeed: AppState = { ...initialState, screen: "sql", mode: "empty" };

/** 一覧のデータ行(ルール ID で始まる名前を持つ row)を返す。 */
function dataRows(): HTMLElement[] {
  return screen.getAllByRole("row", { name: /^S\d{3} / });
}

describe("SqlAdviseScreen(SQL指摘)", () => {
  it("empty では「表示できる SQL がありません」を出し、sql-lint は起動しない", () => {
    renderSql(emptySeed);
    expect(screen.getByRole("region", { name: "表示できる SQL がありません" })).toBeInTheDocument();
    expect(runSqlAdvise).not.toHaveBeenCalled();
  });

  it("解析実行が収めた SARIF から S001〜S006 を一覧化し、sql-lint を再起動しない(ゲート経路)", () => {
    renderSql(resultsSeed);
    expect(dataRows()).toHaveLength(6);
    expect(screen.getByRole("table", { name: "SQL指摘一覧" })).toBeInTheDocument();
    expect(runSqlAdvise).not.toHaveBeenCalled();
    expect(readSarif).not.toHaveBeenCalled();
  });

  it("判定範囲の説明を上部に出す(design scSql)", () => {
    renderSql(resultsSeed);
    expect(screen.getByText(/構文レベルの最適化の指摘（S001〜S006）を提示する/)).toBeInTheDocument();
  });

  it("解析済みで 0 件でも空状態を出す", () => {
    renderSql({ ...resultsSeed, sqlAdvice: { status: "ready", items: [] } });
    expect(screen.getByRole("region", { name: "表示できる SQL がありません" })).toBeInTheDocument();
  });

  it("sql-lint が失敗した場合は 0 件と見せず、失敗と理由を示す", () => {
    renderSql({
      ...resultsSeed,
      mode: "error",
      sqlAdvice: { status: "error", message: "sql-lint が SARIF を出力しませんでした。" },
    });
    const region = screen.getByRole("region", { name: "SQL指摘を取得できませんでした" });
    expect(region).toHaveTextContent("sql-lint が SARIF を出力しませんでした。");
    expect(screen.queryByRole("region", { name: "表示できる SQL がありません" })).toBeNull();
  });

  it("SARIF を未取得のまま解析済みになった場合は未取得として示す", () => {
    renderSql({ ...resultsSeed, sqlAdvice: { status: "none" } });
    expect(screen.getByRole("region", { name: "SQL指摘をまだ取得していません" })).toBeInTheDocument();
  });

  it("重大度チップで絞る(高=S002/S003 のみ)", () => {
    renderSql(resultsSeed);
    const chips = within(screen.getByRole("group", { name: "重大度フィルタ" }));
    fireEvent.click(chips.getByRole("button", { name: /中/ }));
    fireEvent.click(chips.getByRole("button", { name: /低/ }));
    // 高(S002/S003)だけ残る。
    expect(dataRows()).toHaveLength(2);
    expect(screen.getByRole("row", { name: /^S002 / })).toBeInTheDocument();
    expect(screen.queryByRole("row", { name: /^S001 / })).toBeNull();
  });

  it("フィルタはタブを移動して戻っても保たれる", () => {
    render(
      <AppStateProvider initialState={resultsSeed}>
        <Harness />
      </AppStateProvider>,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "ルール" }), { target: { value: "S002" } });
    expect(dataRows()).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "指摘一覧タブ" }));
    fireEvent.click(screen.getByRole("button", { name: "SQL指摘タブ" }));
    expect(screen.getByRole("combobox", { name: "ルール" })).toHaveValue("S002");
    expect(dataRows()).toHaveLength(1);
  });

  it("行を選ぶと原本から SQL 本文を読んで詳細ペインへ出す", async () => {
    renderSql(resultsSeed);
    fireEvent.click(screen.getByRole("row", { name: /^S001 / }));
    await waitFor(() =>
      expect(readSourceText).toHaveBeenCalledWith({
        inputDir: "C:\\資産\\SYK",
        path: "cobol/SYK006.cbl",
        // 資産一覧の検出値(engine の charset 名)をそのまま main へ渡す。
        codepage: "windows-31j",
      }),
    );
    const detail = within(screen.getByRole("complementary", { name: "SQL 文と最適化の指摘の詳細" }));
    // 本文は EXEC SQL から END-EXEC までの 3 行で、その先の行は含めない。
    expect(await detail.findByText(/014500\s+EXEC SQL/)).toBeInTheDocument();
    expect(detail.getByText(/014600\s+SELECT \* FROM SYKDB\.ZAIKOM/)).toBeInTheDocument();
    expect(detail.getByText(/014700\s+END-EXEC\./)).toBeInTheDocument();
    expect(detail.queryByText(/014800/)).toBeNull();
  });

  it("選択した行を aria-current と修飾子クラスで示す", async () => {
    renderSql(resultsSeed);
    const row = screen.getByRole("row", { name: /^S001 / });
    expect(row).not.toHaveAttribute("aria-current");
    fireEvent.click(row);
    expect(screen.getByRole("row", { name: /^S001 / })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("row", { name: /^S001 / })).toHaveClass("ci-findings-table__row--selected");
    expect(screen.getByRole("row", { name: /^S002 / })).not.toHaveAttribute("aria-current");
    // 行選択が起こす readSourceText の解決を待ち、act の外での状態更新を防ぐ。
    await waitFor(() => expect(readSourceText).toHaveBeenCalled());
  });

  it("詳細ペインは同じ位置の指摘を全件示す(1 つの SQL 文に複数の指摘)", async () => {
    renderSql(resultsSeed);
    fireEvent.click(screen.getByRole("row", { name: /^S001 / }));
    const detail = within(screen.getByRole("complementary", { name: "SQL 文と最適化の指摘の詳細" }));
    expect(await detail.findByText("最適化の指摘 3 件")).toBeInTheDocument();
    // cobol/SYK006.cbl:145 には S001・S004・S006 が付く。
    expect(detail.getByText("S001")).toBeInTheDocument();
    expect(detail.getByText("S004")).toBeInTheDocument();
    expect(detail.getByText("S006")).toBeInTheDocument();
    expect(detail.queryByText("S002")).toBeNull();
  });

  it("SQL 文の総数は提示しない(SARIF から厳密に導けない)", () => {
    renderSql(resultsSeed);
    expect(screen.queryByText(/SQL 文 \d+ 件/)).toBeNull();
  });

  it("詳細ペインの「該当ソース行へ →」で viewer へ該当行付きで遷移する", async () => {
    renderSql(resultsSeed);
    fireEvent.click(screen.getByRole("row", { name: /^S002 / }));
    fireEvent.click(await screen.findByRole("button", { name: "該当ソース行へ" }));
    expect(screen.getByTestId("probe")).toHaveTextContent("viewer|cobol/SYK007.cbl|84");
  });

  it("本文の読取失敗は理由を示す", async () => {
    readSourceText.mockRejectedValue(new Error("資産フォルダの外にあるため読み取れません"));
    renderSql(resultsSeed);
    fireEvent.click(screen.getByRole("row", { name: /^S001 / }));
    // 同じ本文から作る下段の原本も理由を示すため、詳細ペインの中に限って確かめる。
    const detail = within(screen.getByRole("complementary", { name: "SQL 文と最適化の指摘の詳細" }));
    expect(await detail.findByText(/資産フォルダの外にあるため読み取れません/)).toBeInTheDocument();
  });

  it("復号非対応のコードページは本文を空と見せず、利用者向けの表記で非対応として示す", async () => {
    // main は復号非対応のとき、要求した engine の charset 名をそのまま返す。
    readSourceText.mockResolvedValue({ text: "", codepage: "x-IBM930", truncated: false, unsupported: true });
    renderSql(resultsSeed);
    fireEvent.click(screen.getByRole("row", { name: /^S001 / }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("本文の表示に対応していない");
    expect(alert).toHaveTextContent("EBCDIC CP930");
    expect(alert).not.toHaveTextContent("x-IBM930");
  });

  it("同一位置に同一ルールの指摘が2件あっても key が衝突せず両方を描く", async () => {
    // 1 行に EXEC SQL 文が2つ並ぶと、指摘の位置(文の開始行・1桁)が一致して ruleId まで重なる。
    const duplicated: readonly SarifFinding[] = [
      { ruleId: "S002", level: "error", message: "1件目の非SARGableな述語がある。", file: "cobol/SYK007.cbl", startLine: 84, startColumn: 1 },
      { ruleId: "S002", level: "error", message: "2件目の非SARGableな述語がある。", file: "cobol/SYK007.cbl", startLine: 84, startColumn: 1 },
    ];
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    renderSql({ ...resultsSeed, sqlAdvice: { status: "ready", items: duplicated } });
    fireEvent.click(dataRows()[0]);
    const detail = within(screen.getByRole("complementary", { name: "SQL 文と最適化の指摘の詳細" }));
    expect(await detail.findByText("最適化の指摘 2 件")).toBeInTheDocument();
    expect(detail.getByText("1件目の非SARGableな述語がある。")).toBeInTheDocument();
    expect(detail.getByText("2件目の非SARGableな述語がある。")).toBeInTheDocument();
    // key の重複は React が描画を「重複または省略」しうる未定義動作として警告する。
    const warnings = consoleError.mock.calls.map((call) => String(call[0]));
    expect(warnings.filter((message) => message.includes("same key"))).toEqual([]);
    consoleError.mockRestore();
  });

  it("設定の重大度しきい値より低い指摘を一覧から除く", () => {
    renderSql({ ...resultsSeed, severityThreshold: "medium" });
    // 高(S002/S003)と中(S001/S004)だけが残り、低(S005/S006)は消える。
    expect(dataRows()).toHaveLength(4);
    expect(screen.queryByRole("row", { name: /^S005 / })).toBeNull();
    expect(screen.queryByRole("row", { name: /^S006 / })).toBeNull();
  });

  it("しきい値で隠れている重大度のチップは操作できない", () => {
    renderSql({ ...resultsSeed, severityThreshold: "high" });
    const chips = within(screen.getByRole("group", { name: "重大度フィルタ" }));
    expect(chips.getByRole("button", { name: /高/ })).toBeEnabled();
    expect(chips.getByRole("button", { name: /中/ })).toBeDisabled();
  });

  it("未選択のうちは原本を読まず、選択を促す", () => {
    renderSql(resultsSeed);
    expect(screen.getByText(/SQL 本文と指摘の詳細を表示する/)).toBeInTheDocument();
    expect(readSourceText).not.toHaveBeenCalled();
  });
});

describe("SqlAdviseScreen の詳細ペインの幅", () => {
  /** 詳細ペインへ渡っている幅。寸法は下段の容れ物が持つ(一覧は上に縦分割で載る)。 */
  function detailWidth(): string {
    const body = document.querySelector(".ci-sql__body");
    if (body === null) {
      throw new Error("SQL指摘の下段が無い");
    }
    return (body as HTMLElement).style.getPropertyValue("--ci-sql-detail-w");
  }

  it("原本と詳細ペインの境界に分割ハンドルを置く", () => {
    renderSql(resultsSeed);
    const handle = screen.getByRole("separator", { name: "SQL 文と指摘の詳細ペインの幅" });
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", String(SPLIT_PANES.sqlDetail.initial));
    expect(handle).toHaveAttribute("aria-valuemin", String(SPLIT_PANES.sqlDetail.min));
    // 可動上限はコンテナの実寸から導くため、レイアウトを持たない環境では示さない
    // (導出そのものは SplitHandle.test.tsx が確かめる)。
    expect(detailWidth()).toBe(`${SPLIT_PANES.sqlDetail.initial}px`);
  });

  it("→ キーで詳細ペインを狭め、Home キーで下限まで詰める", () => {
    renderSql(resultsSeed);
    const handle = screen.getByRole("separator", { name: "SQL 文と指摘の詳細ペインの幅" });
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(detailWidth()).toBe(`${SPLIT_PANES.sqlDetail.initial - 24}px`);
    fireEvent.keyDown(handle, { key: "Home" });
    expect(detailWidth()).toBe(`${SPLIT_PANES.sqlDetail.min}px`);
  });
});
