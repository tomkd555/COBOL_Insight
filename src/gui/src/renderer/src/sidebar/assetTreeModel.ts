/**
 * 資産ツリーの組み立て(React 非依存の純関数)。走査した相対パスをそのまま階層として並べる。
 * フォルダ名の規約は持たない(engine の走査が内容から種別を逆算するため)。
 */

import type { AssetInventoryItem, SarifFinding } from "../../../shared/engine-api";

/**
 * 種別フィルタの値。表示名ではなく符号で持つ。表示名を状態に持つと、文言を直したとたんに
 * 絞り込みが効かなくなる。
 */
export type AssetTypeCode = "cobol" | "copybook" | "jcl" | "bms" | "other";

/** 種別フィルタの選択値。all はすべての種別を通す。 */
export type AssetTypeFilter = "all" | AssetTypeCode;

/** 種別の表示名。 */
export const ASSET_TYPE_LABELS: Readonly<Record<AssetTypeCode, string>> = {
  cobol: "COBOL",
  copybook: "コピー句",
  jcl: "JCL",
  bms: "BMS",
  other: "その他",
};

/** 種別フィルタの並び。 */
export const ASSET_TYPE_FILTERS: readonly AssetTypeFilter[] = [
  "all",
  "cobol",
  "copybook",
  "jcl",
  "bms",
  "other",
];

/** 種別フィルタの表示名。 */
export function assetTypeFilterLabel(filter: AssetTypeFilter): string {
  return filter === "all" ? "すべて" : ASSET_TYPE_LABELS[filter];
}

/** engine の NODE.type を種別の符号へ対応させる。未登録・未知(DATASET 等)は other。 */
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

/** ツリーの1行。フォルダと資産を同じ並びに載せ、深さで階層を示す。 */
export interface TreeRow {
  readonly kind: "folder" | "file";
  /** フォルダは資産フォルダからの相対パス、資産は AssetInventoryItem.path と同じ。 */
  readonly path: string;
  readonly name: string;
  /** 0 起点の深さ。 */
  readonly depth: number;
  /** フォルダのみ。開いているか。 */
  readonly expanded: boolean;
  /** 資産のみ。 */
  readonly item: AssetInventoryItem | null;
  /** 資産のみ。lint の指摘件数。 */
  readonly findingCount: number;
  /** 資産のみ。 */
  readonly type: AssetTypeCode | null;
}

export interface TreeOptions {
  /** 名前フィルタ(大文字小文字を無視した部分一致)。 */
  readonly search: string;
  readonly type: AssetTypeFilter;
  /** 畳んでいるフォルダの相対パス。既定はすべて開いた状態である。 */
  readonly collapsed: ReadonlySet<string>;
  /** 相対パスごとの指摘件数。供給源は lint と sql-lint の SARIF である。 */
  readonly findingCounts: Readonly<Record<string, number>>;
}

/** lint の指摘を相対パスごとに数える。 */
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

/** フォルダ節点。子フォルダと直下の資産を持つ。 */
interface FolderNode {
  readonly folders: Map<string, FolderNode>;
  readonly files: AssetInventoryItem[];
}

function newFolder(): FolderNode {
  return { folders: new Map(), files: [] };
}

/** 絞り込みを通った資産だけで階層を組む。空になったフォルダは現れない。 */
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
 * 表示する行を上から順に並べる。フォルダを先、資産を後に置き、いずれも名前の昇順とする。
 * 畳んだフォルダの中身は並べない。
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

/** フォルダの開閉を反転した新しい集合を返す。 */
export function toggleCollapsed(collapsed: ReadonlySet<string>, path: string): Set<string> {
  const next = new Set(collapsed);
  if (next.has(path)) {
    next.delete(path);
  } else {
    next.add(path);
  }
  return next;
}
