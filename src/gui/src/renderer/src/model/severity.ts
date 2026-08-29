/**
 * The four severities the interface shows, and the mapping from the engine's own vocabulary.
 *
 * The engine grades rules HIGH/MEDIUM/LOW/ADVISORY. SARIF's level has only three values and says
 * nothing rule-specific, so severity is always taken from the rule catalog, never from the level.
 */

export type Severity = "high" | "medium" | "low" | "warning";

/** Ordered from most to least severe. The threshold and the sort both read this order. */
export const SEVERITIES: readonly Severity[] = ["high", "medium", "low", "warning"];

const RANK: Readonly<Record<Severity, number>> = { high: 0, medium: 1, low: 2, warning: 3 };

/** Maps an engine severity onto the interface's vocabulary. Anything unknown becomes medium. */
export function severityOf(engineSeverity: string): Severity {
  switch (engineSeverity.toUpperCase()) {
    case "HIGH":
      return "high";
    case "LOW":
      return "low";
    case "ADVISORY":
      return "warning";
    default:
      return "medium";
  }
}

/** Severities at or above the threshold, in order. */
export function visibleSeverities(threshold: Severity): Severity[] {
  return SEVERITIES.filter((severity) => RANK[severity] <= RANK[threshold]);
}

/** Compares two severities, most severe first. */
export function compareSeverity(left: Severity, right: Severity): number {
  return RANK[left] - RANK[right];
}
