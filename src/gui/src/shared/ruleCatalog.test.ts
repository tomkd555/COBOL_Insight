import { describe, it, expect } from "vitest";
import { parseRuleCatalog } from "./ruleCatalog";


const ENTRY = {
  id: "R003",
  name: "MOVEによる桁落ち・切り捨て",
  category: "データ移動",
  severity: "HIGH",
  phase: "DATA_FLOW",
  hasFix: false,
  source: "builtin",
  enabled: true,
  summary: "受信項目の桁数が送信項目より小さい MOVE を検出する。",
  rationale: "上位桁が失われる。",
  detection: "PICTURE を解決して桁を比べる。",
  remedy: "受信項目の桁を広げる。",
  badExample: "MOVE A TO B.",
  goodExample: "MOVE A TO C.",
};

describe("parseRuleCatalog", () => {
  it("engine の rules --json をルール一覧へ写す", () => {
    const catalog = parseRuleCatalog({ ruleCount: 1, rules: [ENTRY], userRuleErrors: [] });
    expect(catalog.rules).toHaveLength(1);
    expect(catalog.rules[0]).toEqual(ENTRY);
    expect(catalog.userRuleErrors).toEqual([]);
    expect(catalog.ruleConfigWarnings).toEqual([]);
  });

  it("無効にしたルールを enabled: false のまま受け取る", () => {
    const catalog = parseRuleCatalog({ rules: [{ ...ENTRY, enabled: false }] });
    expect(catalog.rules[0].enabled).toBe(false);
  });

  it("設定ファイルの注意を併せて返す", () => {
    const catalog = parseRuleCatalog({
      rules: [],
      ruleConfigWarnings: ["rules-config.json: 知らないルール ID である R999"],
    });
    expect(catalog.ruleConfigWarnings).toEqual([
      "rules-config.json: 知らないルール ID である R999",
    ]);
  });

  it("利用者定義ルールの定義の誤りを併せて返す", () => {
    const catalog = parseRuleCatalog({
      ruleCount: 0,
      rules: [],
      userRuleErrors: ["user-rules.json の rules[0]: id が無い、または空である"],
    });
    expect(catalog.userRuleErrors).toHaveLength(1);
  });

  it("欠けた欄は空文字・偽で補い、一覧の描画を止めない", () => {
    const catalog = parseRuleCatalog({ rules: [{ id: "U001", name: "自作" }] });
    expect(catalog.rules[0]).toEqual({
      id: "U001",
      name: "自作",
      category: "",
      severity: "MEDIUM",
      phase: "SYNTAX",
      hasFix: false,
      source: "builtin",
      enabled: true,
      summary: "",
      rationale: "",
      detection: "",
      remedy: "",
      badExample: "",
      goodExample: "",
    });
  });

  it("source が user の要素はそのまま保つ", () => {
    const catalog = parseRuleCatalog({ rules: [{ id: "U001", name: "自作", source: "user" }] });
    expect(catalog.rules[0].source).toBe("user");
  });

  it("id を持たない要素は落とす", () => {
    const catalog = parseRuleCatalog({ rules: [{ name: "ID なし" }, ENTRY] });
    expect(catalog.rules.map((rule) => rule.id)).toEqual(["R003"]);
  });

  it("出力を得られなかった場合は例外にする", () => {
    expect(() => parseRuleCatalog(null)).toThrow();
  });
});
