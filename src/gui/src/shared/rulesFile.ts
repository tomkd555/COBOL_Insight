/**
 * The one rule configuration file (rules.json). The GUI writes it, the engine reads it through
 * --rules on every subcommand. It replaces V1's separate rules-config.json and user-rules.json.
 *
 * Reading is deliberately forgiving: a missing, empty or corrupt file yields an empty configuration
 * rather than an error, because a rule file the GUI cannot read must not stop the application from
 * starting. What the engine makes of the same file is reported back through RuleCatalog.ruleErrors.
 */

/** The version the engine accepts. */
export const RULES_FILE_VERSION = 2;

/** Per-rule overrides. An absent field means "leave the engine's default alone". */
export interface RuleOverride {
  readonly enabled?: boolean;
  /** HIGH/MEDIUM/LOW/ADVISORY. */
  readonly severity?: string;
}

/** One user-defined rule. Ids start with U so they cannot collide with the built-ins (R, S). */
export interface CustomRule {
  readonly id: string;
  readonly name: string;
  readonly category: string;
  readonly severity: string;
  /** Asset kinds to scan (COBOL/COPYBOOK/JCL/BMS). */
  readonly targets: readonly string[];
  /** The regular expression to match (Java syntax). */
  readonly pattern: string;
  /** A line also matching this is not reported. Empty means no exclusion. */
  readonly excludePattern: string;
  readonly ignoreCase: boolean;
  /** When false, COBOL sources are matched over columns 8-72 with comment lines skipped. */
  readonly wholeLine: boolean;
  /** The finding message. ${match} is replaced by the matched text. */
  readonly message: string;
  /** Why it matters. Empty lets the engine supply its default wording. */
  readonly rationale: string;
  /** How to fix it. Empty lets the engine supply its default wording. */
  readonly remedy: string;
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
    const rule = toCustomRule(raw);
    if (rule !== null) custom.push(rule);
  }
  return {
    version: typeof source["version"] === "number" ? source["version"] : RULES_FILE_VERSION,
    rules,
    custom,
  };
}

/** A custom rule without an id cannot be addressed, so it is dropped rather than defaulted. */
function toCustomRule(value: unknown): CustomRule | null {
  const raw = asObject(value);
  const id = typeof raw["id"] === "string" ? raw["id"] : "";
  if (id === "") {
    return null;
  }
  return {
    id,
    name: asString(raw["name"]),
    category: asString(raw["category"]),
    severity: asString(raw["severity"]) === "" ? "MEDIUM" : asString(raw["severity"]),
    targets: Array.isArray(raw["targets"])
      ? raw["targets"].filter((element): element is string => typeof element === "string")
      : [],
    pattern: asString(raw["pattern"]),
    excludePattern: asString(raw["excludePattern"]),
    ignoreCase: raw["ignoreCase"] === true,
    wholeLine: raw["wholeLine"] === true,
    message: asString(raw["message"]),
    rationale: asString(raw["rationale"]),
    remedy: asString(raw["remedy"]),
  };
}

function asObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}
