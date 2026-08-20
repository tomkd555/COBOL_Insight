import { describe, it, expect } from "vitest";
import { allRules, buildRuleCatalog, ruleCount, ruleOf } from "./ruleCatalog";
import { FIXTURE_CATALOG, FIXTURE_RULE_ENTRIES } from "./__fixtures__/catalog";

describe("engine から取り込んだ一覧", () => {
  it("組み込み 37 件を索引へ組む", () => {
    expect(FIXTURE_CATALOG.loaded).toBe(true);
    expect(ruleCount(FIXTURE_CATALOG)).toBe(37);
    expect(FIXTURE_CATALOG.order).toContain("R031");
    expect(FIXTURE_CATALOG.order).toContain("S006");
  });

  it("engine の重大度を画面の 4 段へ置き換える", () => {
    expect(ruleOf(FIXTURE_CATALOG, "R004").severity).toBe("high");
    expect(ruleOf(FIXTURE_CATALOG, "R008").severity).toBe("medium");
    expect(ruleOf(FIXTURE_CATALOG, "R002").severity).toBe("low");
    expect(ruleOf(FIXTURE_CATALOG, "R009").severity).toBe("warning");
    expect(ruleOf(FIXTURE_CATALOG, "S001").severity).toBe("medium");
    expect(ruleOf(FIXTURE_CATALOG, "S005").severity).toBe("low");
  });

  it("修正案を持つのは engine の FixProducer があるルールだけである", () => {
    for (const id of ["R004", "R017", "R018", "R021"]) {
      expect(ruleOf(FIXTURE_CATALOG, id).hasFix).toBe(true);
    }
    const withFix = allRules(FIXTURE_CATALOG)
      .filter((rule) => rule.hasFix)
      .map((rule) => rule.id);
    expect(withFix).toEqual(["R004", "R017", "R018", "R021"]);
  });

  it("名称とカテゴリを engine の説明から引く", () => {
    expect(ruleOf(FIXTURE_CATALOG, "R014").name).toBe(
      "セクション末尾のEXIT文欠如によるフォールスルー",
    );
    expect(ruleOf(FIXTURE_CATALOG, "R029").name).toBe(
      "呼び出し先プログラムの戻りコード(RETURN-CODE)未検査",
    );
    expect(ruleOf(FIXTURE_CATALOG, "S001").category).toBe("可読性・保守性");
    expect(ruleOf(FIXTURE_CATALOG, "S004").category).toBe("性能");
  });

  it("説明の各項目を持ち、画面がルールの内容を示せる", () => {
    const rule = ruleOf(FIXTURE_CATALOG, "R003");
    expect(rule.summary).toContain("MOVE");
    expect(rule.rationale).not.toBe("");
    expect(rule.detection).not.toBe("");
    expect(rule.remedy).not.toBe("");
    expect(rule.badExample).toContain("PIC");
    expect(rule.goodExample).toContain("PIC");
  });

  it("組み込みルールはいずれも出所を builtin として持つ", () => {
    expect(allRules(FIXTURE_CATALOG).every((rule) => rule.source === "builtin")).toBe(true);
  });
});

describe("索引の組み直し", () => {
  it("利用者定義ルールを含む一覧から組める", () => {
    const catalog = buildRuleCatalog([
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
        summary: "正規表現に一致する行を検出する。",
        rationale: "運用規約で禁じている。",
        detection: "対象は COBOL の各行である。",
        remedy: "別の書き方へ改める。",
        badExample: "",
        goodExample: "",
      },
    ]);
    expect(ruleCount(catalog)).toBe(38);
    expect(ruleOf(catalog, "U001").source).toBe("user");
    expect(ruleOf(catalog, "U001").severity).toBe("high");
  });

  it("engine の重大度が未知の値でも中として扱い、一覧を止めない", () => {
    const catalog = buildRuleCatalog([
      {
        id: "U002",
        name: "未知の重大度",
        category: "利用者定義",
        severity: "CRITICAL",
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
    expect(ruleOf(catalog, "U002").severity).toBe("medium");
  });
});

describe("索引にない ID", () => {
  it("構文解析の失敗を解析エラーとして名付ける", () => {
    const rule = ruleOf(FIXTURE_CATALOG, "parse-failure");
    expect(rule.name).toBe("構文解析失敗");
    expect(rule.category).toBe("解析エラー");
    expect(rule.severity).toBe("high");
  });

  it("復号の失敗を別の名で示す", () => {
    expect(ruleOf(FIXTURE_CATALOG, "decode-failure").name).toBe("文字コードの復号失敗");
  });

  it("engine が出さない ID は未登録として示す", () => {
    const unknown = ruleOf(FIXTURE_CATALOG, "R999");
    expect(unknown.name).toBe("未登録のルール");
    expect(unknown.severity).toBe("high");
    expect(unknown.hasFix).toBe(false);
  });
});
