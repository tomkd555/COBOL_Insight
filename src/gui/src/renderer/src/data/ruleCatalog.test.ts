import { describe, it, expect, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseRuleCatalog } from "../../../shared/ruleCatalog";
import {
  allRules,
  isRuleCatalogLoaded,
  ruleCount,
  ruleIds,
  ruleOf,
  setRuleCatalog,
} from "./ruleCatalog";

/** vitest.setup.ts が注入するものと同じ、engine の rules --json の実出力。 */
const FIXTURE = parseRuleCatalog(
  JSON.parse(
    readFileSync(join(__dirname, "__fixtures__", "rules.json"), "utf-8"),
  ) as Record<string, unknown>,
).rules;

/** カタログはモジュール共通の状態のため、入れ替えたテストの後は必ず元へ戻す。 */
afterEach(() => {
  setRuleCatalog(FIXTURE);
});

describe("engine から取り込んだカタログ", () => {
  it("組み込み 37 件を取り込む", () => {
    expect(isRuleCatalogLoaded()).toBe(true);
    expect(ruleCount()).toBe(37);
    expect(ruleIds()).toContain("R031");
    expect(ruleIds()).toContain("S006");
  });

  it("engine の重大度を画面の 4 段へ写す", () => {
    expect(ruleOf("R004").severity).toBe("high");
    expect(ruleOf("R008").severity).toBe("medium");
    expect(ruleOf("R002").severity).toBe("low");
    expect(ruleOf("R009").severity).toBe("warning");
    expect(ruleOf("S001").severity).toBe("medium");
    expect(ruleOf("S005").severity).toBe("low");
  });

  it("修正案を持つのは engine の FixProducer があるルールだけである", () => {
    for (const id of ["R004", "R017", "R018", "R021"]) {
      expect(ruleOf(id).hasFix).toBe(true);
    }
    const withFix = allRules().filter((rule) => rule.hasFix).map((rule) => rule.id);
    expect(withFix).toEqual(["R004", "R017", "R018", "R021"]);
  });

  it("名称とカテゴリを engine の説明から引く", () => {
    expect(ruleOf("R014").name).toBe("セクション末尾のEXIT文欠如によるフォールスルー");
    expect(ruleOf("R029").name).toBe("呼び出し先プログラムの戻りコード(RETURN-CODE)未検査");
    expect(ruleOf("S001").category).toBe("可読性・保守性");
    expect(ruleOf("S004").category).toBe("性能・信頼性");
  });

  it("説明の各項目を持ち、画面がルールの内容を示せる", () => {
    const rule = ruleOf("R003");
    expect(rule.summary).toContain("MOVE");
    expect(rule.rationale).not.toBe("");
    expect(rule.detection).not.toBe("");
    expect(rule.remedy).not.toBe("");
    expect(rule.badExample).toContain("PIC");
    expect(rule.goodExample).toContain("PIC");
  });

  it("組み込みルールはいずれも出所を builtin として持つ", () => {
    expect(allRules().every((rule) => rule.source === "builtin")).toBe(true);
  });
});

describe("カタログの入れ替え", () => {
  it("利用者定義ルールを含む一覧で置き換えられる", () => {
    setRuleCatalog([
      ...FIXTURE,
      {
        id: "U001",
        name: "自社で禁じた命令",
        category: "社内規約",
        severity: "HIGH",
        phase: "SYNTAX",
        hasFix: false,
        source: "user",
        summary: "正規表現に一致する行を検出する。",
        rationale: "運用規約で禁じている。",
        detection: "対象は COBOL の各行である。",
        remedy: "別の書き方へ改める。",
        badExample: "",
        goodExample: "",
      },
    ]);
    expect(ruleCount()).toBe(38);
    expect(ruleOf("U001").source).toBe("user");
    expect(ruleOf("U001").severity).toBe("high");
  });

  it("engine の重大度が未知の値でも中として扱い、一覧を止めない", () => {
    setRuleCatalog([
      {
        id: "U002",
        name: "未知の重大度",
        category: "利用者定義",
        severity: "CRITICAL",
        phase: "SYNTAX",
        hasFix: false,
        source: "user",
        summary: "",
        rationale: "",
        detection: "",
        remedy: "",
        badExample: "",
        goodExample: "",
      },
    ]);
    expect(ruleOf("U002").severity).toBe("medium");
  });
});

describe("カタログにない ID", () => {
  it("構文解析の失敗を解析エラーとして名付ける", () => {
    const rule = ruleOf("parse-failure");
    expect(rule.name).toBe("構文解析失敗");
    expect(rule.category).toBe("解析エラー");
    expect(rule.severity).toBe("high");
  });

  it("復号の失敗を別の名で示す", () => {
    expect(ruleOf("decode-failure").name).toBe("文字コードの復号失敗");
  });

  it("engine が出さない ID は未登録として示す", () => {
    const unknown = ruleOf("R999");
    expect(unknown.name).toBe("未登録のルール");
    expect(unknown.severity).toBe("high");
    expect(unknown.hasFix).toBe(false);
  });
});
