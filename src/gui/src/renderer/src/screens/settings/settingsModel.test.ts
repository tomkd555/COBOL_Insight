import { describe, it, expect } from "vitest";
import { ruleCount, ruleIds } from "../../data/ruleCatalog";
import {
  ENCODING_OPTIONS,
  addPath,
  buildRuleGroups,
  disabledRuleIds,
  enabledRuleCount,
  filterCountLabel,
  groupedRuleIds,
  isReadOnly,
  movePath,
  removePath,
  ruleCountLabel,
  setRulesEnabled,
  severityThresholdNote,
  toggleRule,
  visibleSeverities,
} from "./settingsModel";
import {
  ENCODING_OPTIONS as ASSET_ENCODING_OPTIONS,
  MANUAL_ENCODING_OPTIONS,
} from "../explorer/assetView";

describe("ルールカタログの読み出し", () => {
  it("バグ検出 31 件と SQL 助言 6 件の合計 37 件を扱う", () => {
    expect(ruleCount()).toBe(37);
    expect(ruleIds().filter((id) => id.startsWith("R"))).toHaveLength(31);
    expect(ruleIds().filter((id) => id.startsWith("S"))).toHaveLength(6);
  });

  it("ID はカタログの定義順(R001→R031→S001→S006)である", () => {
    expect(ruleIds()[0]).toBe("R001");
    expect(ruleIds()[30]).toBe("R031");
    expect(ruleIds()[36]).toBe("S006");
  });

  it("既定の文字コードの選択肢は資産エクスプローラーの手動指定と同じ語彙である", () => {
    expect(ENCODING_OPTIONS).toEqual([...MANUAL_ENCODING_OPTIONS]);
    for (const option of ENCODING_OPTIONS) {
      expect(ASSET_ENCODING_OPTIONS).toContain(option);
    }
  });

  it("選択肢はいずれも scan へ渡す charset を持ち、効果の無い選択肢を並べない", () => {
    expect(ENCODING_OPTIONS).toHaveLength(4);
    expect(ENCODING_OPTIONS).toContain("手動: Shift_JIS");
    expect(ENCODING_OPTIONS).toContain("手動: EBCDIC CP939");
  });
});

describe("buildRuleGroups", () => {
  it("検索語が無ければ全件をカテゴリ別にまとめる", () => {
    const groups = buildRuleGroups("", {});
    expect(groupedRuleIds(groups)).toHaveLength(ruleCount());
    expect(groups[0].category).toBe("データフロー");
    expect(groups[0].rows[0].id).toBe("R001");
  });

  it("同じカテゴリを連続してまとめ、重複した見出しを作らない", () => {
    const groups = buildRuleGroups("", {});
    const categories = groups.map((group) => group.category);
    expect(new Set(categories).size).toBe(categories.length);
  });

  it("ルール ID で絞り込む", () => {
    const groups = buildRuleGroups("R017", {});
    expect(groupedRuleIds(groups)).toEqual(["R017"]);
    expect(groups[0].category).toBe("例外処理");
  });

  it("ルール名称で絞り込む", () => {
    expect(groupedRuleIds(buildRuleGroups("SQLCODE", {}))).toEqual(["R018"]);
  });

  it("カテゴリで絞り込む", () => {
    expect(groupedRuleIds(buildRuleGroups("セキュリティ", {}))).toEqual(["R026", "R027"]);
  });

  it("大文字小文字と前後の空白を無視する", () => {
    expect(groupedRuleIds(buildRuleGroups("  r017  ", {}))).toEqual(["R017"]);
  });

  it("一致しない検索語では空になる", () => {
    expect(buildRuleGroups("該当しない語", {})).toEqual([]);
  });

  it("無効化した状態を各行へ反映する", () => {
    const groups = buildRuleGroups("R004", { R004: true });
    expect(groups[0].rows[0].disabled).toBe(true);
  });

  it("修正案を持つルールを行から見分けられる", () => {
    const groups = buildRuleGroups("R004", {});
    expect(groups[0].rows[0].hasFix).toBe(true);
    expect(buildRuleGroups("R021", {})[0].rows[0].hasFix).toBe(true);
  });
});

describe("有効・無効の集合", () => {
  it("無効化した件数を総数から引く", () => {
    expect(enabledRuleCount({})).toBe(37);
    expect(enabledRuleCount({ R001: true, R002: true })).toBe(35);
  });

  it("false は無効として数えない", () => {
    expect(enabledRuleCount({ R001: false })).toBe(37);
  });

  it("カタログにない ID は数に含めない", () => {
    expect(enabledRuleCount({ R999: true })).toBe(37);
    expect(disabledRuleIds({ R999: true })).toEqual([]);
  });

  it("無効化した ID をカタログの定義順で返す", () => {
    expect(disabledRuleIds({ S001: true, R004: true })).toEqual(["R004", "S001"]);
  });

  it("1件の反転は元の集合を変えない", () => {
    const before = { R004: true } as const;
    expect(toggleRule(before, "R017")).toEqual({ R004: true, R017: true });
    expect(toggleRule(before, "R004")).toEqual({});
    expect(before).toEqual({ R004: true });
  });

  it("一括で有効にすると指定した ID を集合から外す", () => {
    expect(setRulesEnabled({ R001: true, R002: true }, ["R001"], true)).toEqual({ R002: true });
  });

  it("一括で無効にすると指定した ID を集合へ加える", () => {
    expect(setRulesEnabled({}, ["R001", "R002"], false)).toEqual({ R001: true, R002: true });
  });

  it("絞り込み結果だけを一括操作の対象にできる", () => {
    const groups = buildRuleGroups("セキュリティ", {});
    expect(setRulesEnabled({}, groupedRuleIds(groups), false)).toEqual({
      R026: true,
      R027: true,
    });
  });

  it("件数の見出しを組む", () => {
    expect(ruleCountLabel({})).toBe("有効 37 / 37");
    expect(ruleCountLabel({ R001: true })).toBe("有効 36 / 37");
  });

  it("絞り込み件数は検索語があるときだけ示す", () => {
    expect(filterCountLabel("", buildRuleGroups("", {}))).toBeNull();
    expect(filterCountLabel("R01", buildRuleGroups("R01", {}))).toBe("検索に一致: 10 件");
  });
});

describe("重大度のしきい値", () => {
  it("しきい値以上の重大度を並べる", () => {
    expect(visibleSeverities("high")).toEqual(["high"]);
    expect(visibleSeverities("low")).toEqual(["high", "medium", "low"]);
    expect(visibleSeverities("warning")).toEqual(["high", "medium", "low", "warning"]);
  });

  it("説明文へ対象の重大度を並べる", () => {
    expect(severityThresholdNote("medium")).toBe(
      "現在の設定: 「中」以上を表示（高・中 が対象）",
    );
  });

  it("最下位のしきい値ではすべて表示である旨を添える", () => {
    expect(severityThresholdNote("warning")).toContain("すべて表示");
  });
});

describe("コピー句探索パスの編集", () => {
  const paths = ["C:\\資産\\copybook", "C:\\資産\\共通\\copylib", "D:\\copylib2"];

  it("1つ上へ動かす", () => {
    expect(movePath(paths, 1, -1)).toEqual([
      "C:\\資産\\共通\\copylib",
      "C:\\資産\\copybook",
      "D:\\copylib2",
    ]);
  });

  it("1つ下へ動かす", () => {
    expect(movePath(paths, 0, 1)).toEqual([
      "C:\\資産\\共通\\copylib",
      "C:\\資産\\copybook",
      "D:\\copylib2",
    ]);
  });

  it("端では動かさない", () => {
    expect(movePath(paths, 0, -1)).toEqual(paths);
    expect(movePath(paths, 2, 1)).toEqual(paths);
  });

  it("元の並びを変えない", () => {
    const original = [...paths];
    movePath(paths, 1, -1);
    expect(paths).toEqual(original);
  });

  it("1件を除く", () => {
    expect(removePath(paths, 1)).toEqual(["C:\\資産\\copybook", "D:\\copylib2"]);
  });

  it("範囲外の除去は並びを変えない", () => {
    expect(removePath(paths, 9)).toEqual(paths);
  });

  it("末尾へ加える", () => {
    const result = addPath(["C:\\a"], "  D:\\b  ");
    expect(result).toEqual({ paths: ["C:\\a", "D:\\b"], added: true, reason: null });
  });

  it("空文字は加えず理由を返す", () => {
    const result = addPath(["C:\\a"], "   ");
    expect(result.added).toBe(false);
    expect(result.paths).toEqual(["C:\\a"]);
    expect(result.reason).toContain("入力してください");
  });

  it("重複は加えず理由を返す", () => {
    const result = addPath(["C:\\a"], "C:\\a");
    expect(result.added).toBe(false);
    expect(result.reason).toContain("すでに登録");
  });
});

describe("isReadOnly", () => {
  it("解析の実行中だけ読み取り専用にする", () => {
    expect(isReadOnly("running")).toBe(true);
    expect(isReadOnly("empty")).toBe(false);
    expect(isReadOnly("results")).toBe(false);
    expect(isReadOnly("error")).toBe(false);
  });
});
