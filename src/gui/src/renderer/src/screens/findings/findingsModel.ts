/**
 * 指摘一覧・SQL指摘で共有する一覧のビューモデル(React 非依存の純関数)。重大度の集計・
 * ルール/ファイル選択肢の導出・重大度/ルール/ファイル/内容のフィルタ・重大度/ファイル/行の
 * ソート・要約文を持つ。
 *
 * データ供給源は lint / sql-lint の --sarif を parseSarif で平坦化した SarifFinding[] である。
 * 重大度は SARIF の level ではなく、ルールカタログ(ruleOf)を引いて決める。
 */

import type { SarifFinding } from "../../../../shared/engine-api";
import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../../components/severity";
import { ruleOf } from "../../data/ruleCatalog";
import { visibleSeverities } from "../settings/settingsModel";
import type { FindingSort, FindingSortColumn } from "../../state/appState";

/** ルール/ファイル選択の「すべて」を表す番兵値。 */
export const ALL = "all";

/** 一覧のフィルタ状態。AppState から供給する。ソート状態は表側だけが持つため含まない。 */
export interface FindingFilters {
  /** 重大度チップの ON/OFF。 */
  readonly severity: Record<Severity, boolean>;
  /** 設定の重大度しきい値。これより低い重大度は一覧に出さない。 */
  readonly threshold: Severity;
  /** ルール選択("all" または ルール ID)。 */
  readonly rule: string;
  /** ファイル選択("all" または相対パス)。 */
  readonly file: string;
  /** 内容テキスト検索。 */
  readonly text: string;
}

/** フィルタの初期値(全重大度 ON・しきい値は最下位・ルール/ファイル すべて・検索空)。 */
export const initialFindingFilters: FindingFilters = {
  severity: { high: true, medium: true, low: true, warning: true },
  threshold: "warning",
  rule: ALL,
  file: ALL,
  text: "",
};

/** 表でソート可能な列。AppState の FindingSortColumn(sev/rule/file/line)と同じ。 */
export type SortColumn = FindingSortColumn;

/** 列とソートの向きの組。AppState の FindingSort と同じ形で、指摘一覧・SQL指摘はそれぞれ AppState に持つ。 */
export type SortState = FindingSort;

/** 表の既定のソート状態(重大度列・昇順)。 */
export const initialSortState: SortState = { column: "sev", direction: "asc" };

/** 見出しクリックに応じたソート状態の遷移。同じ列を再度押すと向きが反転し、別の列を押すと昇順から始める。 */
export function nextSortState(current: SortState, column: SortColumn): SortState {
  if (current.column === column) {
    return { column, direction: current.direction === "asc" ? "desc" : "asc" };
  }
  return { column, direction: "asc" };
}

/** しきい値が表示を許す重大度か。偽の重大度はチップも操作できない。 */
export function severityAllowed(severity: Severity, threshold: Severity): boolean {
  return visibleSeverities(threshold).includes(severity);
}

/** しきい値で一覧が絞られている旨の注記。すべての重大度を通すときは null。 */
export function thresholdNote(threshold: Severity): string | null {
  if (visibleSeverities(threshold).length === SEVERITY_ORDER.length) {
    return null;
  }
  return `重大度しきい値「${SEVERITY_META[threshold].label}」以上を表示（設定で変更する）`;
}

/** 表示用に整えた1件の指摘行。 */
export interface FindingRow {
  readonly finding: SarifFinding;
  readonly severity: Severity;
  readonly ruleName: string;
  readonly hasFix: boolean;
}

/** ドロップダウンの選択肢。 */
export interface FindingOption {
  readonly value: string;
  readonly label: string;
}

/** 指摘の重大度(ルールカタログ由来)。 */
export function severityOf(finding: SarifFinding): Severity {
  return ruleOf(finding.ruleId).severity;
}

/** 重大度ごとの件数(フィルタ前の全件を対象にする)。 */
export function severityCounts(findings: readonly SarifFinding[]): Record<Severity, number> {
  const counts: Record<Severity, number> = { high: 0, medium: 0, low: 0, warning: 0 };
  for (const finding of findings) counts[severityOf(finding)] += 1;
  return counts;
}

/** ルール選択肢。出現するルール ID を昇順に並べ、先頭へ「すべて」を置く。 */
export function ruleOptions(findings: readonly SarifFinding[]): FindingOption[] {
  const ids = [...new Set(findings.map((f) => f.ruleId))].sort();
  return [
    { value: ALL, label: "ルール: すべて" },
    ...ids.map((id) => ({ value: id, label: `${id} ${ruleOf(id).name}` })),
  ];
}

/** 資産選択肢。出現する資産を昇順に並べ、先頭へ「すべて」を置く。 */
export function fileOptions(findings: readonly SarifFinding[]): FindingOption[] {
  const files = [...new Set(findings.map((f) => f.file))].sort();
  return [
    { value: ALL, label: "資産: すべて" },
    ...files.map((file) => ({ value: file, label: file })),
  ];
}

const SORT_INDEX: Record<Severity, number> = { high: 0, medium: 1, low: 2, warning: 3 };

function compareFileLine(a: SarifFinding, b: SarifFinding): number {
  if (a.file !== b.file) return a.file < b.file ? -1 : 1;
  return a.startLine - b.startLine;
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

function compareBySeverity(a: FindingRow, b: FindingRow): number {
  const bySev = SORT_INDEX[a.severity] - SORT_INDEX[b.severity];
  return bySev !== 0 ? bySev : compareFileLine(a.finding, b.finding);
}

/** 列ごとの比較(昇順)。重大度は 高→中→低→警告 を昇順とし、同順位はファイル→行で比べる。 */
function compareByColumn(a: FindingRow, b: FindingRow, column: SortColumn): number {
  switch (column) {
    case "file":
      return compareFileLine(a.finding, b.finding);
    case "line":
      return a.finding.startLine - b.finding.startLine;
    case "rule":
      return compareRuleId(a.finding.ruleId, b.finding.ruleId);
    case "sev":
      return compareBySeverity(a, b);
  }
}

/**
 * 重大度/ルール/ファイル/内容でフィルタし、指定列・向きでソートした表示行を返す。
 * 設定のしきい値が表示できる重大度の範囲を決め、その範囲の中で重大度チップがさらに絞る(論理積)。
 */
export function filterAndSortFindings(
  findings: readonly SarifFinding[],
  filters: FindingFilters,
  sort: SortState = initialSortState,
): FindingRow[] {
  const needle = filters.text.toLowerCase();
  const allowed = new Set(visibleSeverities(filters.threshold));
  const rows = findings
    .filter((finding) => {
      const rule = ruleOf(finding.ruleId);
      if (!allowed.has(rule.severity)) return false;
      if (!filters.severity[rule.severity]) return false;
      if (filters.rule !== ALL && finding.ruleId !== filters.rule) return false;
      if (filters.file !== ALL && finding.file !== filters.file) return false;
      if (needle !== "") {
        const haystack = (finding.message + rule.name + finding.file).toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      return true;
    })
    .map<FindingRow>((finding) => {
      const rule = ruleOf(finding.ruleId);
      return { finding, severity: rule.severity, ruleName: rule.name, hasFix: rule.hasFix };
    });

  rows.sort((a, b) => {
    const cmp = compareByColumn(a, b, sort.column);
    return sort.direction === "asc" ? cmp : -cmp;
  });
  return rows;
}

/** 要約文。全件数・重大度の内訳・フィルタ後の表示件数を、この順で1行に並べる。 */
export function summaryText(
  findings: readonly SarifFinding[],
  visibleCount: number,
  counts: Record<Severity, number> = severityCounts(findings),
): string {
  return (
    `${findings.length} 件（高 ${counts.high} / 中 ${counts.medium} / 低 ${counts.low} / 推奨 ${counts.warning}）` +
    `― 表示 ${visibleCount} 件`
  );
}

/** SEVERITY_ORDER を再輸出し、チップの並び順を利用側で共有する。 */
export { SEVERITY_ORDER };
