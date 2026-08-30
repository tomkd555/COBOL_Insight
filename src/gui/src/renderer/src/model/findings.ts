/**
 * The view model behind the problems panel (pure, independent of React). The lint and sql-lint
 * findings become one table, filtered by severity, rule, file and source.
 *
 * Severity comes from the rule index rather than the SARIF level: level has three values and says
 * nothing rule-specific, whereas the interface grades findings in four and does so per rule.
 */

import type { SarifFinding } from "../../../shared/ipc";
import { ruleOf, type RuleIndex } from "./ruleIndex";
import { compareSeverity, visibleSeverities, type Severity } from "./severity";

/** Where a finding came from: the code lint, the SQL lint, or the reparse check after a save. */
export type FindingSource = "lint" | "sql" | "save";

/** The value that means "no restriction" in the rule, file and source selectors. */
export const ALL = "all";

/** One finding with its origin attached, before it is turned into a row. */
export interface PanelFinding {
  readonly finding: SarifFinding;
  readonly source: FindingSource;
}

/** One row ready to display. */
export interface FindingRow extends PanelFinding {
  readonly severity: Severity;
  readonly ruleName: string;
  readonly hasFix: boolean;
  /** Row identity, so duplicates at the same position under the same rule stay distinguishable. */
  readonly key: string;
}

/** How the table is sorted. */
export type FindingSort = "severity" | "file" | "rule";

export interface FindingFilter {
  /** The severity chips. */
  readonly severity: Readonly<Record<Severity, boolean>>;
  /** The configured threshold; nothing below it reaches the table at all. */
  readonly threshold: Severity;
  /** "all" or a rule id. */
  readonly rule: string;
  /** "all" or a relative path. */
  readonly file: string;
  /** "all", "lint", "sql" or "save". */
  readonly source: string;
  /** Free-text search over the message, the rule name and the file. */
  readonly text: string;
  readonly sort: FindingSort;
}

export const initialFindingFilter: FindingFilter = {
  severity: { high: true, medium: true, low: true, warning: true },
  threshold: "warning",
  rule: ALL,
  file: ALL,
  source: ALL,
  text: "",
  sort: "severity",
};

/** Merges the lint, SQL and save findings into one list. */
export function mergeFindings(
  lint: readonly SarifFinding[],
  sql: readonly SarifFinding[],
  save: readonly SarifFinding[] = [],
): PanelFinding[] {
  return [
    ...lint.map<PanelFinding>((finding) => ({ finding, source: "lint" })),
    ...sql.map<PanelFinding>((finding) => ({ finding, source: "sql" })),
    ...save.map<PanelFinding>((finding) => ({ finding, source: "save" })),
  ];
}

/** Rule ids that occur among the findings, sorted naturally (R001..R031, then S001..). */
export function ruleIds(rows: readonly PanelFinding[]): string[] {
  return [...new Set(rows.map((row) => row.finding.ruleId))].sort(compareRuleId);
}

/** Files that occur among the findings, in ascending path order. */
export function fileNames(rows: readonly PanelFinding[]): string[] {
  return [...new Set(rows.map((row) => row.finding.file))].sort();
}

/** Counts by severity over every finding, before any filtering. */
export function severityCounts(
  rows: readonly PanelFinding[],
  index: RuleIndex,
): Record<Severity, number> {
  const counts: Record<Severity, number> = { high: 0, medium: 0, low: 0, warning: 0 };
  for (const row of rows) {
    counts[ruleOf(index, row.finding.ruleId).severity] += 1;
  }
  return counts;
}

/** Splits a rule id into its letter prefix and its number so the two sort independently. */
const RULE_ID_PATTERN = /^([A-Za-z]+)(\d+)$/;

function compareRuleId(a: string, b: string): number {
  const pa = RULE_ID_PATTERN.exec(a);
  const pb = RULE_ID_PATTERN.exec(b);
  if (pa === null || pb === null) return a < b ? -1 : a > b ? 1 : 0;
  if (pa[1] !== pb[1]) return pa[1] < pb[1] ? -1 : 1;
  return Number(pa[2]) - Number(pb[2]);
}

function byPosition(a: FindingRow, b: FindingRow): number {
  if (a.finding.file !== b.finding.file) return a.finding.file < b.finding.file ? -1 : 1;
  return a.finding.startLine - b.finding.startLine;
}

function comparator(sort: FindingSort): (a: FindingRow, b: FindingRow) => number {
  switch (sort) {
    case "file":
      return byPosition;
    case "rule":
      return (a, b) => {
        const byRule = compareRuleId(a.finding.ruleId, b.finding.ruleId);
        return byRule !== 0 ? byRule : byPosition(a, b);
      };
    default:
      return (a, b) => {
        const bySeverity = compareSeverity(a.severity, b.severity);
        return bySeverity !== 0 ? bySeverity : byPosition(a, b);
      };
  }
}

/**
 * Filters and sorts the rows. The configured threshold sets the range of severities that can be
 * shown at all, and the severity chips narrow further within it (the two combine as an AND).
 */
export function filterFindings(
  rows: readonly PanelFinding[],
  index: RuleIndex,
  filter: FindingFilter,
): FindingRow[] {
  const needle = filter.text.trim().toLowerCase();
  const allowed = new Set(visibleSeverities(filter.threshold));
  const result: FindingRow[] = [];
  for (const [position, row] of rows.entries()) {
    const rule = ruleOf(index, row.finding.ruleId);
    if (!allowed.has(rule.severity)) continue;
    if (!filter.severity[rule.severity]) continue;
    if (filter.rule !== ALL && row.finding.ruleId !== filter.rule) continue;
    if (filter.file !== ALL && row.finding.file !== filter.file) continue;
    if (filter.source !== ALL && row.source !== filter.source) continue;
    if (needle !== "") {
      const haystack = (row.finding.message + rule.name + row.finding.file).toLowerCase();
      if (!haystack.includes(needle)) continue;
    }
    result.push({
      ...row,
      severity: rule.severity,
      ruleName: rule.name,
      hasFix: rule.hasFix,
      key: `${row.source}:${position}`,
    });
  }
  result.sort(comparator(filter.sort));
  return result;
}

/** Whether the threshold is currently hiding anything. */
export function thresholdHides(threshold: Severity): boolean {
  return visibleSeverities(threshold).length < 4;
}
