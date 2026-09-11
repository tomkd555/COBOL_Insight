import type { RuleCatalog, RuleCatalogEntry } from "./ipc";

/**
 * Converts the engine's `rules --json` output into the catalog the screens read. This is the only
 * conversion point: rule names, categories and prose are never authored in the GUI.
 *
 * Missing fields are filled with defaults rather than dropping the entry, so a GUI newer than the
 * engine still renders the list. Only an entry without an id is discarded, because nothing can
 * address it.
 */
export function parseRuleCatalog(summary: Record<string, unknown> | null): RuleCatalog {
  if (summary === null) {
    throw new Error("the engine's rules subcommand returned no rule listing");
  }
  const rules: RuleCatalogEntry[] = [];
  for (const element of asArray(summary["rules"])) {
    const entry = toEntry(element);
    if (entry !== null) {
      rules.push(entry);
    }
  }
  return { rules, ruleErrors: asArray(summary["ruleErrors"]).map((error) => String(error)) };
}

function toEntry(value: unknown): RuleCatalogEntry | null {
  const id = asString(prop(value, "id"));
  if (id === undefined || id === "") {
    return null;
  }
  return {
    id,
    name: asString(prop(value, "name")) ?? "",
    category: asString(prop(value, "category")) ?? "",
    severity: asString(prop(value, "severity")) ?? "MEDIUM",
    hasFix: prop(value, "hasFix") === true,
    source: prop(value, "source") === "user" ? "user" : "builtin",
    // An absent field means an older engine, where every rule is in effect.
    enabled: prop(value, "enabled") !== false,
    commands: asStringArray(prop(value, "commands")),
    targets: asStringArray(prop(value, "targets")),
    summary: asString(prop(value, "summary")) ?? "",
    rationale: asString(prop(value, "rationale")) ?? "",
    detection: asString(prop(value, "detection")) ?? "",
    remedy: asString(prop(value, "remedy")) ?? "",
    badExample: asString(prop(value, "badExample")) ?? "",
    goodExample: asString(prop(value, "goodExample")) ?? "",
  };
}

function prop(value: unknown, key: string): unknown {
  return value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asStringArray(value: unknown): string[] {
  return asArray(value).filter((element): element is string => typeof element === "string");
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}
