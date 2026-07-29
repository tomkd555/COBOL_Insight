/**
 * 設定画面(GUI 専用)の純ロジック。検出ルール 37 件(R001〜R031・S001〜S006)の有効・無効、
 * コピー句探索パスの並び替え、既定の文字コード、表示する重大度のしきい値を扱う。
 *
 * ルールのメタ情報は data/ruleCatalog を単一の正とし、ここでは絞り込みとカテゴリ別のまとめだけを行う。
 * 無効化したルールは engine の `--disable-rule` へ渡す ID の集合として保つ。
 */

import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../../components/severity";
import { RULE_CATALOG, type RuleInfo } from "../../data/ruleCatalog";
import { MANUAL_ENCODING_OPTIONS } from "../explorer/assetView";
import type { ScreenMode } from "../../state/appState";

/** ルール ID の並び。カタログの定義順(R001→R031→S001→S006)をそのまま用いる。 */
export const RULE_IDS: readonly string[] = Object.keys(RULE_CATALOG);

/** カタログに載るルールの総数。 */
export const RULE_TOTAL = RULE_IDS.length;

/** engine の起動対象を示す1件。 */
export interface EngineLaunchEntry {
  readonly label: string;
  readonly value: string;
}

/**
 * engine の起動対象。main の resolveEngineLaunch が配布形態から決める規則を、利用者へ示す文言に
 * 写したものである。renderer は main のパス解決結果を受け取る経路を持たないため、規則を示す。
 */
export const ENGINE_LAUNCH_INFO: readonly EngineLaunchEntry[] = [
  { label: "配布時の起動対象", value: "resources\\engine\\COBOLInsight.exe（内蔵 JRE 同梱）" },
  { label: "開発時の起動対象", value: "JAVA_HOME の java（engine/cli の installDist を classpath に指定）" },
  { label: "通信", value: "なし（解析エンジンの出力とファイルだけで受け渡す）" },
];

/**
 * 既定の文字コードの選択肢(design encDefOpts)。資産エクスプローラーの手動指定と同じ語彙を用いる。
 * 自動判定・推定は engine の判定結果を表す語であり、既定値としては効果を持たないため並べない。
 */
export const ENCODING_OPTIONS: readonly string[] = MANUAL_ENCODING_OPTIONS;

/** 一覧の1行。ルールのメタ情報に、現在の有効・無効を添える。 */
export interface RuleRow extends RuleInfo {
  /** 無効化されているか。 */
  readonly disabled: boolean;
}

/** カテゴリ別のまとめ1件。 */
export interface RuleGroup {
  readonly category: string;
  readonly rows: readonly RuleRow[];
}

/**
 * 検索語をルール ID・名称・カテゴリへ当てて絞り込み、カテゴリ別にまとめる。カタログの定義順では
 * 同じカテゴリが離れた位置に現れる(例: データフローは R001・R002 と R025)ため、カテゴリ単位で
 * 束ねて見出しを1つにする。カテゴリの並びは最初に現れた位置の順、各カテゴリ内は ID の定義順である。
 */
export function buildRuleGroups(
  search: string,
  disabled: Readonly<Record<string, boolean>>,
): RuleGroup[] {
  const query = search.trim().toLowerCase();
  const byCategory = new Map<string, RuleRow[]>();
  for (const id of RULE_IDS) {
    const rule = RULE_CATALOG[id];
    if (query !== "" && !`${rule.id}${rule.name}${rule.category}`.toLowerCase().includes(query)) {
      continue;
    }
    const rows = byCategory.get(rule.category);
    const row: RuleRow = { ...rule, disabled: disabled[id] === true };
    if (rows === undefined) {
      byCategory.set(rule.category, [row]);
    } else {
      rows.push(row);
    }
  }
  return [...byCategory].map(([category, rows]) => ({ category, rows }));
}

/** 絞り込み結果に現れるルール ID。全選択・全解除の対象になる。 */
export function groupedRuleIds(groups: readonly RuleGroup[]): string[] {
  return groups.flatMap((group) => group.rows.map((row) => row.id));
}

/** 有効なルール数。カタログにない ID は数に含めない。 */
export function enabledRuleCount(disabled: Readonly<Record<string, boolean>>): number {
  return RULE_TOTAL - disabledRuleIds(disabled).length;
}

/** 無効化したルール ID。カタログの定義順で返し、engine の `--disable-rule` へ渡す。 */
export function disabledRuleIds(disabled: Readonly<Record<string, boolean>>): string[] {
  return RULE_IDS.filter((id) => disabled[id] === true);
}

/** 1件の有効・無効を反転した新しい集合を返す。 */
export function toggleRule(
  disabled: Readonly<Record<string, boolean>>,
  id: string,
): Record<string, boolean> {
  const next: Record<string, boolean> = { ...disabled };
  if (next[id] === true) {
    delete next[id];
  } else {
    next[id] = true;
  }
  return next;
}

/** 指定した ID 群を一括で有効・無効にした新しい集合を返す。 */
export function setRulesEnabled(
  disabled: Readonly<Record<string, boolean>>,
  ids: readonly string[],
  enabled: boolean,
): Record<string, boolean> {
  const next: Record<string, boolean> = { ...disabled };
  for (const id of ids) {
    if (enabled) {
      delete next[id];
    } else {
      next[id] = true;
    }
  }
  return next;
}

/** 一覧見出しの件数表示。 */
export function ruleCountLabel(disabled: Readonly<Record<string, boolean>>): string {
  return `有効 ${enabledRuleCount(disabled)} / ${RULE_TOTAL}`;
}

/** 絞り込み結果の件数表示。検索語が無いときは null。 */
export function filterCountLabel(search: string, groups: readonly RuleGroup[]): string | null {
  if (search.trim() === "") {
    return null;
  }
  return `検索に一致: ${groupedRuleIds(groups).length} 件`;
}

/** しきい値以上の重大度(表示対象)。 */
export function visibleSeverities(threshold: Severity): Severity[] {
  return SEVERITY_ORDER.slice(0, SEVERITY_ORDER.indexOf(threshold) + 1);
}

/** しきい値の説明文(design sevThNote)。 */
export function severityThresholdNote(threshold: Severity): string {
  const labels = visibleSeverities(threshold).map((severity) => SEVERITY_META[severity].label);
  const all = labels.length === SEVERITY_ORDER.length ? " ― すべて表示" : "";
  return `現在の設定: 「${SEVERITY_META[threshold].label}」以上を表示（${labels.join("・")} が対象${all}）`;
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
export function isReadOnly(mode: ScreenMode): boolean {
  return mode === "running";
}
