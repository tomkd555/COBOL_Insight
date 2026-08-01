import type { ReactElement } from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { App, AppShell } from "./App";
import { AppStateProvider, useAppDispatch } from "./state/AppStateContext";
import { initialState, type AppState, type ScreenMode } from "./state/appState";

/** 指定した初期状態でシェルを描画する。 */
function renderShell(seed: AppState): void {
  render(
    <AppStateProvider initialState={seed}>
      <AppShell />
    </AppStateProvider>,
  );
}

/** JUMP をディスパッチする試験用トリガ。 */
function JumpTrigger(): ReactElement {
  const dispatch = useAppDispatch();
  return (
    <button type="button" onClick={() => dispatch({ type: "JUMP", file: "SYK001.cbl", line: 85, from: "指摘一覧" })}>
      ジャンプ実行
    </button>
  );
}

describe("App シェル", () => {
  it("タイトルバーに製品名を表示する", () => {
    render(<App />);
    expect(screen.getByRole("banner")).toHaveTextContent("COBOL Insight");
  });

  it("アプリ名が文書で唯一の h1 であり、画面の題目は h2 から始まる", () => {
    render(<App />);
    const level1 = screen.getAllByRole("heading", { level: 1 });
    expect(level1).toHaveLength(1);
    expect(level1[0]).toHaveTextContent("COBOL Insight");
    // 画面の題目は Screen の隠し見出しが h2 として担い、資産一覧の空状態の見出しは h3 になる
    // (右の詳細ペインの題目も同じ h3 のため、空状態の見出しを名前で絞って確かめる)。
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent("資産一覧");
    expect(
      screen.getByRole("heading", { level: 3, name: "資産がまだ取り込まれていません" }),
    ).toBeInTheDocument();
  });

  it("9タブを提示し、既定は資産一覧を選択する", () => {
    render(<App />);
    expect(screen.getAllByRole("tab")).toHaveLength(9);
    expect(screen.getByRole("tab", { name: "資産一覧" })).toHaveAttribute("aria-selected", "true");
  });

  it("タブを切り替えると選択状態とオーバーレイ内容が変わる", () => {
    render(<App />);
    fireEvent.click(screen.getByRole("tab", { name: "レポート出力" }));
    expect(screen.getByRole("tab", { name: "レポート出力" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "資産一覧" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tabpanel")).toHaveTextContent("レポート出力");
  });

  it("9タブすべてを順に選択でき、対応するオーバーレイへ切り替わる", () => {
    render(<App />);
    const tabs = ["資産一覧", "端末取込", "呼出関係図", "指摘一覧", "ソースビューア", "SQL指摘", "修正案の差分", "レポート出力", "設定"];
    tabs.forEach((name) => {
      fireEvent.click(screen.getByRole("tab", { name }));
      expect(screen.getByRole("tab", { name })).toHaveAttribute("aria-selected", "true");
      expect(screen.getByRole("tabpanel")).toBeInTheDocument();
    });
  });

  it("初期状態(非実行中)では進捗バーを出さず、ステータスに件数を表示する", () => {
    render(<App />);
    expect(screen.queryByRole("progressbar")).toBeNull();
    expect(screen.getByRole("contentinfo")).toHaveTextContent("資産 0 ・ ルール 37 有効 ・ v1.0.0");
  });

  describe("4状態(mode)ごとの描画", () => {
    it("empty は空状態プレースホルダを描画し進捗バーを出さない", () => {
      renderShell({ ...initialState, mode: "empty" });
      expect(screen.queryByRole("progressbar")).toBeNull();
      expect(screen.getByRole("region", { name: "資産がまだ取り込まれていません" })).toBeInTheDocument();
    });

    it("running は進捗バーと3段の進行提示を描画する", () => {
      renderShell({ ...initialState, mode: "running" });
      expect(screen.getByRole("progressbar")).toBeInTheDocument();
      expect(screen.getByText(/第1段 資産の走査と構文解析/)).toBeInTheDocument();
      expect(screen.getByRole("banner")).toHaveTextContent("解析実行中");
    });

    it("running では資産フォルダが未設定なら実行段だけをタイトルバーへ表示する", () => {
      renderShell({ ...initialState, mode: "running", runStage: 1 });
      expect(screen.getByRole("banner")).toHaveTextContent("解析実行中 ― 資産の走査と構文解析");
    });

    it("running では資産フォルダ名と現在の実行段をタイトルバーへ表示する", () => {
      renderShell({
        ...initialState,
        mode: "running",
        runStage: 2,
        project: { inputDir: "C:\\assets\\SYK006", dbPath: null, copybookPaths: [] },
      });
      expect(screen.getByRole("banner")).toHaveTextContent("解析実行中 ― SYK006（指摘の検出）");
    });

    it("results は本体プレースホルダを描画し、エラーバナーは出さない", () => {
      renderShell({ ...initialState, mode: "results", screen: "report" });
      expect(screen.queryByRole("progressbar")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.getByRole("tabpanel")).toHaveTextContent("レポート出力");
    });

    it("error は本体に加えて警告バナーを描画する", () => {
      renderShell({ ...initialState, mode: "error", screen: "report" });
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });

    it.each(["empty", "running", "results", "error"] as const)("mode=%s で例外なく描画できる", (mode: ScreenMode) => {
      renderShell({ ...initialState, mode });
      expect(screen.getByRole("tablist")).toBeInTheDocument();
    });
  });

  describe("画面横断ジャンプ", () => {
    it("JUMP でソースビューアへ遷移し、そのタブが選択状態になる", () => {
      render(
        <AppStateProvider>
          <AppShell />
          <JumpTrigger />
        </AppStateProvider>,
      );
      expect(screen.getByRole("tab", { name: "資産一覧" })).toHaveAttribute("aria-selected", "true");
      fireEvent.click(screen.getByText("ジャンプ実行"));
      expect(screen.getByRole("tab", { name: "ソースビューア" })).toHaveAttribute("aria-selected", "true");
    });
  });

  describe("トースト通知", () => {
    it("toastMsg があるとステータス通知として表示する", () => {
      renderShell({ ...initialState, toastMsg: "呼出関係図を SVG として出力しました" });
      expect(screen.getByText("呼出関係図を SVG として出力しました")).toBeInTheDocument();
    });
  });
});
