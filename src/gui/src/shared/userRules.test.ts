import { describe, it, expect } from "vitest";
import {
  emptyUserRules,
  newUserRuleDraft,
  nextUserRuleId,
  normalizeUserRule,
  normalizeUserRulesFile,
  validateUserRule,
} from "./userRules";

const MINIMAL = { id: "U001", name: "試験", pattern: "GOBACK", message: "検出した" };

describe("normalizeUserRule", () => {
  it("省略した欄を既定で補う", () => {
    expect(normalizeUserRule(MINIMAL)).toEqual({
      id: "U001",
      name: "試験",
      category: "利用者定義",
      severity: "MEDIUM",
      targets: ["COBOL"],
      pattern: "GOBACK",
      excludePattern: "",
      ignoreCase: false,
      wholeLine: false,
      message: "検出した",
      rationale: "",
      remedy: "",
    });
  });

  it("扱えない種別を落とし、重複を除く", () => {
    expect(normalizeUserRule({ ...MINIMAL, targets: ["COBOL", "JCL", "cobol", "BMS"] }).targets)
      .toEqual(["COBOL", "BMS"]);
  });

  it("種別の指定が全て扱えない場合は COBOL へ戻す", () => {
    expect(normalizeUserRule({ ...MINIMAL, targets: ["JCL"] }).targets).toEqual(["COBOL"]);
  });

  it("重大度を大文字へ揃える", () => {
    expect(normalizeUserRule({ ...MINIMAL, severity: "high" }).severity).toBe("HIGH");
  });

  it("必須の欄が欠けていれば例外にする", () => {
    expect(() => normalizeUserRule({ id: "U001", name: "試験" })).toThrow(/pattern/);
    expect(() => normalizeUserRule({ ...MINIMAL, id: "  " })).toThrow(/id/);
  });
});

describe("normalizeUserRulesFile", () => {
  it("読めない定義だけを外し、残りを保つ", () => {
    const file = normalizeUserRulesFile({ version: 1, rules: [MINIMAL, { id: "U002" }] });
    expect(file.rules.map((rule) => rule.id)).toEqual(["U001"]);
    expect(file.version).toBe(1);
  });

  it("rules が無い定義は空として扱う", () => {
    expect(normalizeUserRulesFile({})).toEqual(emptyUserRules());
  });
});

describe("nextUserRuleId", () => {
  it("空なら U001 を返す", () => {
    expect(nextUserRuleId([])).toBe("U001");
  });

  it("使われていない最小の番号を返す", () => {
    const rules = [
      normalizeUserRule({ ...MINIMAL, id: "U001" }),
      normalizeUserRule({ ...MINIMAL, id: "U003" }),
    ];
    expect(nextUserRuleId(rules)).toBe("U002");
  });
});

describe("newUserRuleDraft", () => {
  it("既存と重ならない ID を採り、対象は COBOL から始める", () => {
    const draft = newUserRuleDraft([normalizeUserRule(MINIMAL)]);
    expect(draft.id).toBe("U002");
    expect(draft.targets).toEqual(["COBOL"]);
    expect(draft.severity).toBe("MEDIUM");
    expect(draft.name).toBe("");
  });
});

describe("validateUserRule", () => {
  const existing = [normalizeUserRule(MINIMAL)];

  function draftOf(patch: Record<string, unknown>) {
    return normalizeUserRule({ ...MINIMAL, id: "U002", ...patch });
  }

  it("欄が揃っていれば誤りを返さない", () => {
    expect(validateUserRule(draftOf({}), existing, null)).toEqual([]);
  });

  it("ID の形が違えば誤りにする", () => {
    expect(validateUserRule(draftOf({ id: "R001" }), existing, null)[0]).toContain("U で始まり");
  });

  it("既存と同じ ID を拒む", () => {
    const errors = validateUserRule(draftOf({ id: "U001" }), existing, null);
    expect(errors.some((error) => error.includes("既に使われている"))).toBe(true);
  });

  it("自分自身の ID は重複として扱わない", () => {
    expect(validateUserRule(draftOf({ id: "U001" }), existing, 0)).toEqual([]);
  });

  it("解釈できない正規表現を誤りにする", () => {
    const errors = validateUserRule(draftOf({ pattern: "A(" }), existing, null);
    expect(errors.some((error) => error.includes("正規表現を解釈できない"))).toBe(true);
  });

  it("除外する正規表現も同じく検査する", () => {
    const errors = validateUserRule(draftOf({ excludePattern: "B[" }), existing, null);
    expect(errors.some((error) => error.includes("除外する正規表現"))).toBe(true);
  });

  it("名称とメッセージの空を誤りにする", () => {
    const draft = { ...draftOf({}), name: "", message: "" };
    const errors = validateUserRule(draft, existing, null);
    expect(errors).toHaveLength(2);
  });

  it("重大度の語彙外を誤りにする", () => {
    const draft = { ...draftOf({}), severity: "CRITICAL" };
    expect(validateUserRule(draft, existing, null).some((e) => e.includes("重大度"))).toBe(true);
  });

  it("種別が空なら誤りにする", () => {
    const draft = { ...draftOf({}), targets: [] };
    expect(validateUserRule(draft, existing, null).some((e) => e.includes("種別"))).toBe(true);
  });
});
