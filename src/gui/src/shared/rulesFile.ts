/**
 * The one rule configuration file (rules.json). The GUI writes it, the engine reads it through
 * --rules on every subcommand. It replaces V1's separate rules-config.json and user-rules.json.
 *
 * Reading is deliberately forgiving: a missing, empty or corrupt file yields an empty configuration
 * rather than an error, because a rule file the GUI cannot read must not stop the application from
 * starting. What the engine makes of the same file is reported back through RuleCatalog.ruleErrors.
 *
 * A custom rule is carried across as it was written, with only its id checked. The engine is the
 * validator (docs/rules.md); coercing the definitions here would either drop fields a newer engine
 * understands or rewrite an author's rule into one that means something else — an empty
 * `excludeRegex`, for instance, excludes every line.
 */

/** The version the engine accepts. */
export const RULES_FILE_VERSION = 2;

/** Per-rule overrides. An absent field means "leave the engine's default alone". */
export interface RuleOverride {
  readonly enabled?: boolean;
  /** HIGH/MEDIUM/LOW/ADVISORY. */
  readonly severity?: string;
}

/** A regular expression over each line of decoded source. */
export interface LineMatch {
  readonly kind: "line";
  /** Java regular expression. A line matching it anywhere in the scanned span is reported. */
  readonly regex: string;
  readonly ignoreCase?: boolean;
  /** programArea skips comment lines and scans columns 8-72; wholeLine scans the raw line. */
  readonly area?: "programArea" | "wholeLine";
  /** A line also matching this is not reported. */
  readonly excludeRegex?: string;
}

/** Statements of the named verbs that carry none of the named clauses. */
export interface StatementMatch {
  readonly kind: "statement";
  readonly verb: readonly string[];
  readonly missingClause?: readonly string[];
  /** Only look inside paragraphs whose name this regular expression finds. */
  readonly inParagraph?: string;
}

/** How far forward a `checked-after` rule follows the control flow. */
export type CheckedAfterScope =
  | "untilNextMatchingStatement"
  | "untilParagraphEnd"
  | "untilProgramEnd";

/** A statement whose status has to be examined before the flow leaves the declared scope. */
export interface CheckedAfterMatch {
  readonly kind: "checked-after";
  readonly after: { readonly verb: string; readonly textRegex?: string };
  readonly checks: { readonly dataItem: readonly string[] };
  readonly scope?: CheckedAfterScope;
  /** Whether every forward path has to check, or one is enough. */
  readonly onEveryPath?: boolean;
}

export type CustomMatch = LineMatch | StatementMatch | CheckedAfterMatch;

/** The three detection shapes a custom rule can take. */
export const CUSTOM_MATCH_KINDS: readonly CustomMatch["kind"][] = [
  "line",
  "statement",
  "checked-after",
];

/** One user-defined rule. Ids start with U so they cannot collide with the built-ins (R, S). */
export interface CustomRule {
  readonly id: string;
  readonly name: string;
  /** The finding message. ${match} is replaced by the matched text. */
  readonly message: string;
  readonly match: CustomMatch;
  /** HIGH/MEDIUM/LOW/ADVISORY. Absent means MEDIUM. */
  readonly severity?: string;
  /** Asset kinds to scan (COBOL/COPYBOOK/JCL/BMS). Absent means COBOL alone. */
  readonly targets?: readonly string[];
  /** Subcommands the rule runs under (LINT/SQL_LINT/REPORT/FIX/SCAN). */
  readonly commands?: readonly string[];
  readonly category?: string;
  readonly summary?: string;
  readonly rationale?: string;
  readonly remedy?: string;
}

export interface RulesFile {
  readonly version: number;
  /** Overrides keyed by rule id. */
  readonly rules: Readonly<Record<string, RuleOverride>>;
  readonly custom: readonly CustomRule[];
}

export function emptyRulesFile(): RulesFile {
  return { version: RULES_FILE_VERSION, rules: {}, custom: [] };
}

/** Coerces a parsed value into the rule-file shape, dropping what cannot be used. */
export function normalizeRulesFile(value: unknown): RulesFile {
  const source = asObject(value);
  const rules: Record<string, RuleOverride> = {};
  for (const [id, raw] of Object.entries(asObject(source["rules"]))) {
    const override = asObject(raw);
    const entry: { enabled?: boolean; severity?: string } = {};
    if (typeof override["enabled"] === "boolean") entry.enabled = override["enabled"];
    if (typeof override["severity"] === "string") entry.severity = override["severity"];
    rules[id] = entry;
  }
  const custom: CustomRule[] = [];
  for (const raw of Array.isArray(source["custom"]) ? source["custom"] : []) {
    // A definition without an id cannot be addressed, so it is dropped rather than defaulted; the
    // rest is carried across untouched for the engine to judge.
    if (typeof asObject(raw)["id"] === "string" && asObject(raw)["id"] !== "") {
      custom.push(raw as CustomRule);
    }
  }
  return {
    version: typeof source["version"] === "number" ? source["version"] : RULES_FILE_VERSION,
    rules,
    custom,
  };
}

function asObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
