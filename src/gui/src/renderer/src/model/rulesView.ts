/**
 * The rule list as the screen arranges it, and the edits the screen makes to the rule configuration
 * file.
 *
 * Whether a rule is in effect is never worked out here. The engine applies the configuration file
 * and reports the outcome in `enabled`; the screen writes the file, asks the engine again, and
 * renders what comes back. Deciding it locally would let the two drift apart the moment the engine
 * gained a reason of its own to disable a rule.
 */

import type { RuleCatalogEntry } from "../../../shared/ipc";
import type { RuleOverride, RulesFile } from "../../../shared/rulesFile";

export interface RuleGroup {
  /** The heading the group is listed under: a technology, or the category of a user-defined rule. */
  readonly category: string;
  readonly rules: readonly RuleCatalogEntry[];
}

/**
 * Whether the rule answers the search, matched over its id, its name, its summary, its technology,
 * its category and its symptom — so "S0C7", "Db2" and "異常終了" each find the rules they name.
 */
export function matchesQuery(entry: RuleCatalogEntry, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === "") {
    return true;
  }
  return `${entry.id} ${entry.name} ${entry.summary} ${entry.technology} ${entry.category} ${entry.symptom}`
    .toLowerCase()
    .includes(needle);
}

export function filterRules(
  entries: readonly RuleCatalogEntry[],
  query: string,
): RuleCatalogEntry[] {
  return entries.filter((entry) => matchesQuery(entry, query));
}

/**
 * The heading a rule is listed under. A built-in rule goes under the technology it is about, which is
 * how the person who owns the JCL, or the Db2 access, looks for the rules that concern them; what
 * goes wrong (the category) is shown on the row. A user-defined rule keeps its own category, and so
 * does any rule of an engine that names no technology.
 */
export function headingOf(entry: RuleCatalogEntry): string {
  return entry.source === "user" || entry.technology === "" ? entry.category : entry.technology;
}

/**
 * Groups the rules by {@link headingOf}, in the order each heading first appears and with the rules
 * in the order the engine listed them: it sorts by id, so the user-defined rules follow the built-in
 * ones.
 */
export function groupByCategory(entries: readonly RuleCatalogEntry[]): RuleGroup[] {
  const groups = new Map<string, RuleCatalogEntry[]>();
  for (const entry of entries) {
    const heading = headingOf(entry);
    const bucket = groups.get(heading);
    if (bucket === undefined) {
      groups.set(heading, [entry]);
    } else {
      bucket.push(entry);
    }
  }
  return [...groups].map(([category, rules]) => ({ category, rules }));
}

/** The override written for this rule, or an empty one. */
export function overrideOf(file: RulesFile, id: string): RuleOverride {
  return file.rules[id] ?? {};
}

/**
 * Writes `enabled` for each id. The value is stated even when it agrees with the rule's own
 * default, so a later change of that default cannot silently turn a rule the user switched off back
 * on.
 */
export function setEnabled(file: RulesFile, ids: readonly string[], enabled: boolean): RulesFile {
  const rules = { ...file.rules };
  for (const id of ids) {
    rules[id] = { ...rules[id], enabled };
  }
  return { ...file, rules };
}

/** Writes a severity override, or removes it when the severity is null ("back to the default"). */
export function setSeverity(file: RulesFile, id: string, severity: string | null): RulesFile {
  const rules = { ...file.rules };
  const { severity: _dropped, ...rest } = rules[id] ?? {};
  const next: RuleOverride = severity === null ? rest : { ...rest, severity };
  // An override with nothing left in it is removed, so the file lists only what was actually set.
  if (next.enabled === undefined && next.severity === undefined) {
    delete rules[id];
  } else {
    rules[id] = next;
  }
  return { ...file, rules };
}
