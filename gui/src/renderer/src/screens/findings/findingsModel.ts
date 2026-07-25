/**
 * 指摘一覧・SQL助言で共有する一覧のビューモデル(React 非依存の純関数)。
 * design gvFindings(design:1273-1329)の重大度集計・ルール/ファイル選択肢の導出・
 * 重大度/ルール/ファイル/内容フィルタ・重大度/ファイル/行ソート・要約文を移植する。
 *
 * データ供給源は lint / sql-advise の --sarif を parseSarif で平坦化した SarifFinding[] である。
 * 重大度は SARIF の level ではなく、ルールカタログ(ruleOf)を引いて決める(A2)。
 */

import type { SarifFinding } from "../../../../shared/engine-api";
import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../../components/severity";
import { ruleOf } from "../../data/ruleCatalog";
import { visibleSeverities } from "../settings/settingsModel";
import type { FindingSort } from "../../state/appState";

/** ルール/ファイル選択の「すべて」を表す番兵値。 */
export const ALL = "all";

/** 一覧のフィルタ・ソート状態。いずれも AppState から供給する。 */
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
  /** ソート列。 */
  readonly sort: FindingSort;
}

/** フィルタの初期値(全重大度 ON・しきい値は最下位・ルール/ファイル すべて・検索空・重大度ソート)。 */
export const initialFindingFilters: FindingFilters = {
  severity: { high: true, medium: true, low: true, warning: true },
  threshold: "warning",
  rule: ALL,
  file: ALL,
  text: "",
  sort: "sev",
};

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

/** ファイル選択肢。出現するファイルを昇順に並べ、先頭へ「すべて」を置く。 */
export function fileOptions(findings: readonly SarifFinding[]): FindingOption[] {
  const files = [...new Set(findings.map((f) => f.file))].sort();
  return [
    { value: ALL, label: "ファイル: すべて" },
    ...files.map((file) => ({ value: file, label: file })),
  ];
}

const SORT_INDEX: Record<Severity, number> = { high: 0, medium: 1, low: 2, warning: 3 };

function compareFileLine(a: SarifFinding, b: SarifFinding): number {
  if (a.file !== b.file) return a.file < b.file ? -1 : 1;
  return a.startLine - b.startLine;
}

/**
 * 重大度/ルール/ファイル/内容でフィルタし、指定列でソートした表示行を返す(design gvFindings)。
 * 設定のしきい値が表示できる重大度の範囲を決め、その範囲の中で重大度チップがさらに絞る(論理積)。
 */
export function filterAndSortFindings(
  findings: readonly SarifFinding[],
  filters: FindingFilters,
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
    if (filters.sort === "file") return compareFileLine(a.finding, b.finding);
    if (filters.sort === "line") return a.finding.startLine - b.finding.startLine;
    const bySev = SORT_INDEX[a.severity] - SORT_INDEX[b.severity];
    return bySev !== 0 ? bySev : compareFileLine(a.finding, b.finding);
  });
  return rows;
}

/** 要約文(全件・重大度内訳・表示件数)。design:1327 の書式を踏襲する。 */
export function summaryText(
  findings: readonly SarifFinding[],
  visibleCount: number,
  counts: Record<Severity, number> = severityCounts(findings),
): string {
  return (
    `${findings.length} 件（高 ${counts.high} / 中 ${counts.medium} / 低 ${counts.low} / 警告 ${counts.warning}）` +
    `― 表示 ${visibleCount} 件`
  );
}

/** SEVERITY_ORDER を再輸出し、チップの並び順を利用側で共有する。 */
export { SEVERITY_ORDER };
