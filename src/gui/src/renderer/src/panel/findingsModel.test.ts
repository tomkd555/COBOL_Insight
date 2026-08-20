import { describe, it, expect } from "vitest";
import type { SarifFinding } from "../../../shared/engine-api";
import { FIXTURE_CATALOG } from "../data/__fixtures__/catalog";
import {
  ALL,
  fileOptions,
  filterFindings,
  initialFindingFilter,
  mergeFindings,
  ruleOptions,
  severityCounts,
  thresholdNote,
  type FindingFilter,
} from "./findingsModel";

/** R004=高 / R008=中 / R002=低 / R009=推奨。S001=中。 */
function finding(ruleId: string, file: string, startLine: number, message = ""): SarifFinding {
  return { ruleId, level: "warning", message, file, startLine, startColumn: 1 };
}

const LINT: readonly SarifFinding[] = [
  finding("R008", "b.cbl", 20, "中の指摘"),
  finding("R004", "b.cbl", 10, "高の指摘"),
  finding("R002", "a.cbl", 5, "低の指摘"),
  finding("R009", "a.cbl", 30, "推奨の指摘"),
];

const SQL: readonly SarifFinding[] = [finding("S001", "a.cbl", 12, "SQL の指摘")];

function filter(overrides: Partial<FindingFilter> = {}): FindingFilter {
  return { ...initialFindingFilter, ...overrides };
}

describe("mergeFindings(2 つの SARIF をまとめる)", () => {
  it("出所を添えて 1 つの並びにする", () => {
    const merged = mergeFindings(LINT, SQL);
    expect(merged).toHaveLength(5);
    expect(merged.filter((row) => row.source === "sql")).toHaveLength(1);
  });
});

describe("filterFindings(絞り込みと並び)", () => {
  const merged = mergeFindings(LINT, SQL);

  it("重大度の重い順、同じ重大度なら資産名・行の順に並べる", () => {
    const rows = filterFindings(merged, FIXTURE_CATALOG, filter());
    expect(rows.map((row) => `${row.finding.ruleId}:${row.finding.file}:${row.finding.startLine}`)).toEqual([
      "R004:b.cbl:10",
      "S001:a.cbl:12",
      "R008:b.cbl:20",
      "R002:a.cbl:5",
      "R009:a.cbl:30",
    ]);
  });

  it("重大度チップを外した重大度は出さない", () => {
    const rows = filterFindings(
      merged,
      FIXTURE_CATALOG,
      filter({ severity: { high: false, medium: true, low: true, warning: true } }),
    );
    expect(rows.some((row) => row.severity === "high")).toBe(false);
    expect(rows).toHaveLength(4);
  });

  it("しきい値より低い重大度は、チップが ON でも出さない", () => {
    const rows = filterFindings(merged, FIXTURE_CATALOG, filter({ threshold: "medium" }));
    expect(rows.map((row) => row.severity)).toEqual(["high", "medium", "medium"]);
  });

  it("ルール・資産・出所で絞る", () => {
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ rule: "R004" }))).toHaveLength(1);
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ file: "a.cbl" }))).toHaveLength(3);
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ source: "sql" }))).toHaveLength(1);
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ source: "lint" }))).toHaveLength(4);
  });

  it("絞り込みは論理積で重ねる", () => {
    const rows = filterFindings(
      merged,
      FIXTURE_CATALOG,
      filter({ file: "a.cbl", source: "lint" }),
    );
    expect(rows.map((row) => row.finding.ruleId)).toEqual(["R002", "R009"]);
  });

  it("内容・ルール名・資産名を対象に大小を問わず探す", () => {
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ text: "SQL の" }))).toHaveLength(1);
    expect(filterFindings(merged, FIXTURE_CATALOG, filter({ text: "B.CBL" }))).toHaveLength(2);
  });

  it("重大度と修正案の有無をルール一覧から引く", () => {
    const rows = filterFindings(merged, FIXTURE_CATALOG, filter({ rule: "R004" }));
    expect(rows[0].severity).toBe("high");
    expect(rows[0].hasFix).toBe(true);
    expect(rows[0].ruleName).not.toBe("");
  });

  it("同じ位置・同じルールが重複しても行の識別子は分かれる", () => {
    const duplicated = mergeFindings([LINT[0], LINT[0]], []);
    const rows = filterFindings(duplicated, FIXTURE_CATALOG, filter());
    expect(new Set(rows.map((row) => row.key)).size).toBe(2);
  });
});

describe("選択肢と件数", () => {
  const merged = mergeFindings(LINT, SQL);

  it("ルール選択肢は「すべて」を先頭に ID 順で並べる", () => {
    expect(ruleOptions(merged, FIXTURE_CATALOG).map((option) => option.value)).toEqual([
      ALL,
      "R002",
      "R004",
      "R008",
      "R009",
      "S001",
    ]);
  });

  it("資産選択肢は「すべて」を先頭に昇順で並べる", () => {
    expect(fileOptions(merged).map((option) => option.value)).toEqual([ALL, "a.cbl", "b.cbl"]);
  });

  it("重大度ごとの件数を数える", () => {
    expect(severityCounts(merged, FIXTURE_CATALOG)).toEqual({
      high: 1,
      medium: 2,
      low: 1,
      warning: 1,
    });
  });
});

describe("thresholdNote(しきい値の注記)", () => {
  it("すべての重大度を通すときは注記を出さない", () => {
    expect(thresholdNote("warning")).toBeNull();
  });

  it("絞られているときだけ注記を出す", () => {
    expect(thresholdNote("medium")).not.toBeNull();
  });
});
