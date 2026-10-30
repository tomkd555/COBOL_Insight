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
import { CUSTOM_MATCH_KINDS } from "../../../shared/rulesFile";

/** `U` followed by 1 to 15 of the characters the engine accepts. */
const ID_PATTERN = /^U[0-9A-Za-z_-]{1,15}$/;

/** The severities a custom rule can declare. */
export const CUSTOM_SEVERITIES: readonly string[] = ["HIGH", "MEDIUM", "LOW", "ADVISORY"];

/** The asset kinds a custom rule can examine. */
export const CUSTOM_TARGETS: readonly string[] = ["COBOL", "COPYBOOK", "JCL", "BMS"];

/** The subcommands a custom rule can run under. */
export const CUSTOM_COMMANDS: readonly string[] = ["LINT", "SQL_LINT", "FIX"];

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
   * NOT_AN_ARRAY when the text parsed to something other than a list of definitions.
   */
  readonly error: string | null;
}

/** The `error` of a text that parses as JSON to something other than an array of objects. */
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
  const taken = new Set(existing.map(idOf));
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
 * The definition's detection, or an empty line match where it holds none of a known kind. The form
 * opens only a definition that holds one (formEditable); the local checks read a missing detection
 * as this empty line match.
 */
export function matchOf(rule: CustomRule): CustomMatch {
  return hasKnownMatch(rule) ? rule.match : emptyMatch("line");
}

/** Whether the definition's detection is one of the kinds this build has fields for. */
export function hasKnownMatch(rule: CustomRule): boolean {
  const match: unknown = rule.match;
  if (match === null || typeof match !== "object") {
    return false;
  }
  const kind: unknown = (match as { kind?: unknown }).kind;
  return (CUSTOM_MATCH_KINDS as readonly unknown[]).includes(kind);
}

/** The strings of a list a definition holds; a value of another type reads as an empty list. */
export function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((each): each is string => typeof each === "string") : [];
}

/** The text of a field a definition holds, or the empty text where it holds something else. */
function textOf(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/** The definition's id as text. A definition typed by hand can hold anything where the id belongs. */
export function idOf(rule: CustomRule): string {
  return textOf((rule as { id?: unknown }).id);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Text a one-line field shows as it is: absent, or a string with no line break. */
function optionalText(value: unknown): boolean {
  return value === undefined || (typeof value === "string" && !/[\r\n]/.test(value));
}

function optionalFlag(value: unknown): boolean {
  return value === undefined || typeof value === "boolean";
}

/**
 * Optional text a one-line field shows as it is. Pruning drops it where it is blank, so a definition
 * as read keeps it only where it holds a character.
 */
function optionalProse(value: unknown, asRead: boolean): boolean {
  return optionalText(value) && !(asRead && typeof value === "string" && value.trim() === "");
}

/**
 * A list a list field shows as it is: no item holds a separator of the field, which would split it.
 * Pruning trims the items and drops the blank ones, and drops an optional list left empty, so in a
 * definition as read every item is trimmed and holds a character, and an optional list holds an item.
 */
function optionalList(value: unknown, asRead: boolean, optional = false): boolean {
  return (
    value === undefined ||
    (Array.isArray(value) &&
      !(asRead && optional && value.length === 0) &&
      value.every(
        (each) =>
          typeof each === "string" &&
          !/[,、\r\n]/.test(each) &&
          !(asRead && (each.trim() === "" || each.trim() !== each)),
      ))
  );
}

/**
 * A list check boxes show as it is: every item one of the options, spelled as the option is. Pruning
 * drops the list left empty, and a tick writes the ticked options once each in the order of the
 * options, so in a definition as read the list holds an option and follows that order, each once.
 */
function optionalChoices(value: unknown, options: readonly string[], asRead: boolean): boolean {
  const at = (each: unknown): number => (options as readonly unknown[]).indexOf(each);
  return (
    value === undefined ||
    (Array.isArray(value) &&
      !(asRead && value.length === 0) &&
      value.every((each, index) => at(each) !== -1 && (!asRead || index === 0 || at(each) > at(value[index - 1]))))
  );
}

/**
 * Whether every member the form has a field for holds a value that field can show as it is. A
 * definition typed by hand, or written for a newer engine, can hold a detection of another kind, a
 * value of another type, a severity the list lacks, a target or a command outside the options or
 * spelled otherwise, text with a line break, or a list item holding `,`, `、` or a line break; the
 * form then shows no fields for it, so that an edit there cannot write over what it could not show.
 * A definition the form did not make or change (formEdited) is also left to the JSON pane where an
 * edit would drop or trim what it holds: blank optional text, an empty optional list, or a list item
 * that is blank or carries blanks at either end, and a target or command list that repeats an option
 * or lists the options in another order. The form itself holds those while the user types. A
 * definition with a missing or null detection goes to the JSON pane too, since the first edit in the
 * form would write a line match into it. Such a definition is edited in the JSON pane only. Members
 * the form has no field for are carried across by its edits.
 */
export function formEditable(rule: CustomRule): boolean {
  const source = rule as unknown as Record<string, unknown>;
  const asRead = !fromForm.has(rule);
  if (
    typeof source["id"] !== "string" ||
    !["id", "name", "message", "severity"].every((key) => optionalText(source[key])) ||
    !["category", "summary", "rationale", "remedy"].every((key) => optionalProse(source[key], asRead)) ||
    !optionalChoices(source["targets"], CUSTOM_TARGETS, asRead) ||
    !optionalChoices(source["commands"], CUSTOM_COMMANDS, asRead) ||
    (typeof source["severity"] === "string" &&
      !CUSTOM_SEVERITIES.includes(source["severity"].toUpperCase()))
  ) {
    return false;
  }
  const match = source["match"];
  if (!isRecord(match)) {
    return false;
  }
  switch (match["kind"]) {
    case "line":
      return (
        optionalText(match["regex"]) &&
        optionalFlag(match["ignoreCase"]) &&
        (match["area"] === undefined || match["area"] === "programArea" || match["area"] === "wholeLine") &&
        optionalProse(match["excludeRegex"], asRead)
      );
    case "statement":
      return (
        optionalList(match["verb"], asRead) &&
        optionalList(match["missingClause"], asRead, true) &&
        optionalProse(match["inParagraph"], asRead)
      );
    case "checked-after": {
      const after = match["after"];
      const checks = match["checks"];
      return (
        (after === undefined ||
          (isRecord(after) && optionalText(after["verb"]) && optionalProse(after["textRegex"], asRead))) &&
        (checks === undefined || (isRecord(checks) && optionalList(checks["dataItem"], asRead))) &&
        (match["scope"] === undefined || CUSTOM_SCOPES.includes(match["scope"] as string)) &&
        optionalFlag(match["onEveryPath"])
      );
    }
    default:
      return false;
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

/** The definitions the form has made or changed since they were read. */
const fromForm = new WeakSet<object>();

/** Marks a definition as the form's own work, so that it is written the way the form means it. */
export function formEdited(rule: CustomRule): CustomRule {
  fromForm.add(rule);
  return rule;
}

/**
 * A definition as it is written. One the form made or changed is pruned of what the form left empty;
 * every other one — read from the file, typed into the JSON pane — keeps its members and their
 * values as they stand, so saving one rule leaves the others as they were. The JSON text of every
 * definition is serialised anew.
 */
export function asWritten(rule: CustomRule): CustomRule {
  return fromForm.has(rule) ? pruned(rule) : rule;
}

/** Writes the `custom` array as the text of the raw pane and of the rule file. */
export function serialise(defs: readonly CustomRule[]): string {
  return JSON.stringify(defs.map(asWritten), null, 2);
}

/**
 * A definition the form edited, as the form means it: the optional fields left blank or null are
 * dropped, the texts of lists are trimmed, and a detection of a known kind has its defaults filled
 * in. Every other member is written as it stands: the engine is the validator, and a value coerced
 * here would be a rule rewritten into one that means something else (shared/rulesFile.ts).
 *
 * Writing the empty optionals out would not be harmless: an empty `excludeRegex` matches every line
 * and would silence the rule entirely, and an empty optional string is not always read as "use the
 * default".
 */
export function pruned(rule: CustomRule): CustomRule {
  const source = rule as unknown as Record<string, unknown>;
  const result: Record<string, unknown> = { id: source["id"], name: source["name"] };
  putText(result, "category", source["category"]);
  putText(result, "severity", source["severity"]);
  putList(result, "targets", source["targets"]);
  putList(result, "commands", source["commands"]);
  result["message"] = source["message"];
  putText(result, "summary", source["summary"]);
  putText(result, "rationale", source["rationale"]);
  putText(result, "remedy", source["remedy"]);
  result["match"] = prunedMatch(source["match"]);
  copyOthers(result, source, KNOWN_MEMBERS);
  return result as unknown as CustomRule;
}

/** The members of a definition the form has a field for. */
const KNOWN_MEMBERS: readonly string[] = [
  "id",
  "name",
  "category",
  "severity",
  "targets",
  "commands",
  "message",
  "summary",
  "rationale",
  "remedy",
  "match",
];

/** Carries across every member the caller did not write itself: the author's, or a newer engine's. */
function copyOthers(
  target: Record<string, unknown>,
  source: Record<string, unknown>,
  written: readonly string[],
): void {
  for (const [key, value] of Object.entries(source)) {
    if (!written.includes(key)) {
      // Defined, since assigning to a member named `__proto__` would set the prototype.
      Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true });
    }
  }
}

function prunedMatch(written: unknown): unknown {
  if (written === undefined || written === null) {
    return prunedMatch(emptyMatch("line"));
  }
  if (!isRecord(written)) {
    return written;
  }
  if (written["kind"] === "line") {
    const result: Record<string, unknown> = {
      kind: "line",
      regex: written["regex"] ?? "",
      ignoreCase: written["ignoreCase"] ?? false,
      area: written["area"] ?? "programArea",
    };
    putText(result, "excludeRegex", written["excludeRegex"]);
    copyOthers(result, written, ["kind", "regex", "ignoreCase", "area", "excludeRegex"]);
    return result;
  }
  if (written["kind"] === "statement") {
    const result: Record<string, unknown> = {
      kind: "statement",
      verb: listAsWritten(written["verb"] ?? []),
    };
    putList(result, "missingClause", written["missingClause"]);
    putText(result, "inParagraph", written["inParagraph"]);
    copyOthers(result, written, ["kind", "verb", "missingClause", "inParagraph"]);
    return result;
  }
  if (written["kind"] === "checked-after") {
    const result: Record<string, unknown> = {
      kind: "checked-after",
      after: prunedAfter(written["after"]),
      checks: prunedChecks(written["checks"]),
      scope: written["scope"] ?? "untilNextMatchingStatement",
      onEveryPath: written["onEveryPath"] ?? false,
    };
    copyOthers(result, written, ["kind", "after", "checks", "scope", "onEveryPath"]);
    return result;
  }
  return written;
}

function prunedAfter(written: unknown): unknown {
  if (written === undefined || written === null) {
    return { verb: "" };
  }
  if (!isRecord(written)) {
    return written;
  }
  const result: Record<string, unknown> = { verb: written["verb"] ?? "" };
  putText(result, "textRegex", written["textRegex"]);
  copyOthers(result, written, ["verb", "textRegex"]);
  return result;
}

function prunedChecks(written: unknown): unknown {
  if (written === undefined || written === null) {
    return { dataItem: [] };
  }
  if (!isRecord(written)) {
    return written;
  }
  const result: Record<string, unknown> = { dataItem: listAsWritten(written["dataItem"] ?? []) };
  copyOthers(result, written, ["dataItem"]);
  return result;
}

/**
 * Writes an optional member. Blank text and null are left out; a value of any other type is the
 * author's and is written as it stands, for the engine to judge.
 */
function putText(target: Record<string, unknown>, key: string, value: unknown): void {
  if (typeof value === "string" ? value.trim() !== "" : value !== undefined && value !== null) {
    target[key] = value;
  }
}

/**
 * Writes an optional list. An empty list and null are left out; a value of another type is written
 * as it stands.
 */
function putList(target: Record<string, unknown>, key: string, value: unknown): void {
  const written = listAsWritten(value);
  if (Array.isArray(written) ? written.length > 0 : written !== undefined && written !== null) {
    target[key] = written;
  }
}

/**
 * A list with its texts trimmed and its blank ones gone, an element of another type left as it
 * stands; a value of another type comes back as it stands.
 */
function listAsWritten(value: unknown): unknown {
  if (!Array.isArray(value)) {
    return value;
  }
  return value
    .map((element: unknown) => (typeof element === "string" ? element.trim() : element))
    .filter((element) => element !== "");
}

/**
 * The usable entries of a list the engine requires. The engine reads one text as a list of one, so a
 * definition typed that way has its entry.
 */
function entries(value: unknown): string[] {
  const list = typeof value === "string" ? [value] : stringList(value);
  return list.map((element) => element.trim()).filter((element) => element !== "");
}

/**
 * The problems that can be decided without the engine: the id format, duplicate ids, and the fields
 * with no default to fall back on.
 */
export function localProblems(defs: readonly CustomRule[]): CustomRuleProblem[] {
  const problems: CustomRuleProblem[] = [];
  const seen = new Set<string>();
  defs.forEach((rule, index) => {
    const id = textOf(rule.id);
    const at = (code: CustomRuleProblemCode): void => {
      problems.push({ code, index, id });
    };
    if (!ID_PATTERN.test(id)) {
      at("idFormat");
    } else if (seen.has(id)) {
      at("idDuplicate");
    }
    seen.add(id);
    if (textOf(rule.name).trim() === "") at("nameRequired");
    if (textOf(rule.message).trim() === "") at("messageRequired");
    // A detection of a kind this build has no fields for is the engine's to judge.
    if (!hasKnownMatch(rule) && rule.match !== undefined && rule.match !== null) {
      return;
    }
    const match = matchOf(rule);
    if (match.kind === "line" && textOf(match.regex).trim() === "") at("regexRequired");
    if (match.kind === "statement" && entries(match.verb).length === 0) at("verbRequired");
    if (match.kind === "checked-after") {
      const after: unknown = match.after;
      const checks: unknown = match.checks;
      if (textOf(isRecord(after) ? after["verb"] : "").trim() === "") at("afterVerbRequired");
      if (entries(isRecord(checks) ? checks["dataItem"] : []).length === 0) at("dataItemRequired");
    }
  });
  return problems;
}

/**
 * The engine's own complaints that name this rule, so they can be shown beside its fields. The id
 * counts where it stands as a whole token, between characters an id cannot hold.
 */
export function engineErrorsFor(errors: readonly string[], id: string): string[] {
  if (id === "") {
    return [];
  }
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const named = new RegExp(`(?<![0-9A-Za-z_-])${escaped}(?![0-9A-Za-z_-])`);
  return errors.filter((error) => named.test(error));
}
