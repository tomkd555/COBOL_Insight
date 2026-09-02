import { describe, expect, it } from "vitest";
import type { RuleCatalogEntry } from "../../../shared/ipc";
import { emptyRulesFile, type RulesFile } from "../../../shared/rulesFile";
import { filterRules, groupByCategory, overrideOf, setEnabled, setSeverity } from "./rulesView";

function entry(overrides: Partial<RuleCatalogEntry>): RuleCatalogEntry {
  return {
    id: "R001",
    name: "未初期化のデータ項目の参照",
    category: "データフロー",
    severity: "HIGH",
    phase: "DATA_FLOW",
    hasFix: false,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: ["LINT", "REPORT"],
    targets: ["COBOL"],
    needs: ["CFG", "DATAFLOW", "SEMANTIC", "SOURCE_TEXT"],
    summary: "値を設定する前に参照し得るデータ項目を検出します。",
    rationale: "",
    detection: "",
    remedy: "",
    badExample: "",
    goodExample: "",
    ...overrides,
  };
}

// The three entries carry the engine's own name, category and summary for R001, R004 and S001.
const ENTRIES = [
  entry({}),
  entry({
    id: "R004",
    name: "ON SIZE ERROR 句の欠如",
    category: "例外処理",
    summary:
      "結果が受け取り側項目のけた数を超え得るのに ON SIZE ERROR 句を持たない算術文を検出します。",
  }),
  entry({
    id: "S001",
    name: "列を明示しない SELECT *",
    category: "可読性・保守性",
    summary: "SELECT 句に * を使う問い合わせを検出します。",
  }),
];

describe("the search", () => {
  it("matches the id, the name and the summary", () => {
    expect(filterRules(ENTRIES, "r004").map((rule) => rule.id)).toEqual(["R004"]);
    expect(filterRules(ENTRIES, "SELECT").map((rule) => rule.id)).toEqual(["S001"]);
    expect(filterRules(ENTRIES, "受け取り側項目").map((rule) => rule.id)).toEqual(["R004"]);
  });

  it("keeps everything when nothing was typed", () => {
    expect(filterRules(ENTRIES, "   ")).toHaveLength(3);
  });
});

describe("the grouping", () => {
  it("keeps the engine's order and puts each rule under its category", () => {
    expect(groupByCategory(ENTRIES)).toEqual([
      { category: "データフロー", rules: [ENTRIES[0]] },
      { category: "例外処理", rules: [ENTRIES[1]] },
      { category: "可読性・保守性", rules: [ENTRIES[2]] },
    ]);
  });
});

describe("switching rules off and on", () => {
  it("writes the state of every id it was given", () => {
    const file = setEnabled(emptyRulesFile(), ["R001", "R004"], false);
    expect(file.rules).toEqual({ R001: { enabled: false }, R004: { enabled: false } });
  });

  it("leaves a severity override in place", () => {
    const before: RulesFile = { ...emptyRulesFile(), rules: { R001: { severity: "LOW" } } };
    expect(setEnabled(before, ["R001"], false).rules["R001"]).toEqual({
      severity: "LOW",
      enabled: false,
    });
  });

  it("leaves the custom rules alone", () => {
    const before: RulesFile = {
      ...emptyRulesFile(),
      custom: [{ id: "U001", name: "n", message: "m", match: { kind: "line", regex: "A" } }],
    };
    expect(setEnabled(before, ["R001"], true).custom).toBe(before.custom);
  });
});

describe("the severity override", () => {
  it("is written and then removed, leaving no empty entry behind", () => {
    const set = setSeverity(emptyRulesFile(), "R001", "HIGH");
    expect(set.rules).toEqual({ R001: { severity: "HIGH" } });
    expect(setSeverity(set, "R001", null).rules).toEqual({});
  });

  it("keeps the entry when the rule is also switched off", () => {
    const off = setEnabled(emptyRulesFile(), ["R001"], false);
    expect(setSeverity(off, "R001", null).rules).toEqual({ R001: { enabled: false } });
  });

  it("reads back through overrideOf", () => {
    const file = setSeverity(emptyRulesFile(), "R001", "LOW");
    expect(overrideOf(file, "R001").severity).toBe("LOW");
    expect(overrideOf(file, "R004")).toEqual({});
  });
});
