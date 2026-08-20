import { describe, it, expect } from "vitest";
import { buildRuleCatalog, ruleCount, ruleOf } from "../../data/ruleCatalog";
import { FIXTURE_CATALOG, FIXTURE_RULE_ENTRIES } from "../../data/__fixtures__/catalog";
import { visibleSeverities } from "../../components/severity";
import {
  ALL_RULES,
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
  ruleCategories,
  ruleConfigToggling,
  ruleConfigWith,
  ruleCountLabel,
} from "./settingsModel";
import { MANUAL_ENCODING_OPTIONS } from "../../data/encodings";

/** 検索語だけを与えた絞り込み。 */
function search(text: string): typeof ALL_RULES {
  return { ...ALL_RULES, search: text };
}

/** engine が「R004 と S001 は無効」と返した一覧。 */
const WITH_DISABLED = buildRuleCatalog(
  FIXTURE_RULE_ENTRIES.map((entry) =>
    entry.id === "R004" || entry.id === "S001" ? { ...entry, enabled: false } : entry,
  ),
);

/** 利用者定義ルールを1件足した一覧。 */
const WITH_USER_RULE = buildRuleCatalog([
  ...FIXTURE_RULE_ENTRIES,
  {
    id: "U001",
    name: "自社で禁じた命令",
    category: "社内規約",
    severity: "HIGH",
    phase: "SYNTAX",
    hasFix: false,
    source: "user",
    enabled: true,
    summary: "",
    rationale: "",
    detection: "",
    remedy: "",
    badExample: "",
    goodExample: "",
  },
]);

describe("ルール一覧の読み出し", () => {
  it("バグ検出 31 件と SQL 指摘 6 件の合計 37 件を扱う", () => {
    expect(ruleCount(FIXTURE_CATALOG)).toBe(37);
    expect(FIXTURE_CATALOG.order.filter((id) => id.startsWith("R"))).toHaveLength(31);
    expect(FIXTURE_CATALOG.order.filter((id) => id.startsWith("S"))).toHaveLength(6);
  });

  it("ID は engine が返した順(R001→R031→S001→S006)である", () => {
    expect(FIXTURE_CATALOG.order[0]).toBe("R001");
    expect(FIXTURE_CATALOG.order[30]).toBe("R031");
    expect(FIXTURE_CATALOG.order[36]).toBe("S006");
  });

  it("既定の文字コードの選択肢は手動指定と同じ語彙である", () => {
    expect(ENCODING_OPTIONS).toEqual([...MANUAL_ENCODING_OPTIONS]);
  });

  it("選択肢はいずれも scan へ渡す charset を持ち、効果の無い選択肢を並べない", () => {
    expect(ENCODING_OPTIONS).toHaveLength(4);
    expect(ENCODING_OPTIONS).toContain("手動: Shift_JIS");
    expect(ENCODING_OPTIONS).toContain("手動: EBCDIC CP939");
  });
});

describe("buildRuleGroups", () => {
  it("絞り込まなければ全件をカテゴリ別にまとめる", () => {
    const groups = buildRuleGroups(FIXTURE_CATALOG, ALL_RULES);
    expect(groupedRuleIds(groups)).toHaveLength(ruleCount(FIXTURE_CATALOG));
    expect(groups[0].category).toBe("データフロー");
    expect(groups[0].rows[0].id).toBe("R001");
  });

  it("同じカテゴリを連続してまとめ、重複した見出しを作らない", () => {
    const categories = buildRuleGroups(FIXTURE_CATALOG, ALL_RULES).map((group) => group.category);
    expect(new Set(categories).size).toBe(categories.length);
  });

  it("ルール ID で絞り込む", () => {
    const groups = buildRuleGroups(FIXTURE_CATALOG, search("R017"));
    expect(groupedRuleIds(groups)).toEqual(["R017"]);
    expect(groups[0].category).toBe("例外処理");
  });

  it("ルール名称で絞り込む", () => {
    expect(groupedRuleIds(buildRuleGroups(FIXTURE_CATALOG, search("SQLCODE")))).toEqual(["R018"]);
  });

  it("カテゴリの語で絞り込む", () => {
    expect(groupedRuleIds(buildRuleGroups(FIXTURE_CATALOG, search("セキュリティ")))).toEqual([
      "R026",
      "R027",
    ]);
  });

  it("大文字小文字と前後の空白を無視する", () => {
    expect(groupedRuleIds(buildRuleGroups(FIXTURE_CATALOG, search("  r017  ")))).toEqual(["R017"]);
  });

  it("一致しない検索語では空になる", () => {
    expect(buildRuleGroups(FIXTURE_CATALOG, search("該当しない語"))).toEqual([]);
  });

  it("出所で絞り込む", () => {
    const user = buildRuleGroups(WITH_USER_RULE, { ...ALL_RULES, source: "user" });
    expect(groupedRuleIds(user)).toEqual(["U001"]);
    expect(groupedRuleIds(buildRuleGroups(WITH_USER_RULE, { ...ALL_RULES, source: "builtin" }))).toHaveLength(37);
  });

  it("カテゴリを選んで絞り込む", () => {
    const groups = buildRuleGroups(FIXTURE_CATALOG, { ...ALL_RULES, category: "セキュリティ" });
    expect(groupedRuleIds(groups)).toEqual(["R026", "R027"]);
  });

  it("engine が返した有効・無効を各行が持つ", () => {
    const groups = buildRuleGroups(WITH_DISABLED, search("R004"));
    expect(groups[0].rows[0].enabled).toBe(false);
    expect(buildRuleGroups(WITH_DISABLED, search("R005"))[0].rows[0].enabled).toBe(true);
  });

  it("修正案を持つルールを行から見分けられる", () => {
    expect(buildRuleGroups(FIXTURE_CATALOG, search("R004"))[0].rows[0].hasFix).toBe(true);
    expect(buildRuleGroups(FIXTURE_CATALOG, search("R021"))[0].rows[0].hasFix).toBe(true);
  });

  it("カテゴリの選択肢を engine の並びで返す", () => {
    const categories = ruleCategories(FIXTURE_CATALOG);
    expect(categories[0]).toBe("データフロー");
    expect(new Set(categories).size).toBe(categories.length);
  });
});

describe("engine へ書き渡すルールの設定", () => {
  it("無効なルールを engine が返した順で数える", () => {
    expect(enabledRuleCount(FIXTURE_CATALOG)).toBe(37);
    expect(enabledRuleCount(WITH_DISABLED)).toBe(35);
    expect(disabledRuleIds(WITH_DISABLED)).toEqual(["R004", "S001"]);
  });

  it("1件の反転は engine の答えを起点にする", () => {
    expect(ruleConfigToggling(WITH_DISABLED, "R004")).toEqual({
      version: 1,
      disabledRules: ["S001"],
    });
    expect(ruleConfigToggling(WITH_DISABLED, "R017")).toEqual({
      version: 1,
      disabledRules: ["R004", "R017", "S001"],
    });
  });

  it("一括で無効にすると、いま無効なものを保ったまま加える", () => {
    expect(ruleConfigWith(WITH_DISABLED, ["R026", "R027"], false).disabledRules).toEqual([
      "R004",
      "R026",
      "R027",
      "S001",
    ]);
  });

  it("一括で有効にすると設定から外す", () => {
    expect(ruleConfigWith(WITH_DISABLED, ["R004", "S001"], true).disabledRules).toEqual([]);
  });

  it("絞り込み結果だけを一括操作の対象にできる", () => {
    const groups = buildRuleGroups(FIXTURE_CATALOG, search("セキュリティ"));
    expect(ruleConfigWith(FIXTURE_CATALOG, groupedRuleIds(groups), false).disabledRules).toEqual([
      "R026",
      "R027",
    ]);
  });

  it("件数の見出しを組む", () => {
    expect(ruleCountLabel(FIXTURE_CATALOG)).toBe("有効 37 / 37");
    expect(ruleCountLabel(WITH_DISABLED)).toBe("有効 35 / 37");
  });

  it("絞り込み件数は絞り込んでいるときだけ示す", () => {
    expect(filterCountLabel(ALL_RULES, buildRuleGroups(FIXTURE_CATALOG, ALL_RULES))).toBeNull();
    expect(filterCountLabel(search("R01"), buildRuleGroups(FIXTURE_CATALOG, search("R01")))).toBe(
      "絞り込みに一致: 10 件",
    );
  });

  it("一覧に無い ID を引いても有効として扱い、一覧を止めない", () => {
    expect(ruleOf(FIXTURE_CATALOG, "R999").enabled).toBe(true);
  });
});

describe("重大度のしきい値", () => {
  it("しきい値以上の重大度を並べる", () => {
    expect(visibleSeverities("high")).toEqual(["high"]);
    expect(visibleSeverities("low")).toEqual(["high", "medium", "low"]);
    expect(visibleSeverities("warning")).toEqual(["high", "medium", "low", "warning"]);
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
