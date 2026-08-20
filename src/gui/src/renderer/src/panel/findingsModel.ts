/**
 * 下部パネルの指摘表のビューモデル(React 非依存の純関数)。lint と sql-lint の SARIF を1つの表へ
 * まとめ、重大度・ルール・資産・出所で絞り込む。
 *
 * 重大度は SARIF の level ではなくルール一覧の索引から引く。level は3段だが、画面の重大度は
 * 4段でルール固有の属性だからである。
 */

import type { SarifFinding } from "../../../shared/engine-api";
import { ruleOf, type RuleCatalogIndex } from "../data/ruleCatalog";
import { visibleSeverities, type Severity } from "../components/severity";

/**
 * 指摘の出所。lint の検出・sql-lint の検出・書き戻し時の再パース検証を1つの表で区別する。
 */
export type FindingSource = "lint" | "sql" | "save";

/** 出所の表示名。 */
export const SOURCE_LABELS: Readonly<Record<FindingSource, string>> = {
  lint: "コード",
  sql: "SQL",
  save: "保存時の検証",
};

/** ルール・資産・出所の選択で「すべて」を表す値。 */
export const ALL = "all";

/** 表へ流し込む前の1件。出所を添えた SARIF の検出結果である。 */
export interface PanelFinding {
  readonly finding: SarifFinding;
  readonly source: FindingSource;
}

/** 表示用に整えた1行。 */
export interface FindingRow extends PanelFinding {
  readonly severity: Severity;
  readonly ruleName: string;
  readonly hasFix: boolean;
  /** 行の識別子。同じ位置・同じルールの重複があっても取り違えない。 */
  readonly key: string;
}

/** 絞り込みの状態。 */
export interface FindingFilter {
  /** 重大度チップの ON/OFF。 */
  readonly severity: Readonly<Record<Severity, boolean>>;
  /** 設定の重大度しきい値。これより低い重大度は表に出さない。 */
  readonly threshold: Severity;
  /** ルール選択("all" または ルール ID)。 */
  readonly rule: string;
  /** 資産選択("all" または相対パス)。 */
  readonly file: string;
  /** 出所選択("all" または lint / sql)。 */
  readonly source: string;
  /** 内容テキスト検索。 */
  readonly text: string;
}

export const initialFindingFilter: FindingFilter = {
  severity: { high: true, medium: true, low: true, warning: true },
  threshold: "warning",
  rule: ALL,
  file: ALL,
  source: ALL,
  text: "",
};

/** lint・sql-lint・保存時の検証の結果を1つの並びへまとめる。 */
export function mergeFindings(
  lint: readonly SarifFinding[],
  sql: readonly SarifFinding[],
  save: readonly SarifFinding[] = [],
): PanelFinding[] {
  return [
    ...lint.map<PanelFinding>((finding) => ({ finding, source: "lint" })),
    ...sql.map<PanelFinding>((finding) => ({ finding, source: "sql" })),
    ...save.map<PanelFinding>((finding) => ({ finding, source: "save" })),
  ];
}

/** 選択肢の1件。 */
export interface FilterOption {
  readonly value: string;
  readonly label: string;
}

/** ルール選択肢。出現するルール ID を昇順に並べ、先頭へ「すべて」を置く。 */
export function ruleOptions(
  rows: readonly PanelFinding[],
  catalog: RuleCatalogIndex,
): FilterOption[] {
  const ids = [...new Set(rows.map((row) => row.finding.ruleId))].sort(compareRuleId);
  return [
    { value: ALL, label: "ルール: すべて" },
    ...ids.map((id) => ({ value: id, label: `${id} ${ruleOf(catalog, id).name}` })),
  ];
}

/** 資産選択肢。出現する資産を昇順に並べ、先頭へ「すべて」を置く。 */
export function fileOptions(rows: readonly PanelFinding[]): FilterOption[] {
  const files = [...new Set(rows.map((row) => row.finding.file))].sort();
  return [
    { value: ALL, label: "資産: すべて" },
    ...files.map((file) => ({ value: file, label: file })),
  ];
}

/** 出所選択肢。 */
export const SOURCE_OPTIONS: readonly FilterOption[] = [
  { value: ALL, label: "出所: すべて" },
  { value: "lint", label: "出所: コード" },
  { value: "sql", label: "出所: SQL" },
  { value: "save", label: "出所: 保存時の検証" },
];

/** 重大度ごとの件数(絞り込み前の全件を対象にする)。 */
export function severityCounts(
  rows: readonly PanelFinding[],
  catalog: RuleCatalogIndex,
): Record<Severity, number> {
  const counts: Record<Severity, number> = { high: 0, medium: 0, low: 0, warning: 0 };
  for (const row of rows) {
    counts[ruleOf(catalog, row.finding.ruleId).severity] += 1;
  }
  return counts;
}

/** ルール ID を接頭辞(英字)と数値へ分けて比べる。"R001".."R031" → "S001".. のように自然に並ぶ。 */
const RULE_ID_PATTERN = /^([A-Za-z]+)(\d+)$/;

function compareRuleId(a: string, b: string): number {
  const pa = RULE_ID_PATTERN.exec(a);
  const pb = RULE_ID_PATTERN.exec(b);
  if (pa === null || pb === null) return a < b ? -1 : a > b ? 1 : 0;
  if (pa[1] !== pb[1]) return pa[1] < pb[1] ? -1 : 1;
  return Number(pa[2]) - Number(pb[2]);
}

const SEVERITY_RANK: Record<Severity, number> = { high: 0, medium: 1, low: 2, warning: 3 };

/** 重大度(重い順)→ 資産名 → 行の順に並べる。 */
function compareRows(a: FindingRow, b: FindingRow): number {
  const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (bySeverity !== 0) return bySeverity;
  if (a.finding.file !== b.finding.file) return a.finding.file < b.finding.file ? -1 : 1;
  return a.finding.startLine - b.finding.startLine;
}

/**
 * 絞り込んだ表示行を、重大度→資産→行の順に並べて返す。設定のしきい値が表示できる重大度の範囲を
 * 決め、その範囲の中で重大度チップがさらに絞る(論理積)。
 */
export function filterFindings(
  rows: readonly PanelFinding[],
  catalog: RuleCatalogIndex,
  filter: FindingFilter,
): FindingRow[] {
  const needle = filter.text.trim().toLowerCase();
  const allowed = new Set(visibleSeverities(filter.threshold));
  const result: FindingRow[] = [];
  for (const [index, row] of rows.entries()) {
    const rule = ruleOf(catalog, row.finding.ruleId);
    if (!allowed.has(rule.severity)) continue;
    if (!filter.severity[rule.severity]) continue;
    if (filter.rule !== ALL && row.finding.ruleId !== filter.rule) continue;
    if (filter.file !== ALL && row.finding.file !== filter.file) continue;
    if (filter.source !== ALL && row.source !== filter.source) continue;
    if (needle !== "") {
      const haystack = (row.finding.message + rule.name + row.finding.file).toLowerCase();
      if (!haystack.includes(needle)) continue;
    }
    result.push({
      ...row,
      severity: rule.severity,
      ruleName: rule.name,
      hasFix: rule.hasFix,
      key: `${row.source}:${index}`,
    });
  }
  result.sort(compareRows);
  return result;
}

/** しきい値で表が絞られている旨の注記。すべての重大度を通すときは null。 */
export function thresholdNote(threshold: Severity): string | null {
  return visibleSeverities(threshold).length === 4
    ? null
    : "重大度しきい値より低い指摘は表に出していません";
}
