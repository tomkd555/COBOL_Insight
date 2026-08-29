/**
 * Building the asset tree (a pure model, independent of React). The relative paths the scan
 * recorded become the hierarchy as they stand; there is no folder-name convention, because the
 * engine infers asset kind from the bytes.
 */

import type { AssetInventoryItem, SarifFinding } from "../../../shared/ipc";

/**
 * The asset-kind filter value. Held as a code rather than a display name: storing the display name
 * would break the filter the moment the wording changed.
 */
export type AssetTypeCode = "cobol" | "copybook" | "jcl" | "bms" | "other";

/** The filter selection; "all" lets every kind through. */
export type AssetTypeFilter = "all" | AssetTypeCode;

/** The order the kind filters are offered in. */
export const ASSET_TYPE_FILTERS: readonly AssetTypeFilter[] = [
  "all",
  "cobol",
  "copybook",
  "jcl",
  "bms",
  "other",
];

/** Maps the engine's NODE.type onto a kind code. Unregistered and unknown types (DATASET) are other. */
export function assetTypeOf(nodeType: string): AssetTypeCode {
  switch (nodeType) {
    case "PROGRAM":
      return "cobol";
    case "JCL":
      return "jcl";
    case "COPYBOOK":
      return "copybook";
    case "BMS":
      return "bms";
    default:
      return "other";
  }
}

/** One row of the tree. Folders and assets share the list; depth carries the hierarchy. */
export interface TreeRow {
  readonly kind: "folder" | "file";
  /** A folder's path relative to the asset folder; an asset's AssetInventoryItem.path. */
  readonly path: string;
  readonly name: string;
  /** Zero-based depth. */
  readonly depth: number;
  /** Folders only: whether it is open. */
  readonly expanded: boolean;
  /** Assets only. */
  readonly item: AssetInventoryItem | null;
  /** Assets only: how many findings name this file. */
  readonly findingCount: number;
  /** Assets only. */
  readonly type: AssetTypeCode | null;
}

export interface TreeOptions {
  /** Name filter, matched case-insensitively as a substring of the path. */
  readonly search: string;
  readonly type: AssetTypeFilter;
  /** Paths of the collapsed folders. Everything is open by default. */
  readonly collapsed: ReadonlySet<string>;
  /** Finding counts by relative path, drawn from the lint and sql-lint SARIF. */
  readonly findingCounts: Readonly<Record<string, number>>;
}

/** Counts findings by relative path across any number of finding groups. */
export function countFindingsByFile(
  ...groups: readonly (readonly SarifFinding[])[]
): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const findings of groups) {
    for (const finding of findings) {
      counts[finding.file] = (counts[finding.file] ?? 0) + 1;
    }
  }
  return counts;
}

/** A folder node holding its child folders and the assets directly inside it. */
interface FolderNode {
  readonly folders: Map<string, FolderNode>;
  readonly files: AssetInventoryItem[];
}

function newFolder(): FolderNode {
  return { folders: new Map(), files: [] };
}

/** Builds the hierarchy from the assets that pass the filters. Emptied folders do not appear. */
function buildTree(items: readonly AssetInventoryItem[], options: TreeOptions): FolderNode {
  const needle = options.search.trim().toLowerCase();
  const root = newFolder();
  for (const item of items) {
    if (options.type !== "all" && assetTypeOf(item.type) !== options.type) continue;
    if (needle !== "" && !item.path.toLowerCase().includes(needle)) continue;
    const segments = item.path.split("/");
    let node = root;
    for (const segment of segments.slice(0, -1)) {
      let child = node.folders.get(segment);
      if (child === undefined) {
        child = newFolder();
        node.folders.set(segment, child);
      }
      node = child;
    }
    node.files.push(item);
  }
  return root;
}

const BY_NAME = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

/**
 * Lists the visible rows top to bottom: folders first, then assets, each in ascending name order.
 * The contents of a collapsed folder are not listed.
 */
export function buildTreeRows(
  items: readonly AssetInventoryItem[],
  options: TreeOptions,
): TreeRow[] {
  const rows: TreeRow[] = [];
  const walk = (node: FolderNode, prefix: string, depth: number): void => {
    for (const name of [...node.folders.keys()].sort(BY_NAME)) {
      const path = prefix === "" ? name : `${prefix}/${name}`;
      const expanded = !options.collapsed.has(path);
      rows.push({
        kind: "folder",
        path,
        name,
        depth,
        expanded,
        item: null,
        findingCount: 0,
        type: null,
      });
      if (expanded) {
        walk(node.folders.get(name) as FolderNode, path, depth + 1);
      }
    }
    for (const item of [...node.files].sort((a, b) => BY_NAME(a.name, b.name))) {
      rows.push({
        kind: "file",
        path: item.path,
        name: item.name,
        depth,
        expanded: false,
        item,
        findingCount: options.findingCounts[item.path] ?? 0,
        type: assetTypeOf(item.type),
      });
    }
  };
  walk(buildTree(items, options), "", 0);
  return rows;
}

/** Returns a new collapsed set with the folder's state flipped. */
export function toggleCollapsed(collapsed: ReadonlySet<string>, path: string): Set<string> {
  const next = new Set(collapsed);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  return next;
}
