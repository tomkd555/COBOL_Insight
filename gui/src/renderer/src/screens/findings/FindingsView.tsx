import { useMemo, type ReactElement } from "react";
import type { SarifFinding } from "../../../../shared/engine-api";
import type { Severity } from "../../components/severity";
import type { FindingSort } from "../../state/appState";
import { FindingsToolbar } from "./FindingsToolbar";
import { FindingsTable } from "./FindingsTable";
import {
  fileOptions,
  filterAndSortFindings,
  ruleOptions,
  severityCounts,
  summaryText,
  thresholdNote,
  type FindingFilters,
  type FindingRow,
} from "./findingsModel";

export interface FindingsViewHandlers {
  onToggleSeverity: (severity: Severity) => void;
  onRuleChange: (value: string) => void;
  onFileChange: (value: string) => void;
  onTextChange: (value: string) => void;
  onSortChange: (sort: FindingSort) => void;
}

export interface FindingsViewProps {
  /** この画面のフィルタ前の全指摘(lint または sql-advise の SARIF)。 */
  findings: readonly SarifFinding[];
  filters: FindingFilters;
  handlers: FindingsViewHandlers;
  /** 行の活性化(指摘一覧はソース行へのジャンプ、SQL助言は詳細ペインの選択)。 */
  onActivateRow: (row: FindingRow) => void;
  /** 行を活性化したときに起きることの説明。 */
  rowHint: string;
  /** 表の名前(支援技術へ伝える)。 */
  tableLabel: string;
  /** 選択中の指摘(行の強調に使う)。選択の概念を持たない画面は渡さない。 */
  selectedFinding?: SarifFinding | null;
  onGoReport: () => void;
  /** フィルタ 0 件時のメッセージ。 */
  noHitMessage: string;
}

/**
 * 指摘一覧と SQL助言で共有する一覧 UI。ツールバー(重大度チップ・ルール/ファイル選択・
 * 内容検索・要約・レポート出力)と表(重大度/ファイル/行のソート・行の活性化)を組む。
 * フィルタ状態と各種ハンドラは呼び出し側が AppState から供給する。
 */
export function FindingsView({
  findings,
  filters,
  handlers,
  onActivateRow,
  rowHint,
  tableLabel,
  selectedFinding = null,
  onGoReport,
  noHitMessage,
}: FindingsViewProps): ReactElement {
  const counts = useMemo(() => severityCounts(findings), [findings]);
  const rules = useMemo(() => ruleOptions(findings), [findings]);
  const files = useMemo(() => fileOptions(findings), [findings]);
  const rows = useMemo(() => filterAndSortFindings(findings, filters), [findings, filters]);
  // しきい値で一覧が絞られているときは、全件と表示件数の差の理由を要約へ添える。
  const note = thresholdNote(filters.threshold);
  const counted = summaryText(findings, rows.length, counts);
  const summary = note === null ? counted : `${counted} ― ${note}`;

  return (
    <div className="ci-findings">
      <FindingsToolbar
        counts={counts}
        severity={filters.severity}
        threshold={filters.threshold}
        onToggleSeverity={handlers.onToggleSeverity}
        ruleValue={filters.rule}
        ruleOptions={rules}
        onRuleChange={handlers.onRuleChange}
        fileValue={filters.file}
        fileOptions={files}
        onFileChange={handlers.onFileChange}
        text={filters.text}
        onTextChange={handlers.onTextChange}
        summary={summary}
        onGoReport={onGoReport}
      />
      <FindingsTable
        rows={rows}
        sort={filters.sort}
        onSortChange={handlers.onSortChange}
        onActivateRow={onActivateRow}
        rowHint={rowHint}
        label={tableLabel}
        selectedFinding={selectedFinding}
        noHitMessage={noHitMessage}
      />
    </div>
  );
}
