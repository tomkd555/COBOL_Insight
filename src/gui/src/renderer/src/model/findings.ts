/**
 * The view model behind the problems panel (pure, independent of React). The lint and sql-lint
 * findings become one table, filtered by severity, source and free text.
 *
 * Severity comes from the rule index rather than the SARIF level: level has three values and says
 * nothing rule-specific, whereas the interface grades findings in four and does so per rule. The
 * level is passed along all the same, because the engine's own diagnostics have no rule to grade
 * them and the index falls back to it.
 */

import type { SarifFinding } from "../../../shared/ipc";
import { ruleOf, type RuleIndex } from "./ruleIndex";
import { compareSeverity, visibleSeverities, type Severity } from "./severity";

/** One finding with its origin attached, before it is turned into a row. */
export interface PanelFinding {
  readonly finding: SarifFinding;
  /** Where the finding came from: the code lint, the SQL lint, or the reparse check after a save. */
  readonly source: "lint" | "sql" | "save";
}

/** One row ready to display. */
export interface FindingRow extends PanelFinding {
  readonly severity: Severity;
  readonly ruleName: string;
  readonly hasFix: boolean;
  /**
   * Row identity: what the finding is about, never where it sits in the list, so a scoped run that
   * reorders the findings leaves the selected row on the same finding. The count on the end keeps
   * duplicates at the same position under the same rule distinguishable.
   */
  readonly key: string;
}

/** How the table is sorted. */
export type FindingSort = "severity" | "file" | "rule";

export interface FindingFilter {
  /** The severity chips. */
  readonly severity: Readonly<Record<Severity, boolean>>;
  /** The configured threshold; nothing below it reaches the table at all. */
  readonly threshold: Severity;
  /** Free-text search over the message, the rule id, the rule name and the file. */
  readonly text: string;
  readonly sort: FindingSort;
}

export const initialFindingFilter: FindingFilter = {
  severity: { high: true, medium: true, low: true, warning: true },
  threshold: "warning",
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
  // How many rows already carry each identity. Counted over every finding rather than the ones that
  // pass, so which duplicate is which does not move when the filter changes.
  const seen = new Map<string, number>();
  for (const row of rows) {
    const identity = `${row.source}:${row.finding.file}:${row.finding.startLine}:${row.finding.ruleId}`;
    const nth = seen.get(identity) ?? 0;
    seen.set(identity, nth + 1);
    const rule = ruleOf(index, row.finding.ruleId, row.finding.level);
    if (!allowed.has(rule.severity)) continue;
    if (!filter.severity[rule.severity]) continue;
    if (needle !== "") {
      // The rule id is in the haystack because the panel has no rule selector: a reader who wants one
      // rule's findings types its id, the way the asset is narrowed by typing part of its path.
      const haystack = (
        row.finding.message +
        row.finding.ruleId +
        rule.name +
        row.finding.file
      ).toLowerCase();
      if (!haystack.includes(needle)) continue;
    }
    result.push({
      ...row,
      severity: rule.severity,
      ruleName: rule.name,
      hasFix: rule.hasFix,
      key: `${identity}:${nth}`,
    });
  }
  result.sort(comparator(filter.sort));
  return result;
}

/** How many findings the configured threshold keeps out of the table altogether. */
export function hiddenByThreshold(
  rows: readonly PanelFinding[],
  index: RuleIndex,
  threshold: Severity,
): number {
  const allowed = new Set(visibleSeverities(threshold));
  return rows.filter(
    (row) => !allowed.has(ruleOf(index, row.finding.ruleId, row.finding.level).severity),
  ).length;
}
