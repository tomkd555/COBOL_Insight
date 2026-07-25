import { describe, it, expect } from "vitest";
import { RULE_CATALOG, ruleOf } from "./ruleCatalog";

describe("ruleCatalog(検出ルールカタログ)", () => {
  it("バグ検出 31 件と SQL 助言 6 件の計 37 件を持つ", () => {
    expect(Object.keys(RULE_CATALOG)).toHaveLength(37);
    expect(RULE_CATALOG.R031).toBeDefined();
    expect(RULE_CATALOG.S006).toBeDefined();
  });

  it("重大度ラベルを 4 段の Severity へ写す", () => {
    expect(ruleOf("R004").severity).toBe("high");
    expect(ruleOf("R008").severity).toBe("medium");
    expect(ruleOf("R002").severity).toBe("low");
    expect(ruleOf("R009").severity).toBe("warning");
    expect(ruleOf("S001").severity).toBe("medium");
    expect(ruleOf("S005").severity).toBe("low");
  });

  it("修正案 diff を持つのは R004/R017/R018 の 3 件だけ(engine の FixProducer 実装と一致)", () => {
    for (const id of ["R004", "R017", "R018"]) {
      expect(ruleOf(id).hasFix).toBe(true);
    }
    // R021 は engine の CicsResponseUncheckedRule が FixProducer を実装しないため生成できない。
    for (const id of ["R001", "R008", "R021", "R031", "S001"]) {
      expect(ruleOf(id).hasFix).toBe(false);
    }
    const withFix = Object.values(RULE_CATALOG)
      .filter((rule) => rule.hasFix)
      .map((rule) => rule.id);
    expect(withFix).toEqual(["R004", "R017", "R018"]);
  });

  it("名称は docs/06 ルールカタログの名称と一致する", () => {
    expect(ruleOf("R014").name).toBe("セクション末尾のEXIT文欠如によるフォールスルー");
    expect(ruleOf("R029").name).toBe("呼び出し先プログラムの戻りコード(RETURN-CODE)未検査");
  });

  it("SQL 助言のカテゴリは docs/06 の分類(可読性・保守性/性能/性能・信頼性)を使う", () => {
    expect(ruleOf("S001").category).toBe("可読性・保守性");
    expect(ruleOf("S002").category).toBe("性能");
    expect(ruleOf("S003").category).toBe("性能");
    expect(ruleOf("S004").category).toBe("性能・信頼性");
    expect(ruleOf("S005").category).toBe("性能");
    expect(ruleOf("S006").category).toBe("性能");
  });

  it("SARIF の level と画面の重大度は独立(warning level でも中の場合がある)", () => {
    // R008/R011/R022 は SARIF level=warning だが画面重大度は中。
    expect(ruleOf("R011").severity).toBe("medium");
    expect(ruleOf("R022").severity).toBe("medium");
  });

  it("engine の解析エラー ID は構文解析失敗と復号失敗を区別して名付ける", () => {
    expect(ruleOf("parse-failure")).toEqual({
      id: "parse-failure",
      name: "構文解析失敗",
      category: "解析エラー",
      severity: "high",
      hasFix: false,
    });
    expect(ruleOf("decode-failure")).toEqual({
      id: "decode-failure",
      name: "文字コードの復号失敗",
      category: "解析エラー",
      severity: "high",
      hasFix: false,
    });
  });

  it("カタログにも解析エラーにも無い ID を構文解析失敗と名付けない", () => {
    const unknown = ruleOf("R999");
    expect(unknown.severity).toBe("high");
    expect(unknown.name).toBe("未登録のルール");
    expect(unknown.hasFix).toBe(false);
  });
});
