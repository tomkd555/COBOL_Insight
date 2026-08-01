import { describe, it, expect } from "vitest";
import { initialState, type AppState } from "../state/appState";
import { toAppSettings } from "./settingsStore";

function stateOf(overrides: Partial<AppState>): AppState {
  return { ...initialState, ...overrides };
}

describe("toAppSettings", () => {
  it("設定画面の4項目を保存する形へ写す", () => {
    const settings = toAppSettings(
      stateOf({
        rulesDisabled: { R004: true },
        severityThreshold: "medium",
        defaultEncoding: "手動: EBCDIC CP939",
        project: { inputDir: "C:\\資産", dbPath: null, copybookPaths: ["C:\\copy"] },
      }),
    );
    expect(settings).toEqual({
      disabledRules: ["R004"],
      severityThreshold: "medium",
      defaultEncoding: "手動: EBCDIC CP939",
      copybookPaths: ["C:\\copy"],
    });
  });

  it("無効化した ID を昇順で並べ、保存の差分を安定させる", () => {
    const settings = toAppSettings(
      stateOf({ rulesDisabled: { S001: true, R004: true, R001: true } }),
    );
    expect(settings.disabledRules).toEqual(["R001", "R004", "S001"]);
  });

  it("false の項目は無効として数えない", () => {
    expect(toAppSettings(stateOf({ rulesDisabled: { R004: false } })).disabledRules).toEqual([]);
  });

  /**
   * 設定画面が engine へ渡す disabledRuleIds はカタログの並びで返すため、カタログの取り込みが
   * 終わる前に使うと空になる。保存はカタログに依存しないことを確かめる。
   */
  it("カタログに無い ID も落とさず保存する", () => {
    const settings = toAppSettings(stateOf({ rulesDisabled: { U001: true, R999: true } }));
    expect(settings.disabledRules).toEqual(["R999", "U001"]);
  });

  it("資産フォルダそのものは保存しない", () => {
    const settings = toAppSettings(
      stateOf({ project: { inputDir: "C:\\資産", dbPath: "C:\\db", copybookPaths: [] } }),
    );
    expect(Object.keys(settings)).toEqual([
      "disabledRules",
      "severityThreshold",
      "defaultEncoding",
      "copybookPaths",
    ]);
  });
});
