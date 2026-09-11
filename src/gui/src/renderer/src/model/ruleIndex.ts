/**
 * An index over the rule catalog the engine returned, so a finding can be resolved to its rule in
 * constant time. Rule names, categories and prose live only in the engine; the only names held
 * outside it are those of the engine's own diagnostics, which no rule describes.
 */

import type { RuleCatalogEntry } from "../../../shared/ipc";
import { text } from "../i18n/text";
import { severityOf, severityOfLevel, type Severity } from "./severity";

/** The rule id the engine uses for a parse failure; the catalog does not list it. */
export const REPARSE_RULE_ID = "parse-failure";

/** What a finding row needs about its rule. */
export interface IndexedRule {
  readonly id: string;
  readonly name: string;
  readonly category: string;
  readonly severity: Severity;
  readonly hasFix: boolean;
}

export interface RuleIndex {
  readonly entries: readonly RuleCatalogEntry[];
  readonly byId: ReadonlyMap<string, IndexedRule>;
}

export const EMPTY_RULE_INDEX: RuleIndex = { entries: [], byId: new Map() };

export function buildRuleIndex(entries: readonly RuleCatalogEntry[]): RuleIndex {
  const byId = new Map<string, IndexedRule>();
  for (const entry of entries) {
    byId.set(entry.id, {
      id: entry.id,
      name: entry.name,
      category: entry.category,
      severity: severityOf(entry.severity),
      hasFix: entry.hasFix,
    });
  }
  return { entries: [...entries], byId };
}

/** The names of the engine's own finding ids, which are diagnostics rather than rules. */
const DIAGNOSTIC_NAMES: Readonly<Record<string, string>> = text.diagnostic;

/**
 * The rule for an id. The engine's own diagnostics are not rules and the catalog lists none of
 * them, so they are named here and graded from the SARIF level the finding carries — the only
 * grading such a finding has. An id that is neither is shown under the id itself.
 */
export function ruleOf(index: RuleIndex, id: string, level?: string): IndexedRule {
  return (
    index.byId.get(id) ?? {
      id,
      name: DIAGNOSTIC_NAMES[id] ?? id,
      category: "",
      severity: severityOfLevel(level),
      hasFix: false,
    }
  );
}
