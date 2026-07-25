import { describe, it, expect } from "vitest";
import { deriveRunBanner, deriveStatus } from "./status";
import { initialState, type AppState } from "./appState";
import { SAMPLE_INVENTORY } from "../screens/explorer/fixtures";
import { SAMPLE_FINDINGS, SAMPLE_SQL_FINDINGS } from "../screens/findings/fixtures";

function withState(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

/** 解析が全段成功した状態(資産 6 件・指摘 10 件・SQL助言 6 件)。 */
const analyzed = withState({
  mode: "results",
  inventory: { status: "ready", items: SAMPLE_INVENTORY },
  findings: { status: "ready", items: SAMPLE_FINDINGS },
  sqlAdvice: { status: "ready", items: SAMPLE_SQL_FINDINGS },
});

describe("deriveStatus(ステータスバー)", () => {
  it("empty は資産 0 とルール有効数を出す", () => {
    const status = deriveStatus(initialState);
    expect(status.left).toBe("準備完了 ― 資産をインポートしてください");
    expect(status.counts).toBe("資産 0 ・ ルール 37 有効 ・ v1.0.0");
  });

  it("running は指摘と SQL 助言を「―」で伏せる", () => {
    const status = deriveStatus({ ...analyzed, mode: "running" });
    expect(status.counts).toContain("指摘 ―");
    expect(status.counts).toContain("SQL助言 ―");
  });

  it("results は lint と sql-advise の実件数を出す", () => {
    const status = deriveStatus(analyzed);
    expect(status.left).toBe("解析完了 ― 正常終了");
    expect(status.counts).toBe("資産 6 ・ 指摘 10 ・ SQL助言 6 ・ ルール 37 有効 ・ v1.0.0");
  });

  it("指摘の取得が失敗した段は 0 件ではなく「―」で示す", () => {
    const status = deriveStatus({
      ...analyzed,
      mode: "error",
      findings: { status: "error", message: "lint の起動に失敗しました" },
    });
    expect(status.counts).toContain("指摘 ―");
    expect(status.counts).not.toContain("指摘 0");
  });

  it("未取得の段も 0 件と示さない", () => {
    const status = deriveStatus({
      ...analyzed,
      sqlAdvice: { status: "none" },
    });
    expect(status.counts).toContain("SQL助言 ―");
  });

  it("ルールの有効数は設定で無効化した件数を差し引く", () => {
    const status = deriveStatus(withState({ rulesDisabled: { R009: true, S001: true } }));
    expect(status.counts).toContain("ルール 35 有効");
  });

  it("解析後のルール有効数も無効化集合から導く", () => {
    const status = deriveStatus({ ...analyzed, rulesDisabled: { R009: true } });
    expect(status.counts).toContain("ルール 36 有効");
  });
});

describe("deriveRunBanner(解析実行の失敗バナー)", () => {
  it("正常終了ではバナーを出さない", () => {
    expect(deriveRunBanner(analyzed)).toBeNull();
  });

  it("scan 自体が失敗したときは資産一覧を出せない旨と理由を示す", () => {
    const banner = deriveRunBanner({
      ...analyzed,
      mode: "error",
      inventory: { status: "error", message: "入力フォルダが見つかりません" },
    });
    expect(banner).toContain("解析に失敗しました");
    expect(banner).toContain("入力フォルダが見つかりません");
  });

  it("lint が失敗したときは指摘の取得失敗として理由を示す", () => {
    const banner = deriveRunBanner({
      ...analyzed,
      mode: "error",
      findings: { status: "error", message: "lint が SARIF を出力しませんでした" },
    });
    expect(banner).toContain("指摘の取得に失敗しました");
    expect(banner).toContain("lint が SARIF を出力しませんでした");
  });

  it("sql-advise が失敗したときは SQL 助言の取得失敗として示す", () => {
    const banner = deriveRunBanner({
      ...analyzed,
      mode: "error",
      sqlAdvice: { status: "error", message: "sql-advise が異常終了しました" },
    });
    expect(banner).toContain("SQL助言の取得に失敗しました");
  });

  it("全段が成功していて error モードなら構文解析の部分的失敗として示す", () => {
    const banner = deriveRunBanner({ ...analyzed, mode: "error" });
    expect(banner).toContain("一部の資産で構文解析に失敗しました");
  });
});
