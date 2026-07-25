import type { FixCopybookImpact, FixSummaryInfo } from "./engine-api";

/**
 * fix preview/apply のサマリ JSON を型付き情報へ正規化する純関数。preview は fixedFiles、apply は
 * writtenFiles を対象ファイルとして扱い、copybookFixes(影響範囲)と件数を取り出す。main・renderer の
 * 双方が engine のサマリ JSON を同じ規則で正規化するため、ここへ一本化する。
 */
export function parseFixSummary(summary: Record<string, unknown> | null): FixSummaryInfo {
  const info: FixSummaryInfo = {
    files: asStringArray(prop(summary, "fixedFiles") ?? prop(summary, "writtenFiles")),
    copybookFixes: asArray(prop(summary, "copybookFixes")).map(toCopybookImpact),
    fixCount: asNumber(prop(summary, "fixCount")) ?? 0,
    analysisErrors: asNumber(prop(summary, "analysisErrors")) ?? 0,
  };
  const reparseFailures = asNumber(prop(summary, "reparseFailures"));
  if (reparseFailures !== undefined) {
    info.reparseFailures = reparseFailures;
  }
  return info;
}

function toCopybookImpact(value: unknown): FixCopybookImpact {
  return {
    copybook: asString(prop(value, "copybook")) ?? "",
    importers: asStringArray(prop(value, "importers")),
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
  return asArray(value).filter((v): v is string => typeof v === "string");
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}
