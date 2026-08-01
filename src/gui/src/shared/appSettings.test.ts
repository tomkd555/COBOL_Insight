import { describe, it, expect } from "vitest";
import { emptyAppSettings, normalizeAppSettings } from "./appSettings";

const SAVED = {
  version: 1,
  settings: {
    disabledRules: ["R004", "S001"],
    severityThreshold: "medium",
    defaultEncoding: "手動: Shift_JIS",
    copybookPaths: ["C:\\資産\\copybook"],
    paneSizes: { explorerDetail: 520, viewerTranslation: 480 },
  },
};

describe("normalizeAppSettings", () => {
  it("保存した設定をそのまま読み戻す", () => {
    expect(normalizeAppSettings(SAVED)).toEqual(SAVED.settings);
  });

  it("version の無い形(設定そのもの)も読める", () => {
    expect(normalizeAppSettings(SAVED.settings)).toEqual(SAVED.settings);
  });

  it("欠けた欄を空として扱う", () => {
    expect(normalizeAppSettings({ version: 1, settings: {} })).toEqual(emptyAppSettings());
  });

  it("型の合わない欄を空として扱う", () => {
    const settings = normalizeAppSettings({
      settings: { disabledRules: "R004", severityThreshold: 3, copybookPaths: {} },
    });
    expect(settings).toEqual(emptyAppSettings());
  });

  it("分割ペインの寸法が欠けていれば空として扱う(旧い保存ファイルを読める)", () => {
    const settings = normalizeAppSettings({
      version: 1,
      settings: { disabledRules: ["R004"] },
    });
    expect(settings.paneSizes).toEqual({});
  });

  it("分割ペインの寸法のうち、数として扱えない欄を落とす", () => {
    const settings = normalizeAppSettings({
      settings: {
        paneSizes: {
          explorerDetail: 520,
          graphDetail: "300",
          sqlDetail: Number.NaN,
          diffList: null,
        },
      },
    });
    expect(settings.paneSizes).toEqual({ explorerDetail: 520 });
  });

  it("分割ペインの寸法がオブジェクトでなければ空として扱う", () => {
    expect(normalizeAppSettings({ settings: { paneSizes: [1, 2] } }).paneSizes).toEqual({});
    expect(normalizeAppSettings({ settings: { paneSizes: "520" } }).paneSizes).toEqual({});
  });

  it("配列の中の文字列でない要素を落とす", () => {
    const settings = normalizeAppSettings({ settings: { disabledRules: ["R004", 7, null] } });
    expect(settings.disabledRules).toEqual(["R004"]);
  });

  it("オブジェクトでない値は空として扱う", () => {
    expect(normalizeAppSettings(null)).toEqual(emptyAppSettings());
    expect(normalizeAppSettings("x")).toEqual(emptyAppSettings());
    expect(normalizeAppSettings([1, 2])).toEqual(emptyAppSettings());
  });
});
