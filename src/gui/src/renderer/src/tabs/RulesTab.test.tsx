import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import type {
  CobolInsightApi,
  EngineOutputPaths,
  RuleCatalogEntry,
} from "../../../shared/engine-api";
import { RulesTab } from "./RulesTab";
import { buildRuleCatalog } from "../data/ruleCatalog";
import { FIXTURE_RULE_ENTRIES } from "../data/__fixtures__/catalog";
import {
  ProjectProvider,
  initialProjectState,
  type ProjectState,
} from "../state/projectStore";

const OUTPUT_PATHS: EngineOutputPaths = {
  db: "C:\\out\\cobol-insight.db",
  lintSarif: "C:\\out\\lint.sarif",
  sqlAdviseSarif: "C:\\out\\sql.sarif",
  copyExpansion: "C:\\out\\copy.json",
  userRules: "C:\\out\\user-rules.json",
  ruleConfig: "C:\\out\\rules-config.json",
};

/** engine が「R004 は無効」と返した一覧。 */
const DISABLED_R004: readonly RuleCatalogEntry[] = FIXTURE_RULE_ENTRIES.map((entry) =>
  entry.id === "R004" ? { ...entry, enabled: false } : entry,
);

const listRules = vi.fn();
const writeRuleConfig = vi.fn();

function stubApi(): void {
  listRules.mockResolvedValue({
    rules: [...FIXTURE_RULE_ENTRIES],
    userRuleErrors: [],
    ruleConfigWarnings: [],
  });
  writeRuleConfig.mockResolvedValue(undefined);
  const stub: Partial<CobolInsightApi> = {
    getOutputPaths: vi.fn().mockResolvedValue(OUTPUT_PATHS),
    listRules,
    writeRuleConfig,
    writeUserRules: vi.fn().mockResolvedValue(undefined),
    readUserRules: vi.fn().mockResolvedValue({ version: 1, rules: [] }),
  };
  window.cobolInsight = stub as CobolInsightApi;
}

beforeEach(() => {
  vi.clearAllMocks();
  stubApi();
});

function renderTab(overrides: Partial<ProjectState> = {}): void {
  const project: ProjectState = {
    ...initialProjectState,
    mode: "results",
    catalog: buildRuleCatalog(DISABLED_R004),
    ...overrides,
  };
  render(
    <ProjectProvider initial={project}>
      <RulesTab />
    </ProjectProvider>,
  );
}

/** そのルールの有効・無効のトグル。 */
function toggleOf(id: string, name: string): HTMLElement {
  return screen.getByRole("switch", { name: `${id} ${name}` });
}

describe("ルールの一覧", () => {
  it("engine が返した有効・無効をそのまま示す", () => {
    renderTab();
    expect(toggleOf("R004", "ON SIZE ERROR句の欠如")).toHaveAttribute("aria-checked", "false");
    expect(toggleOf("R001", "未初期化変数の参照")).toHaveAttribute("aria-checked", "true");
  });

  it("有効な件数を見出しへ出す", () => {
    renderTab();
    expect(screen.getByText("有効 36 / 37")).toBeInTheDocument();
  });

  it("設定ファイルの注意を一覧の上へ出す", () => {
    renderTab({ ruleConfigWarnings: ["知らないルール ID である R999"] });
    const alerts = screen.getAllByRole("alert");
    expect(alerts.some((alert) => alert.textContent?.includes("R999"))).toBe(true);
  });

  it("利用者定義ルールの定義の誤りを一覧の上へ出す", () => {
    renderTab({ userRuleErrors: ["rules[0]: id が無い、または空である"] });
    const alerts = screen.getAllByRole("alert");
    expect(alerts.some((alert) => alert.textContent?.includes("id が無い"))).toBe(true);
  });
});

describe("有効・無効の切替", () => {
  it("設定ファイルへ書いたうえで、一覧を engine から取り直す", async () => {
    renderTab();
    fireEvent.click(toggleOf("R004", "ON SIZE ERROR句の欠如"));

    await waitFor(() => expect(writeRuleConfig).toHaveBeenCalledTimes(1));
    expect(writeRuleConfig).toHaveBeenCalledWith(OUTPUT_PATHS.ruleConfig, {
      version: 1,
      disabledRules: [],
    });
    // 書いただけでは表は変わらない。取り直した一覧が新しい状態の供給源である。
    await waitFor(() => expect(listRules).toHaveBeenCalledTimes(1));
    expect(listRules).toHaveBeenCalledWith({
      userRulesFile: OUTPUT_PATHS.userRules,
      ruleConfigFile: OUTPUT_PATHS.ruleConfig,
    });
    await waitFor(() =>
      expect(toggleOf("R004", "ON SIZE ERROR句の欠如")).toHaveAttribute("aria-checked", "true"),
    );
  });

  it("有効なルールを押すと設定ファイルへ加える", async () => {
    renderTab();
    fireEvent.click(toggleOf("R001", "未初期化変数の参照"));
    await waitFor(() =>
      expect(writeRuleConfig).toHaveBeenCalledWith(OUTPUT_PATHS.ruleConfig, {
        version: 1,
        disabledRules: ["R001", "R004"],
      }),
    );
  });

  it("絞り込んだ範囲だけを一括で無効にできる", async () => {
    renderTab();
    fireEvent.change(screen.getByRole("textbox", { name: "検索" }), {
      target: { value: "セキュリティ" },
    });
    fireEvent.click(screen.getByRole("button", { name: "表示中をすべて無効" }));
    await waitFor(() =>
      expect(writeRuleConfig).toHaveBeenCalledWith(OUTPUT_PATHS.ruleConfig, {
        version: 1,
        disabledRules: ["R004", "R026", "R027"],
      }),
    );
  });

  it("書き込みに失敗したら、そのまま伝えて一覧を取り直さない", async () => {
    writeRuleConfig.mockRejectedValue(new Error("書き込みを拒まれました"));
    renderTab();
    fireEvent.click(toggleOf("R004", "ON SIZE ERROR句の欠如"));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("書き込みを拒まれました"),
    );
    expect(listRules).not.toHaveBeenCalled();
    expect(toggleOf("R004", "ON SIZE ERROR句の欠如")).toHaveAttribute("aria-checked", "false");
  });
});

describe("絞り込み", () => {
  it("出所で絞り込むと、組み込みルールだけを外せる", () => {
    renderTab();
    fireEvent.change(screen.getByRole("combobox", { name: "出所" }), {
      target: { value: "user" },
    });
    expect(screen.getByText(/絞り込みに一致: 0 件/)).toBeInTheDocument();
  });

  it("カテゴリで絞り込む", () => {
    renderTab();
    fireEvent.change(screen.getByRole("combobox", { name: "カテゴリ" }), {
      target: { value: "セキュリティ" },
    });
    const group = screen.getByRole("region", { name: "セキュリティ" });
    expect(within(group).getAllByRole("switch")).toHaveLength(2);
  });
});
