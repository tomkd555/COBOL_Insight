/**
 * An index over the rule catalog the engine returned, so a finding can be resolved to its rule in
 * constant time. Rule names, categories and prose live only in the engine; this holds no wording of
 * its own beyond the placeholder used for an id the catalog does not know.
 */

import type { RuleCatalogEntry } from "../../../shared/ipc";
import { text } from "../i18n/text";
import { severityOf, type Severity } from "./severity";

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

/**
 * The rule for an id. A finding whose rule the catalog does not know still has to be shown, so it
 * falls back to a medium-severity entry whose name is the id itself — except for the write-back's
 * own verification, which is not a rule the engine lists and is named for what it is.
 */
export function ruleOf(index: RuleIndex, id: string): IndexedRule {
  return (
    index.byId.get(id) ?? {
      id,
      name: id === REPARSE_RULE_ID ? text.source.save : id,
      category: "",
      severity: "medium",
      hasFix: false,
    }
  );
}
