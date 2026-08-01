import type { ScanDiscovery } from "../../state/appState";

/**
 * scan のサマリ JSON から走査の付帯情報を取り出す。engine が項目を返さない場合(古い engine を
 * 起動した場合など)は「従来構成で取りこぼし無し」として扱い、警告を出さない側へ倒す。
 */
export function readScanDiscovery(summary: Record<string, unknown> | null): ScanDiscovery {
  return {
    mode: summary?.["discoveryMode"] === "recursive" ? "recursive" : "convention",
    truncated: summary?.["truncated"] === true,
    outsideCount: countOf(summary?.["outsideConventionCount"]),
    outsideSamples: stringsOf(summary?.["outsideConventionSamples"]),
  };
}

function countOf(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

function stringsOf(value: unknown): readonly string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}
