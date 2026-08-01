import { describe, it, expect } from "vitest";
import { deriveRunBanner, deriveScanNotice, deriveStatus } from "./status";
import { initialState, type AppState } from "./appState";
import { SAMPLE_INVENTORY } from "../screens/explorer/fixtures";
import { SAMPLE_FINDINGS, SAMPLE_SQL_FINDINGS } from "../screens/findings/fixtures";

function withState(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

/** 解析が全段成功した状態(資産 6 件・指摘 10 件・SQL指摘 6 件)。 */
const analyzed = withState({
  mode: "results",
  inventory: { status: "ready", items: SAMPLE_INVENTORY },
  findings: { status: "ready", items: SAMPLE_FINDINGS },
  sqlAdvice: { status: "ready", items: SAMPLE_SQL_FINDINGS },
});

describe("deriveStatus(ステータスバー)", () => {
  it("empty は資産 0 とルール有効数を出す", () => {
    const status = deriveStatus(initialState);
    expect(status.left).toBe("準備完了 ― 資産の取込待ち");
    expect(status.counts).toBe("資産 0 ・ ルール 37 有効 ・ v1.0.0");
  });

  it("running は指摘と SQL 指摘を「―」で伏せる", () => {
    const status = deriveStatus({ ...analyzed, mode: "running" });
    expect(status.counts).toContain("指摘 ―");
    expect(status.counts).toContain("SQL指摘 ―");
  });

  it("results は lint と sql-lint の実件数を出す", () => {
    const status = deriveStatus(analyzed);
    expect(status.left).toBe("解析完了 ― 正常終了");
    expect(status.counts).toBe("資産 6 ・ 指摘 10 ・ SQL指摘 6 ・ ルール 37 有効 ・ v1.0.0");
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
    expect(status.counts).toContain("SQL指摘 ―");
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
    expect(banner).toContain("解析に失敗した");
    expect(banner).toContain("入力フォルダが見つかりません");
  });

  it("lint が失敗したときは指摘の取得失敗として理由を示す", () => {
    const banner = deriveRunBanner({
      ...analyzed,
      mode: "error",
      findings: { status: "error", message: "lint が SARIF を出力しませんでした" },
    });
    expect(banner).toContain("指摘の取得に失敗した");
    expect(banner).toContain("lint が SARIF を出力しませんでした");
  });

  it("sql-lint が失敗したときは SQL 指摘の取得失敗として示す", () => {
    const banner = deriveRunBanner({
      ...analyzed,
      mode: "error",
      sqlAdvice: { status: "error", message: "sql-lint が異常終了しました" },
    });
    expect(banner).toContain("SQL指摘の取得に失敗した");
  });

  it("全段が成功していて error モードなら構文解析の部分的失敗として示す", () => {
    const banner = deriveRunBanner({ ...analyzed, mode: "error" });
    expect(banner).toContain("一部の資産で構文解析に失敗した");
  });
});

describe("deriveScanNotice(走査の警告)", () => {
  /** 資産 0 件で解析が正常終了した状態。 */
  const empty = withState({
    mode: "results",
    inventory: { status: "ready", items: [] },
    scanDiscovery: { undecided: [], mismatches: [], truncated: false, unreadable: [] },
  });

  it("資産を取り込めていれば警告を出さない", () => {
    expect(deriveScanNotice(analyzed)).toEqual([]);
  });

  it("解析前は警告を出さない", () => {
    expect(deriveScanNotice(initialState)).toEqual([]);
  });

  it("実行中は警告を出さない", () => {
    expect(deriveScanNotice({ ...empty, mode: "running" })).toEqual([]);
  });

  it("取得に失敗した場合は失敗バナーに任せ、警告を出さない", () => {
    const failed = withState({
      mode: "error",
      inventory: { status: "error", message: "読み取りに失敗しました" },
    });
    expect(deriveScanNotice(failed)).toEqual([]);
  });

  it("0 件のときは資産の種別と、拡張子が無くても内容で判定する旨を案内する", () => {
    const [section] = deriveScanNotice(empty);
    expect(section.text).toContain("対象の資産が 1 件も見つからなかった");
    expect(section.text).toContain("COBOL・コピー句・JCL・BMS");
    expect(section.text).toContain("内容から種別を判定する");
    expect(section.details).toEqual([]);
  });

  it("undecided は件数と判定条件を示し、該当ファイルを全件添える", () => {
    const [section] = deriveScanNotice({
      ...analyzed,
      scanDiscovery: {
        undecided: ["misc/README.txt", "misc/NOTES.txt"],
        mismatches: [],
        truncated: false,
        unreadable: [],
      },
    });
    expect(section.text).toContain("2 件");
    expect(section.text).toContain("種別を判定できなかった");
    expect(section.text).toContain("IDENTIFICATION DIVISION");
    expect(section.details).toEqual(["misc/README.txt", "misc/NOTES.txt"]);
  });

  it("unreadable は件数を示し、該当ファイルを全件添える", () => {
    const [section] = deriveScanNotice({
      ...analyzed,
      scanDiscovery: {
        undecided: [],
        mismatches: [],
        truncated: false,
        unreadable: ["cobol/LOCKED.cbl"],
      },
    });
    expect(section.text).toContain("1 件");
    expect(section.text).toContain("読み取れなかった");
    expect(section.details).toEqual(["cobol/LOCKED.cbl"]);
  });

  it("mismatches は件数を示し、どちらとして扱ったかを全件添える", () => {
    const [section] = deriveScanNotice({
      ...analyzed,
      scanDiscovery: {
        undecided: [],
        mismatches: [{ path: "copybook/X.cpy", byExtension: "COPYBOOK", byContent: "COBOL" }],
        truncated: false,
        unreadable: [],
      },
    });
    expect(section.text).toContain("1 件");
    expect(section.text).toContain("拡張子と内容が食い違った");
    expect(section.details).toEqual(["copybook/X.cpy → COBOL 本体"]);
  });

  it("上限で打ち切ったときはその旨を示す", () => {
    const [section] = deriveScanNotice({
      ...analyzed,
      scanDiscovery: { undecided: [], mismatches: [], truncated: true, unreadable: [] },
    });
    expect(section.text).toContain("上限");
  });

  it("報告の優先順位は undecided・unreadable ＞ mismatches ＞ truncated の順に並ぶ", () => {
    const sections = deriveScanNotice({
      ...analyzed,
      scanDiscovery: {
        undecided: ["a.txt"],
        mismatches: [{ path: "b.cpy", byExtension: "COPYBOOK", byContent: "COBOL" }],
        truncated: true,
        unreadable: ["c.cbl"],
      },
    });
    expect(sections).toHaveLength(4);
    expect(sections[0].text).toContain("種別を判定できなかった");
    expect(sections[1].text).toContain("読み取れなかった");
    expect(sections[2].text).toContain("拡張子と内容が食い違った");
    expect(sections[3].text).toContain("走査の上限");
  });
});
