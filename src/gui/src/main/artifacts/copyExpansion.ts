import type {
  CopyExpansion,
  CopyExpansionData,
  CopyExpansionLine,
  CopyExpansionProgram,
} from "../../shared/ipc";

/**
 * Converts the JSON scan writes through --copy-expansion into the shape the screens read. Unknown
 * structure is handled defensively and missing fields become "", 0 or []. Unreadable text throws,
 * which the caller reports the same way it reports a missing artefact.
 *
 * Each line's text is the engine's preprocessed form: comment lines, the sequence area and the
 * identification area are blank. Blank lines are kept, because dropping them would break the
 * correspondence with the copybook's line numbers.
 */
export function parseCopyExpansion(text: string): CopyExpansionData {
  const doc: unknown = JSON.parse(text);
  return { programs: asArray(prop(doc, "programs")).map(toProgram) };
}

function toProgram(value: unknown): CopyExpansionProgram {
  return {
    path: asString(prop(value, "path")) ?? "",
    programId: asString(prop(value, "programId")) ?? "",
    expansions: asArray(prop(value, "expansions")).map(toExpansion),
  };
}

function toExpansion(value: unknown): CopyExpansion {
  return {
    copyStatementLine: asNumber(prop(value, "copyStatementLine")) ?? 0,
    copybookName: asString(prop(value, "copybookName")) ?? "",
    copybookPath: asString(prop(value, "copybookPath")) ?? "",
    lines: asArray(prop(value, "lines")).map(toLine),
  };
}

function toLine(value: unknown): CopyExpansionLine {
  return {
    copybookLine: asNumber(prop(value, "copybookLine")) ?? 0,
    text: asString(prop(value, "text")) ?? "",
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

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}
