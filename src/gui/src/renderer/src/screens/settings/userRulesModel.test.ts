import { describe, it, expect } from "vitest";
import { normalizeUserRule } from "../../../../shared/userRules";
import { targetsLabel, testResultLabel, testUserRulePattern } from "./userRulesModel";

/** 固定形式の桁を保った行を組む。1〜6桁は一連番号、7桁目は標識、8桁目から本体である。 */
function fixed(indicator: string, body: string): string {
  return `000100${indicator}${body}`;
}

function draftOf(patch: Record<string, unknown>) {
  return normalizeUserRule({
    id: "U001",
    name: "試験",
    pattern: "GOBACK",
    message: "GOBACK を使っている",
    ...patch,
  });
}

describe("testUserRulePattern", () => {
  it("本体領域の一致を行番号と桁で示す", () => {
    const result = testUserRulePattern(draftOf({}), fixed(" ", "    GOBACK."));
    expect(result.error).toBeNull();
    expect(result.matches).toHaveLength(1);
    expect(result.matches[0].line).toBe(1);
    expect(result.matches[0].column).toBe(12);
    expect(result.matches[0].matched).toBe("GOBACK");
  });

  it("注記行は走査しない", () => {
    const result = testUserRulePattern(draftOf({}), fixed("*", "    GOBACK."));
    expect(result.matches).toEqual([]);
    expect(result.scannedLines).toBe(0);
  });

  it("識別領域(73桁目以降)は対象外である", () => {
    const line = fixed(" ", `${" ".repeat(65)}GOBACK`);
    expect(testUserRulePattern(draftOf({}), line).matches).toEqual([]);
  });

  it("行全体を対象にする指定では注記行も走査する", () => {
    const draft = draftOf({ pattern: "TODO", wholeLine: true });
    const result = testUserRulePattern(draft, fixed("*", "    TODO 直す"));
    expect(result.matches).toHaveLength(1);
  });

  it("大小を区別しない指定が効く", () => {
    const result = testUserRulePattern(draftOf({ ignoreCase: true }), fixed(" ", "    goback."));
    expect(result.matches).toHaveLength(1);
  });

  it("除外する正規表現に一致する行を落とす", () => {
    const draft = draftOf({ excludePattern: "NORMAL-END" });
    const result = testUserRulePattern(draft, fixed(" ", "    GOBACK. *> NORMAL-END"));
    expect(result.matches).toEqual([]);
  });

  it("BMS は桁の割り当てを持たないため行全体を見る", () => {
    const draft = draftOf({ pattern: "COLOR=RED", targets: ["BMS"] });
    const result = testUserRulePattern(draft, "FLD1 DFHMDF POS=(1,1),COLOR=RED");
    expect(result.matches).toHaveLength(1);
  });

  it("メッセージの ${match} を一致した文字列へ置き換える", () => {
    const draft = draftOf({ pattern: "MOVE\\s+\\S+", message: "禁じた書き方である: ${match}" });
    const result = testUserRulePattern(draft, fixed(" ", "    MOVE WS-A TO WS-B."));
    expect(result.matches[0].message).toBe("禁じた書き方である: MOVE WS-A");
  });

  it("一致する行をすべて挙げ、走査した行数を数える", () => {
    const text = [
      fixed(" ", "    GOBACK."),
      fixed("*", "    GOBACK."),
      fixed(" ", "    DISPLAY WS-A."),
      fixed(" ", "    GOBACK."),
    ].join("\n");
    const result = testUserRulePattern(draftOf({}), text);
    expect(result.matches.map((match) => match.line)).toEqual([1, 4]);
    expect(result.scannedLines).toBe(3);
  });

  it("解釈できない正規表現は理由を返す", () => {
    const result = testUserRulePattern(draftOf({ pattern: "A(" }), "text");
    expect(result.error).not.toBeNull();
    expect(result.matches).toEqual([]);
  });

  it("正規表現が空なら何も走査しない", () => {
    const result = testUserRulePattern({ ...draftOf({}), pattern: "  " }, "text");
    expect(result.scannedLines).toBe(0);
    expect(result.error).toBeNull();
  });
});

describe("testResultLabel", () => {
  it("走査した行数と一致件数を示す", () => {
    const result = testUserRulePattern(draftOf({}), fixed(" ", "    GOBACK."));
    expect(testResultLabel(result)).toBe("1 行を走査し、1 件が一致しました。");
  });

  it("本文が無いときは何も示さない", () => {
    expect(testResultLabel(testUserRulePattern(draftOf({}), ""))).toBe("");
  });

  it("解釈できない正規表現の理由を示す", () => {
    const result = testUserRulePattern(draftOf({ pattern: "A(" }), "text");
    expect(testResultLabel(result)).toContain("正規表現を解釈できませんでした");
  });
});

describe("targetsLabel", () => {
  it("種別を日本語の呼び名でつなぐ", () => {
    expect(targetsLabel(["COBOL", "COPYBOOK", "BMS"])).toBe("COBOL・コピー句・BMS");
  });
});
