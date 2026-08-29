/**
 * The custom rules of the rule configuration file, as the editor holds them.
 *
 * The editor has two panes over one value — a form and the raw JSON of the `custom` array — so the
 * text and the structure have to agree exactly: `parse` and `serialise` are inverses over anything
 * the form can produce.
 *
 * Only what is certainly wrong is checked here: the id format, duplicate ids, and the fields the
 * engine requires. A regular expression is never compiled locally, because the engine reads Java
 * syntax and this runtime reads JavaScript syntax; the two disagree on possessive quantifiers,
 * named groups and character-class syntax, and a local verdict would be wrong in both directions.
 * Everything else is the engine's judgement, obtained through `engine:validate-rules`.
 */

import type { CustomMatch, CustomRule } from "../../../shared/rulesFile";

/** `U` followed by 1 to 15 of the characters the engine accepts (docs/rules.md). */
const ID_PATTERN = /^U[0-9A-Za-z_-]{1,15}$/;

/** The severities a custom rule can declare. */
export const CUSTOM_SEVERITIES: readonly string[] = ["HIGH", "MEDIUM", "LOW", "ADVISORY"];

/** The asset kinds a custom rule can examine. */
export const CUSTOM_TARGETS: readonly string[] = ["COBOL", "COPYBOOK", "JCL", "BMS"];

/** The subcommands a custom rule can run under. */
export const CUSTOM_COMMANDS: readonly string[] = ["LINT", "SQL_LINT", "REPORT", "FIX", "SCAN"];

/** How far forward a `checked-after` rule follows the control flow. */
export const CUSTOM_SCOPES: readonly string[] = [
  "untilNextMatchingStatement",
  "untilParagraphEnd",
  "untilProgramEnd",
];

/** What is wrong with one definition. The wording lives in text.ts; this names the problem. */
export type CustomRuleProblemCode =
  | "idFormat"
  | "idDuplicate"
  | "nameRequired"
  | "messageRequired"
  | "regexRequired"
  | "verbRequired"
  | "afterVerbRequired"
  | "dataItemRequired";

export interface CustomRuleProblem {
  readonly code: CustomRuleProblemCode;
  /** Position in the `custom` array, so a definition with no usable id can still be pointed at. */
  readonly index: number;
  readonly id: string;
}

export interface CustomRulesParse {
  /** The definitions, or an empty array when the text could not be read. */
  readonly rules: readonly CustomRule[];
  /**
   * Why the text could not be read; null when it could. It is the JSON parser's own message, or
   * NOT_AN_ARRAY when the text parsed but is not a list of definitions.
   */
  readonly error: string | null;
}

/** The `error` of a text that parses as JSON but is not an array of objects. */
export const NOT_AN_ARRAY = "notAnArray";

/** The default detection of each kind. A new rule and a change of kind both start here. */
export function emptyMatch(kind: CustomMatch["kind"]): CustomMatch {
  switch (kind) {
    case "line":
      return { kind: "line", regex: "", ignoreCase: false, area: "programArea" };
    case "statement":
      return { kind: "statement", verb: [] };
    default:
      return {
        kind: "checked-after",
        after: { verb: "" },
        checks: { dataItem: [] },
        scope: "untilNextMatchingStatement",
        onEveryPath: false,
      };
  }
}

/** A new definition, with an id that does not collide with the ones already written. */
export function emptyCustomRule(existing: readonly CustomRule[]): CustomRule {
  const taken = new Set(existing.map((rule) => rule.id));
  let ordinal = existing.length + 1;
  while (taken.has(`U${String(ordinal).padStart(3, "0")}`)) {
    ordinal += 1;
  }
  return {
    id: `U${String(ordinal).padStart(3, "0")}`,
    name: "",
    message: "",
    severity: "MEDIUM",
    match: emptyMatch("line"),
  };
}

/**
 * The definition's detection. A definition typed by hand can carry no `match` at all, or one of a
 * kind this build does not know; the form still has to render, so it falls back to an empty line
 * match. The original text is not rewritten unless the form is then edited.
 */
export function matchOf(rule: CustomRule): CustomMatch {
  const match = rule.match as Partial<CustomMatch> | undefined;
  if (match === undefined || match === null) {
    return emptyMatch("line");
  }
  switch (match.kind) {
    case "line":
    case "statement":
    case "checked-after":
      return match as CustomMatch;
    default:
      return emptyMatch("line");
  }
}

/** Reads the raw JSON of the `custom` array. */
export function parse(raw: string): CustomRulesParse {
  let value: unknown;
  try {
    value = JSON.parse(raw === "" ? "[]" : raw);
  } catch (error) {
    return { rules: [], error: error instanceof Error ? error.message : String(error) };
  }
  if (!Array.isArray(value)) {
    return { rules: [], error: NOT_AN_ARRAY };
  }
  if (value.some((element) => element === null || typeof element !== "object" || Array.isArray(element))) {
    return { rules: [], error: NOT_AN_ARRAY };
  }
  return { rules: value as CustomRule[], error: null };
}

/** Writes the `custom` array as the text of the raw pane and of the rule file. */
export function serialise(defs: readonly CustomRule[]): string {
  return JSON.stringify(defs.map(pruned), null, 2);
}

/**
 * Drops the optional fields that were left empty.
 *
 * Writing them out would not be harmless: an empty `excludeRegex` matches every line and would
 * silence the rule entirely, and an empty optional string is not always read as "use the default".
 */
export function pruned(rule: CustomRule): CustomRule {
  const result: Record<string, unknown> = { id: rule.id, name: rule.name };
  putText(result, "category", rule.category);
  putText(result, "severity", rule.severity);
  putList(result, "targets", rule.targets);
  putList(result, "commands", rule.commands);
  result["message"] = rule.message;
  putText(result, "summary", rule.summary);
  putText(result, "rationale", rule.rationale);
  putText(result, "remedy", rule.remedy);
  result["match"] = prunedMatch(matchOf(rule));
  return result as unknown as CustomRule;
}

function prunedMatch(match: CustomMatch): CustomMatch {
  if (match.kind === "line") {
    const result: Record<string, unknown> = {
      kind: "line",
      regex: match.regex ?? "",
      ignoreCase: match.ignoreCase === true,
      area: match.area === "wholeLine" ? "wholeLine" : "programArea",
    };
    putText(result, "excludeRegex", match.excludeRegex);
    return result as unknown as CustomMatch;
  }
  if (match.kind === "statement") {
    const result: Record<string, unknown> = { kind: "statement", verb: nonBlank(match.verb) };
    putList(result, "missingClause", match.missingClause);
    putText(result, "inParagraph", match.inParagraph);
    return result as unknown as CustomMatch;
  }
  const after: Record<string, unknown> = { verb: match.after?.verb ?? "" };
  putText(after, "textRegex", match.after?.textRegex);
  return {
    kind: "checked-after",
    after,
    checks: { dataItem: nonBlank(match.checks?.dataItem) },
    scope: match.scope ?? "untilNextMatchingStatement",
    onEveryPath: match.onEveryPath === true,
  } as unknown as CustomMatch;
}

function putText(target: Record<string, unknown>, key: string, value: string | undefined): void {
  if (value !== undefined && value.trim() !== "") {
    target[key] = value;
  }
}

function putList(
  target: Record<string, unknown>,
  key: string,
  value: readonly string[] | undefined,
): void {
  const list = nonBlank(value);
  if (list.length > 0) {
    target[key] = list;
  }
}

function nonBlank(value: readonly string[] | undefined): string[] {
  return (value ?? []).map((element) => element.trim()).filter((element) => element !== "");
}

/**
 * The problems that can be decided without the engine: the id format, duplicate ids, and the fields
 * with no default to fall back on.
 */
export function localProblems(defs: readonly CustomRule[]): CustomRuleProblem[] {
  const problems: CustomRuleProblem[] = [];
  const seen = new Set<string>();
  defs.forEach((rule, index) => {
    const id = rule.id ?? "";
    const at = (code: CustomRuleProblemCode): void => {
      problems.push({ code, index, id });
    };
    if (!ID_PATTERN.test(id)) {
      at("idFormat");
    } else if (seen.has(id)) {
      at("idDuplicate");
    }
    seen.add(id);
    if ((rule.name ?? "").trim() === "") at("nameRequired");
    if ((rule.message ?? "").trim() === "") at("messageRequired");
    const match = matchOf(rule);
    if (match.kind === "line" && (match.regex ?? "").trim() === "") at("regexRequired");
    if (match.kind === "statement" && nonBlank(match.verb).length === 0) at("verbRequired");
    if (match.kind === "checked-after") {
      if ((match.after?.verb ?? "").trim() === "") at("afterVerbRequired");
      if (nonBlank(match.checks?.dataItem).length === 0) at("dataItemRequired");
    }
  });
  return problems;
}

/** The engine's own complaints that name this rule, so they can be shown beside its fields. */
export function engineErrorsFor(errors: readonly string[], id: string): string[] {
  return id === "" ? [] : errors.filter((error) => error.includes(id));
}
