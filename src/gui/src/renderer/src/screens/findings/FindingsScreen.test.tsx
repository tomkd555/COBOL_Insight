import {
  render,
  screen,
  fireEvent,
  createEvent,
  within,
  type RenderResult,
} from "@testing-library/react";
import type { ReactElement } from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { FindingsScreen } from "./FindingsScreen";
import { SAMPLE_FINDINGS } from "./fixtures";
import { AppStateProvider, useAppState } from "../../state/AppStateContext";
import { initialState, type AppState } from "../../state/appState";
import type { CobolInsightApi } from "../../../../shared/engine-api";

let runLint: ReturnType<typeof vi.fn>;
let readSarif: ReturnType<typeof vi.fn>;

beforeEach(() => {
  // この画面は CLI を起動しない。呼ばれたことを検出するためだけにモックを置く。
  runLint = vi.fn();
  readSarif = vi.fn();
  window.cobolInsight = { runLint, readSarif } as unknown as CobolInsightApi;
});

afterEach(() => {
  delete (window as { cobolInsight?: CobolInsightApi }).cobolInsight;
});

/** JUMP・NAV の結果を観測するプローブ。現在の screen / sourceFile / sourceLine を書き出す。 */
function Probe(): ReactElement {
  const state = useAppState();
  return <div data-testid="probe">{`${state.screen}|${state.sourceFile}|${state.sourceLine ?? ""}`}</div>;
}

function renderFindings(seed: AppState): RenderResult {
  return render(
    <AppStateProvider initialState={seed}>
      <FindingsScreen />
      <Probe />
    </AppStateProvider>,
  );
}

/** 解析実行が lint の SARIF を収めた状態。この画面はここから一覧を描く。 */
const resultsSeed: AppState = {
  ...initialState,
  screen: "findings",
  mode: "results",
  findings: { status: "ready", items: SAMPLE_FINDINGS },
};
const emptySeed: AppState = { ...initialState, screen: "findings", mode: "empty" };

/** 結果表のデータ行(ルール ID で始まる名前を持つ row)を出現順に返す。 */
function dataRows(): HTMLElement[] {
  return screen.getAllByRole("row", { name: /^R\d{3} / });
}

/** データ行の名前を出現順に返す。 */
function rowNames(): string[] {
  return dataRows().map((el) => el.getAttribute("aria-label") ?? "");
}

describe("FindingsScreen(指摘一覧)", () => {
  it("empty(未解析)ではエクスプローラー誘導を出し、lint は起動しない", () => {
    renderFindings(emptySeed);
    expect(screen.getByRole("region", { name: "解析がまだ実行されていません" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "資産エクスプローラーへ" })).toBeInTheDocument();
    expect(runLint).not.toHaveBeenCalled();
  });

  it("未解析の誘導ボタンで資産エクスプローラーへ遷移する", () => {
    renderFindings(emptySeed);
    fireEvent.click(screen.getByRole("button", { name: "資産エクスプローラーへ" }));
    expect(screen.getByTestId("probe")).toHaveTextContent("explorer|");
  });

  it("解析実行が収めた SARIF から一覧を生成し、lint を再起動しない(ゲート経路)", () => {
    renderFindings(resultsSeed);
    // 全 10 件が行になる。
    expect(dataRows()).toHaveLength(10);
    expect(runLint).not.toHaveBeenCalled();
    expect(readSarif).not.toHaveBeenCalled();
  });

  it("解析済みで 0 件なら「指摘はありません」を出す(空 2 バリアント目)", () => {
    renderFindings({ ...resultsSeed, findings: { status: "ready", items: [] } });
    expect(screen.getByRole("region", { name: "指摘はありません" })).toBeInTheDocument();
  });

  it("lint が失敗した場合は 0 件と見せず、失敗と理由を示す", () => {
    renderFindings({
      ...resultsSeed,
      mode: "error",
      findings: { status: "error", message: "lint が異常終了しました（終了コード 2）" },
    });
    const region = screen.getByRole("region", { name: "指摘を取得できませんでした" });
    expect(region).toHaveTextContent("lint が異常終了しました（終了コード 2）");
    expect(screen.queryByRole("region", { name: "指摘はありません" })).toBeNull();
  });

  it("SARIF を未取得のまま解析済みになった場合は未取得として示す", () => {
    renderFindings({ ...resultsSeed, findings: { status: "none" } });
    expect(screen.getByRole("region", { name: "指摘をまだ取得していません" })).toBeInTheDocument();
  });

  it("修正案ありルール(R017/R018/R004)には diff ありバッジを出す", () => {
    renderFindings(resultsSeed);
    const r017 = screen.getByRole("row", { name: /^R017 /  });
    expect(within(r017).getByText("差分あり")).toBeInTheDocument();
    const r008 = screen.getByRole("row", { name: /^R008 /  });
    expect(within(r008).queryByText("差分あり")).toBeNull();
    expect(screen.getAllByText("差分あり")).toHaveLength(3); // R017 / R018 / R004
  });

  it("重大度チップ(高)を切ると高の指摘が消える", () => {
    renderFindings(resultsSeed);
    const chips = within(screen.getByRole("group", { name: "重大度フィルタ" }));
    fireEvent.click(chips.getByRole("button", { name: /高/ }));
    expect(screen.queryByRole("row", { name: /^R017 /  })).toBeNull(); // 高
    expect(screen.getByRole("row", { name: /^R008 /  })).toBeInTheDocument(); // 中は残る
  });

  it("ルール選択で 1 ルールへ絞る", () => {
    renderFindings(resultsSeed);
    fireEvent.change(screen.getByRole("combobox", { name: "ルール" }), { target: { value: "R017" } });
    expect(screen.getByRole("row", { name: /^R017 /  })).toBeInTheDocument();
    expect(screen.queryByRole("row", { name: /^R008 /  })).toBeNull();
  });

  it("ファイル選択で 1 ファイルへ絞る", () => {
    renderFindings(resultsSeed);
    fireEvent.change(screen.getByRole("combobox", { name: "ファイル" }), {
      target: { value: "cobol/SYK001.cbl" },
    });
    // SYK001.cbl は R008/R017/R005/R001 の 4 件。
    expect(dataRows()).toHaveLength(4);
    expect(screen.queryByRole("row", { name: /^R018 /  })).toBeNull();
  });

  it("内容テキスト検索で絞る", () => {
    renderFindings(resultsSeed);
    fireEvent.change(screen.getByRole("textbox", { name: "内容" }), { target: { value: "SQLCODE" } });
    expect(dataRows()).toHaveLength(1);
    expect(screen.getByRole("row", { name: /^R018 /  })).toBeInTheDocument();
  });

  it("フィルタで 0 件になると no-hit メッセージを出す(空バリアントとは別)", () => {
    renderFindings(resultsSeed);
    fireEvent.change(screen.getByRole("textbox", { name: "内容" }), { target: { value: "該当しない語XYZ" } });
    expect(screen.getByText("現在のフィルタ条件に一致する指摘はない")).toBeInTheDocument();
  });

  it("行ソート(行)で startLine 昇順になる", () => {
    renderFindings(resultsSeed);
    fireEvent.click(screen.getByRole("button", { name: "行で並べ替え" }));
    // 最小行は SYK003:20 の R002。
    expect(rowNames()[0]).toMatch(/R002/);
  });

  it("行クリックでは選択だけを行い、viewer へは遷移しない", () => {
    renderFindings(resultsSeed);
    const row = screen.getByRole("row", { name: /^R017 /  });
    fireEvent.click(row);
    expect(screen.getByTestId("probe")).toHaveTextContent("findings||");
    expect(row).toHaveAttribute("aria-current", "true");
    expect(row).toHaveClass("ci-findings-table__row--selected");
  });

  it("行は Enter でも選択できる(ジャンプはしない)", () => {
    renderFindings(resultsSeed);
    const row = screen.getByRole("row", { name: /^R017 / });
    fireEvent.keyDown(row, { key: "Enter" });
    expect(row).toHaveAttribute("aria-current", "true");
    expect(screen.getByTestId("probe")).toHaveTextContent("findings||");
  });

  it("Tab 停止は 1 つに集約する(roving tabindex)", () => {
    renderFindings(resultsSeed);
    const rows = screen.getAllByRole("row").filter((row) => row.className.includes("ci-findings-table__row"));
    expect(rows.length).toBeGreaterThan(1);
    expect(rows.filter((row) => row.getAttribute("tabindex") === "0")).toHaveLength(1);
    expect(rows[0]).toHaveAttribute("tabindex", "0");
  });

  it("矢印キーで焦点と選択が次の行へ移る", () => {
    renderFindings(resultsSeed);
    const rows = screen.getAllByRole("row").filter((row) => row.className.includes("ci-findings-table__row"));
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(rows[1]).toHaveAttribute("aria-current", "true");
    expect(rows[1]).toHaveAttribute("tabindex", "0");
    expect(rows[0]).toHaveAttribute("tabindex", "-1");
  });

  it("矢印キーの起点は焦点のある行で、選択中の行ではない", () => {
    // 行を選ばずにジャンプボタンだけを押した場合、焦点は 3 行目にあり選択は無い。
    // 起点を選択に取ると先頭の次(2 行目)へ飛んでしまう。
    renderFindings(resultsSeed);
    const rows = screen.getAllByRole("row").filter((row) => row.className.includes("ci-findings-table__row"));
    const jump = rows[2].querySelector("button");
    expect(jump).not.toBeNull();
    fireEvent.keyDown(rows[2], { key: "ArrowDown" });
    expect(rows[3]).toHaveAttribute("aria-current", "true");
  });

  it("入れ子のジャンプボタンで押した Enter は行の活性化に横取りされない", () => {
    // 行の keydown で preventDefault すると Enter が生む click まで打ち消し、
    // キーボードからジャンプできなくなる。
    renderFindings(resultsSeed);
    const rows = screen.getAllByRole("row").filter((row) => row.className.includes("ci-findings-table__row"));
    const jump = rows[0].querySelector("button") as HTMLButtonElement;
    const event = createEvent.keyDown(jump, { key: "Enter" });
    fireEvent(jump, event);
    expect(event.defaultPrevented).toBe(false);
    expect(rows[0]).not.toHaveAttribute("aria-current");
  });

  it("End で末尾の行へ移る", () => {
    renderFindings(resultsSeed);
    const rows = screen.getAllByRole("row").filter((row) => row.className.includes("ci-findings-table__row"));
    fireEvent.keyDown(rows[0], { key: "End" });
    expect(rows[rows.length - 1]).toHaveAttribute("aria-current", "true");
  });

  it("ファイル・行のセルはジャンプ操作のボタンで、押すと viewer へ該当行付きで遷移する", () => {
    renderFindings(resultsSeed);
    fireEvent.click(screen.getByRole("button", { name: "cobol/SYK001.cbl:85 のソースへジャンプ" }));
    expect(screen.getByTestId("probe")).toHaveTextContent("viewer|cobol/SYK001.cbl|85");
  });

  it("ジャンプ操作のボタンは見た目を CSS の修飾で整え、インラインスタイルを持たない", () => {
    renderFindings(resultsSeed);
    const jump = screen.getByRole("button", { name: "cobol/SYK001.cbl:85 のソースへジャンプ" });
    expect(jump).toHaveClass("ci-findings-table__line", "ci-findings-table__line--jump");
    expect(jump.getAttribute("style")).toBeNull();
  });

  it("一覧を表として意味づけ、6 列の見出しを持つ", () => {
    renderFindings(resultsSeed);
    expect(screen.getByRole("table", { name: "指摘一覧" })).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader")).toHaveLength(6);
  });

  it("ソート状態は列見出しの aria-sort で伝え、ソートボタンへ aria-pressed を流用しない", () => {
    renderFindings(resultsSeed);
    expect(screen.getByRole("columnheader", { name: /重大度/ })).toHaveAttribute("aria-sort", "ascending");
    expect(screen.getByRole("columnheader", { name: /^行/ })).toHaveAttribute("aria-sort", "none");
    expect(screen.getByRole("button", { name: "行で並べ替え" })).not.toHaveAttribute("aria-pressed");

    fireEvent.click(screen.getByRole("button", { name: "行で並べ替え" }));
    expect(screen.getByRole("columnheader", { name: /^行/ })).toHaveAttribute("aria-sort", "ascending");
    expect(screen.getByRole("columnheader", { name: /重大度/ })).toHaveAttribute("aria-sort", "none");
  });

  it("ルールソート(ルール)で ID 昇順になる", () => {
    renderFindings(resultsSeed);
    fireEvent.click(screen.getByRole("button", { name: "ルールで並べ替え" }));
    expect(screen.getByRole("columnheader", { name: /ルール/ })).toHaveAttribute("aria-sort", "ascending");
    // ID 昇順の先頭は R001。
    expect(rowNames()[0]).toMatch(/^R001 /);
  });

  it("同じ見出しを再度押すと向きが反転し、aria-sort が descending になる", () => {
    renderFindings(resultsSeed);
    const sevHeader = screen.getByRole("columnheader", { name: /重大度/ });
    fireEvent.click(screen.getByRole("button", { name: "重大度で並べ替え" }));
    expect(sevHeader).toHaveAttribute("aria-sort", "descending");
    // 昇順(高→中→低→警告)の末尾は警告(R009)のみ。降順はその逆順になるため先頭に来る。
    expect(rowNames()[0]).toMatch(/^R009 /);
  });

  it("別の見出しを押すと昇順から始まる(直前の列の向きを引き継がない)", () => {
    renderFindings(resultsSeed);
    fireEvent.click(screen.getByRole("button", { name: "行で並べ替え" }));
    fireEvent.click(screen.getByRole("button", { name: "行で並べ替え" })); // 行を降順にする
    fireEvent.click(screen.getByRole("button", { name: "重大度で並べ替え" })); // 別列へ切替
    expect(screen.getByRole("columnheader", { name: /重大度/ })).toHaveAttribute("aria-sort", "ascending");
  });

  it("設定の重大度しきい値より低い指摘を一覧から除く", () => {
    renderFindings({ ...resultsSeed, severityThreshold: "medium" });
    // 高 5 件と中 3 件だけが残る。低(R002)と警告(R009)は消える。
    expect(dataRows()).toHaveLength(8);
    expect(screen.queryByRole("row", { name: /^R002 / })).toBeNull();
    expect(screen.queryByRole("row", { name: /^R009 / })).toBeNull();
    expect(screen.getByText(/重大度しきい値「中」以上を表示/)).toBeInTheDocument();
  });

  it("しきい値で隠れている重大度のチップは操作できない", () => {
    renderFindings({ ...resultsSeed, severityThreshold: "medium" });
    const chips = within(screen.getByRole("group", { name: "重大度フィルタ" }));
    expect(chips.getByRole("button", { name: /高/ })).toBeEnabled();
    expect(chips.getByRole("button", { name: /低/ })).toBeDisabled();
    expect(chips.getByRole("button", { name: /警告/ })).toBeDisabled();
  });

  it("しきい値が許した範囲の中でチップが絞る", () => {
    renderFindings({ ...resultsSeed, severityThreshold: "medium" });
    const chips = within(screen.getByRole("group", { name: "重大度フィルタ" }));
    fireEvent.click(chips.getByRole("button", { name: /高/ }));
    expect(dataRows()).toHaveLength(3);
    expect(screen.getByRole("row", { name: /^R008 / })).toBeInTheDocument();
  });

  it("レポート出力ボタンで report 画面へ遷移する", () => {
    renderFindings(resultsSeed);
    fireEvent.click(screen.getByRole("button", { name: "レポート出力へ" }));
    expect(screen.getByTestId("probe")).toHaveTextContent("report|");
  });
});
