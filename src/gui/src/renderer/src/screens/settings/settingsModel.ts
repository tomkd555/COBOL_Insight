/**
 * 設定画面(GUI 専用)の純ロジック。検出ルール(組み込みの R001〜R031・S001〜S006 と、
 * 利用者定義の U から始まるもの)の有効・無効、コピー句探索パスの並び替え、既定の文字コード、
 * 表示する重大度のしきい値を扱う。
 *
 * ルールのメタ情報も、各ルールが検出に効くかどうか(enabled)も engine が返す一覧を単一の正とし、
 * ここでは絞り込み・カテゴリ別のまとめ・設定ファイルの組み立てだけを行う。件数と並びを一覧へ都度
 * 問い合わせるのは、利用者定義ルールの増減で総数が変わるためである。
 */

import { ruleCount, ruleOf, type RuleCatalogIndex, type RuleInfo } from "../../data/ruleCatalog";
import { RULE_CONFIG_VERSION, type RuleConfigFile } from "../../../../shared/engine-api";
import { MANUAL_ENCODING_OPTIONS } from "../../data/encodings";
import type { AnalysisMode } from "../../state/projectStore";

/** 解析エンジンの実行について示す1件。 */
export interface EngineLaunchEntry {
  readonly label: string;
  readonly value: string;
}

/**
 * 解析エンジンの実行のうち、利用者が知る意味のある事実。起動するファイルの場所は配布形態で
 * 決まる内部の話であり、利用者はそれを選べないため並べない。
 */
export const ENGINE_LAUNCH_INFO: readonly EngineLaunchEntry[] = [
  { label: "通信", value: "なし" },
];

/**
 * 既定の文字コードの選択肢(design encDefOpts)。資産エクスプローラーの手動指定と同じ語彙を用いる。
 * 自動判定・推定は engine の判定結果を表す語であり、既定値としては効果を持たないため並べない。
 */
export const ENCODING_OPTIONS: readonly string[] = MANUAL_ENCODING_OPTIONS;

/** ルールの出所の絞り込み。 */
export type RuleSourceFilter = "all" | "builtin" | "user";

/** カテゴリ別のまとめ1件。 */
export interface RuleGroup {
  readonly category: string;
  readonly rows: readonly RuleInfo[];
}

/** 一覧の絞り込み条件。 */
export interface RuleFilter {
  /** ルール ID・名称・カテゴリへ当てる検索語。 */
  readonly search: string;
  readonly source: RuleSourceFilter;
  /** 選んだカテゴリ。空文字はすべてのカテゴリ。 */
  readonly category: string;
}

/** 絞り込みの初期値。 */
export const ALL_RULES: RuleFilter = { search: "", source: "all", category: "" };

/**
 * 絞り込んだうえでカテゴリ別にまとめる。カタログの定義順では同じカテゴリが離れた位置に現れる
 * (例: データフローは R001・R002 と R025)ため、カテゴリ単位で束ねて見出しを1つにする。
 * カテゴリの並びは最初に現れた位置の順、各カテゴリ内は ID の定義順である。
 */
export function buildRuleGroups(catalog: RuleCatalogIndex, filter: RuleFilter): RuleGroup[] {
  const query = filter.search.trim().toLowerCase();
  const byCategory = new Map<string, RuleInfo[]>();
  for (const id of catalog.order) {
    const rule = ruleOf(catalog, id);
    if (query !== "" && !`${rule.id}${rule.name}${rule.category}`.toLowerCase().includes(query)) {
      continue;
    }
    if (filter.source !== "all" && rule.source !== filter.source) {
      continue;
    }
    if (filter.category !== "" && rule.category !== filter.category) {
      continue;
    }
    const rows = byCategory.get(rule.category);
    if (rows === undefined) {
      byCategory.set(rule.category, [rule]);
    } else {
      rows.push(rule);
    }
  }
  return [...byCategory].map(([category, rows]) => ({ category, rows }));
}

/** 絞り込み結果に現れるルール ID。一括操作の対象になる。 */
export function groupedRuleIds(groups: readonly RuleGroup[]): string[] {
  return groups.flatMap((group) => group.rows.map((row) => row.id));
}

/** 絞り込みの選択肢にするカテゴリ。engine が返した並びで、重複を除く。 */
export function ruleCategories(catalog: RuleCatalogIndex): string[] {
  const categories: string[] = [];
  for (const id of catalog.order) {
    const category = ruleOf(catalog, id).category;
    if (!categories.includes(category)) {
      categories.push(category);
    }
  }
  return categories;
}

/** 無効にしてあるルール ID。engine が返した並びで返す。 */
export function disabledRuleIds(catalog: RuleCatalogIndex): string[] {
  return catalog.order.filter((id) => !ruleOf(catalog, id).enabled);
}

/** 有効なルール数。 */
export function enabledRuleCount(catalog: RuleCatalogIndex): number {
  return ruleCount(catalog) - disabledRuleIds(catalog).length;
}

/**
 * 指定した ID を一括で有効・無効にした、engine へ書き渡す設定を組む。engine が返した enabled を
 * 起点にするため、画面が別に無効の写しを持たなくてよい。
 */
export function ruleConfigWith(
  catalog: RuleCatalogIndex,
  ids: readonly string[],
  enabled: boolean,
): RuleConfigFile {
  const disabled = new Set(disabledRuleIds(catalog));
  for (const id of ids) {
    if (enabled) {
      disabled.delete(id);
    } else {
      disabled.add(id);
    }
  }
  return { version: RULE_CONFIG_VERSION, disabledRules: [...disabled].sort() };
}

/** 1件の有効・無効を反転した設定を組む。 */
export function ruleConfigToggling(catalog: RuleCatalogIndex, id: string): RuleConfigFile {
  return ruleConfigWith(catalog, [id], !ruleOf(catalog, id).enabled);
}

/** 一覧見出しの件数表示。 */
export function ruleCountLabel(catalog: RuleCatalogIndex): string {
  return `有効 ${enabledRuleCount(catalog)} / ${ruleCount(catalog)}`;
}

/** 絞り込み結果の件数表示。絞り込んでいないときは null。 */
export function filterCountLabel(
  filter: RuleFilter,
  groups: readonly RuleGroup[],
): string | null {
  if (filter.search.trim() === "" && filter.source === "all" && filter.category === "") {
    return null;
  }
  return `絞り込みに一致: ${groupedRuleIds(groups).length} 件`;
}

/** コピー句探索パスを1つ上・下へ動かした新しい並びを返す。端では動かさない。 */
export function movePath(paths: readonly string[], index: number, delta: number): string[] {
  const target = index + delta;
  if (index < 0 || index >= paths.length || target < 0 || target >= paths.length) {
    return [...paths];
  }
  const next = [...paths];
  const moved = next[index];
  next[index] = next[target];
  next[target] = moved;
  return next;
}

/** コピー句探索パスを1件除いた新しい並びを返す。 */
export function removePath(paths: readonly string[], index: number): string[] {
  if (index < 0 || index >= paths.length) {
    return [...paths];
  }
  return paths.filter((_, position) => position !== index);
}

/** コピー句探索パスの追加結果。既に同じパスがある場合は加えず、理由を返す。 */
export interface AddPathResult {
  readonly paths: string[];
  /** 追加できたか。 */
  readonly added: boolean;
  /** 追加できなかった理由。追加できたときは null。 */
  readonly reason: string | null;
}

/** コピー句探索パスを末尾へ加える。空文字は加えず、重複も加えない。 */
export function addPath(paths: readonly string[], value: string): AddPathResult {
  const trimmed = value.trim();
  if (trimmed === "") {
    return { paths: [...paths], added: false, reason: "追加するフォルダパスを入力してください。" };
  }
  if (paths.includes(trimmed)) {
    return { paths: [...paths], added: false, reason: "同じパスがすでに登録されています。" };
  }
  return { paths: [...paths, trimmed], added: true, reason: null };
}

/** 解析の実行中は設定を変更できない(読み取り専用)。 */
export function isReadOnly(mode: AnalysisMode): boolean {
  return mode === "running";
}
