import { describe, expect, it } from "vitest";
import type { RuleCatalogEntry, SarifFinding } from "../../../shared/ipc";
import { buildRuleIndex } from "./ruleIndex";
import {
  ALL,
  fileNames,
  filterFindings,
  initialFindingFilter,
  mergeFindings,
  ruleIds,
  severityCounts,
  thresholdHides,
} from "./findings";

function rule(id: string, severity: string, name = `name of ${id}`): RuleCatalogEntry {
  return {
    id,
    name,
    category: "c",
    severity,
    phase: "SYNTAX",
    hasFix: false,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: [],
    targets: [],
    needs: [],
    summary: "",
    rationale: "",
    detection: "",
    remedy: "",
    badExample: "",
    goodExample: "",
  };
}

const INDEX = buildRuleIndex([
  rule("R001", "HIGH"),
  rule("R002", "MEDIUM"),
  rule("R010", "LOW"),
  rule("S001", "ADVISORY"),
]);

function finding(ruleId: string, file: string, startLine: number, message = "m"): SarifFinding {
  return { ruleId, level: "warning", message, file, startLine, startColumn: 1 };
}

const LINT = [
  finding("R002", "b.cbl", 20),
  finding("R001", "b.cbl", 10),
  finding("R010", "a.cbl", 5),
];
const SQL = [finding("S001", "a.cbl", 7)];

describe("mergeFindings", () => {
  it("tags each finding with where it came from", () => {
    const merged = mergeFindings(LINT, SQL, [finding("R001", "c.cbl", 1)]);
    expect(merged.map((row) => row.source)).toEqual(["lint", "lint", "lint", "sql", "save"]);
  });
});

describe("filterFindings", () => {
  const merged = mergeFindings(LINT, SQL);

  it("sorts by severity, then by file, then by line", () => {
    const rows = filterFindings(merged, INDEX, initialFindingFilter);
    expect(rows.map((row) => row.finding.ruleId)).toEqual(["R001", "R002", "R010", "S001"]);
  });

  it("sorts by position when asked for file order", () => {
    const rows = filterFindings(merged, INDEX, { ...initialFindingFilter, sort: "file" });
    expect(rows.map((row) => `${row.finding.file}:${row.finding.startLine}`)).toEqual([
      "a.cbl:5",
      "a.cbl:7",
      "b.cbl:10",
      "b.cbl:20",
    ]);
  });

  it("sorts rule ids naturally rather than as text", () => {
    const rows = filterFindings(merged, INDEX, { ...initialFindingFilter, sort: "rule" });
    // R010 must follow R002, which a plain string sort would get wrong.
    expect(rows.map((row) => row.finding.ruleId)).toEqual(["R001", "R002", "R010", "S001"]);
  });

  it("hides everything below the threshold", () => {
    const rows = filterFindings(merged, INDEX, { ...initialFindingFilter, threshold: "medium" });
    expect(rows.map((row) => row.severity)).toEqual(["high", "medium"]);
  });

  it("narrows further within the threshold when a chip is switched off", () => {
    const rows = filterFindings(merged, INDEX, {
      ...initialFindingFilter,
      severity: { ...initialFindingFilter.severity, high: false },
    });
    expect(rows.some((row) => row.severity === "high")).toBe(false);
  });

  it("filters by rule, by file and by source", () => {
    expect(
      filterFindings(merged, INDEX, { ...initialFindingFilter, rule: "R001" }).map(
        (row) => row.finding.ruleId,
      ),
    ).toEqual(["R001"]);
    expect(
      filterFindings(merged, INDEX, { ...initialFindingFilter, file: "a.cbl" })).toHaveLength(2);
    expect(
      filterFindings(merged, INDEX, { ...initialFindingFilter, source: "sql" }).map(
        (row) => row.finding.ruleId,
      ),
    ).toEqual(["S001"]);
  });

  it("searches the message, the rule name and the file", () => {
    const rows = mergeFindings([finding("R001", "a.cbl", 1, "桁あふれの恐れ")], []);
    expect(filterFindings(rows, INDEX, { ...initialFindingFilter, text: "桁あふれ" })).toHaveLength(1);
    expect(filterFindings(rows, INDEX, { ...initialFindingFilter, text: "name of r001" })).toHaveLength(1);
    expect(filterFindings(rows, INDEX, { ...initialFindingFilter, text: "a.cbl" })).toHaveLength(1);
    expect(filterFindings(rows, INDEX, { ...initialFindingFilter, text: "nothing" })).toHaveLength(0);
  });

  it("gives each row a key that survives duplicates at the same position", () => {
    const duplicated = mergeFindings([finding("R001", "a.cbl", 1), finding("R001", "a.cbl", 1)], []);
    const rows = filterFindings(duplicated, INDEX, initialFindingFilter);
    expect(new Set(rows.map((row) => row.key)).size).toBe(2);
  });

  it("still shows a finding whose rule the catalog does not know", () => {
    const rows = filterFindings(mergeFindings([finding("R999", "a.cbl", 1)], []), INDEX, initialFindingFilter);
    expect(rows[0]).toMatchObject({ severity: "medium", ruleName: "R999" });
  });
});

describe("the selector options", () => {
  const merged = mergeFindings(LINT, SQL);

  it("lists the rule ids in natural order", () => {
    expect(ruleIds(merged)).toEqual(["R001", "R002", "R010", "S001"]);
  });

  it("lists the files in ascending order, without repeats", () => {
    expect(fileNames(merged)).toEqual(["a.cbl", "b.cbl"]);
  });

  it("counts every finding by severity, before any filtering", () => {
    expect(severityCounts(merged, INDEX)).toEqual({ high: 1, medium: 1, low: 1, warning: 1 });
  });
});

describe("thresholdHides", () => {
  it("is true for every threshold that excludes something", () => {
    expect(thresholdHides("warning")).toBe(false);
    expect(thresholdHides("low")).toBe(true);
    expect(thresholdHides("high")).toBe(true);
  });
});

describe("the all sentinel", () => {
  it("is the value the rule, file and source selectors use for no restriction", () => {
    expect(initialFindingFilter.rule).toBe(ALL);
    expect(initialFindingFilter.file).toBe(ALL);
    expect(initialFindingFilter.source).toBe(ALL);
  });
});
