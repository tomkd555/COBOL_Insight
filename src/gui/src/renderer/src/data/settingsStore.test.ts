import { describe, it, expect, vi, beforeEach } from "vitest";
import type { CobolInsightApi } from "../../../shared/engine-api";
import { initialState, type AppState } from "../state/appState";
import type { Action } from "../state/appReducer";
import { restoreSettings, saveSettings, toAppSettings, toRuleConfig } from "./settingsStore";

function stateOf(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

describe("toAppSettings", () => {
  it("設定画面の3項目と分割ペインの寸法を保存する形へ写し替える", () => {
    const settings = toAppSettings(
      stateOf({
        severityThreshold: "medium",
        defaultEncoding: "手動: EBCDIC CP939",
        project: { inputDir: "C:\\資産", dbPath: null, copybookPaths: ["C:\\copy"] },
      }),
    );
    expect(settings).toEqual({
      severityThreshold: "medium",
      defaultEncoding: "手動: EBCDIC CP939",
      copybookPaths: ["C:\\copy"],
      paneSizes: initialState.paneWidths,
    });
  });

  it("ドラッグした寸法をそのまま保存する", () => {
    const settings = toAppSettings(
      stateOf({ paneWidths: { ...initialState.paneWidths, explorerDetail: 640 } }),
    );
    expect(settings.paneSizes["explorerDetail"]).toBe(640);
  });

  it("資産フォルダそのものは保存しない", () => {
    const settings = toAppSettings(
      stateOf({ project: { inputDir: "C:\\資産", dbPath: "C:\\db", copybookPaths: [] } }),
    );
    expect(Object.keys(settings)).toEqual([
      "severityThreshold",
      "defaultEncoding",
      "copybookPaths",
      "paneSizes",
    ]);
  });

  /** 無効にしたルールは engine も読む設定ファイル側が持つ。settings.json へ二重に書かない。 */
  it("無効にしたルールを含めない", () => {
    expect(toAppSettings(stateOf({ rulesDisabled: { R004: true } }))).not.toHaveProperty(
      "disabledRules",
    );
  });
});

describe("toRuleConfig", () => {
  it("無効にした ID を昇順で並べ、保存の差分を安定させる", () => {
    const config = toRuleConfig(stateOf({ rulesDisabled: { S001: true, R004: true, R001: true } }));
    expect(config).toEqual({ version: 1, disabledRules: ["R001", "R004", "S001"] });
  });

  it("false の項目は無効として数えない", () => {
    expect(toRuleConfig(stateOf({ rulesDisabled: { R004: false } })).disabledRules).toEqual([]);
  });

  /**
   * 設定画面が engine へ渡す disabledRuleIds はカタログの並びで返すため、カタログの取り込みが
   * 終わる前に使うと空になる。保存はカタログに依存しないことを確かめる。
   */
  it("カタログに無い ID も落とさず保存する", () => {
    const config = toRuleConfig(stateOf({ rulesDisabled: { U001: true, R999: true } }));
    expect(config.disabledRules).toEqual(["R999", "U001"]);
  });
});

const OUTPUT_PATHS = {
  db: "C:\\data\\cobol-insight.db",
  lintSarif: "C:\\data\\cobol-insight.sarif",
  sqlAdviseSarif: "C:\\data\\cobol-insight-sql.sarif",
  copyExpansion: "C:\\data\\cobol-insight-copy-expansion.json",
  userRules: "C:\\data\\user-rules.json",
  ruleConfig: "C:\\data\\rules-config.json",
};

let readSettings: ReturnType<typeof vi.fn>;
let readRuleConfig: ReturnType<typeof vi.fn>;
let writeSettings: ReturnType<typeof vi.fn>;
let writeRuleConfig: ReturnType<typeof vi.fn>;

beforeEach(() => {
  readSettings = vi.fn().mockResolvedValue({
    severityThreshold: "medium",
    defaultEncoding: "",
    copybookPaths: [],
    paneSizes: {},
  });
  readRuleConfig = vi.fn().mockResolvedValue({ version: 1, disabledRules: [] });
  writeSettings = vi.fn().mockResolvedValue(undefined);
  writeRuleConfig = vi.fn().mockResolvedValue(undefined);
  window.cobolInsight = {
    getOutputPaths: vi.fn().mockResolvedValue(OUTPUT_PATHS),
    readSettings,
    readRuleConfig,
    writeSettings,
    writeRuleConfig,
  } as unknown as CobolInsightApi;
});

describe("restoreSettings", () => {
  it("設定ファイルにあるルールを無効の状態として戻す", async () => {
    readRuleConfig.mockResolvedValue({ version: 1, disabledRules: ["R004", "S001"] });
    const actions: Action[] = [];

    await restoreSettings((action) => actions.push(action));

    expect(readRuleConfig).toHaveBeenCalledWith(OUTPUT_PATHS.ruleConfig);
    expect(actions).toContainEqual({
      type: "SET_RULES_ENABLED",
      ids: ["R004", "S001"],
      enabled: false,
    });
  });

  it("無効にしたルールが1件も無ければ、有効・無効へは触れない", async () => {
    const actions: Action[] = [];

    await restoreSettings((action) => actions.push(action));

    expect(actions.map((action) => action.type)).toEqual(["RESTORE_SETTINGS"]);
  });
});

describe("saveSettings", () => {
  it("画面の設定とルールの設定を両方書く", async () => {
    await saveSettings(stateOf({ rulesDisabled: { R009: true } }));

    expect(writeSettings).toHaveBeenCalledTimes(1);
    expect(writeRuleConfig).toHaveBeenCalledWith(OUTPUT_PATHS.ruleConfig, {
      version: 1,
      disabledRules: ["R009"],
    });
  });
});
