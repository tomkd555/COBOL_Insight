import type { DiscoveredAssetKind, KindMismatch, ScanDiscovery } from "../../state/appState";

const DISCOVERED_ASSET_KINDS: readonly DiscoveredAssetKind[] = ["COBOL", "COPYBOOK", "JCL", "BMS"];

function isDiscoveredAssetKind(value: unknown): value is DiscoveredAssetKind {
  return typeof value === "string" && (DISCOVERED_ASSET_KINDS as readonly string[]).includes(value);
}

/**
 * scan のサマリ JSON から走査の付帯情報を取り出す。engine が項目を返さない場合(古い engine を
 * 起動した場合など)は「取りこぼし無し」として扱い、警告を出さない側へ倒す。型の合わない値は
 * 個別に読み飛ばし、他の正しい値まで握り潰さない。
 */
export function readScanDiscovery(summary: Record<string, unknown> | null): ScanDiscovery {
  return {
    undecided: stringsOf(summary?.["undecided"]),
    mismatches: mismatchesOf(summary?.["mismatches"]),
    truncated: summary?.["truncated"] === true,
    unreadable: stringsOf(summary?.["unreadable"]),
  };
}

function stringsOf(value: unknown): readonly string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function mismatchesOf(value: unknown): readonly KindMismatch[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const result: KindMismatch[] = [];
  for (const entry of value) {
    if (entry === null || typeof entry !== "object") {
      continue;
    }
    const record = entry as Record<string, unknown>;
    const path = record["path"];
    const byExtension = record["byExtension"];
    const byContent = record["byContent"];
    if (typeof path === "string" && isDiscoveredAssetKind(byExtension) && isDiscoveredAssetKind(byContent)) {
      result.push({ path, byExtension, byContent });
    }
  }
  return result;
}
