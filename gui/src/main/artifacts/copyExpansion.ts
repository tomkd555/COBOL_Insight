import type {
  CopyExpansion,
  CopyExpansionData,
  CopyExpansionLine,
  CopyExpansionProgram,
} from "../../shared/engine-api";

/**
 * scan の --copy-expansion が書く JSON を、画面が要する形へ変換する純関数。未知構造には防御的に
 * 当たり、欠けた項目は空文字・0・空配列で補う。読めない本文は例外とし、成果物が無い場合と同じく
 * 呼び出し側(renderer)が理由として示す。
 *
 * 行の text は engine の前処理を通した後の姿であり、注記行・一連番号欄・識別欄は空白である。
 * 空白の行も並びの一部として保つ(コピー句の行番号との対応が崩れる)。
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
