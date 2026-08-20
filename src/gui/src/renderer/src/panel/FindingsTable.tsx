import { useMemo, useState, type ReactElement } from "react";
import { FilterChip } from "../components/FilterChip";
import { SeverityBadge } from "../components/SeverityBadge";
import { TextInput } from "../components/TextInput";
import { SEVERITY_META, SEVERITY_ORDER, type Severity } from "../components/severity";
import { artifactItems, useProject } from "../state/projectStore";
import { useSettings } from "../state/settingsStore";
import {
  SOURCE_LABELS,
  SOURCE_OPTIONS,
  fileOptions,
  filterFindings,
  initialFindingFilter,
  mergeFindings,
  ruleOptions,
  severityCounts,
  thresholdNote,
  type FindingFilter,
} from "./findingsModel";

export interface FindingsTableProps {
  /** 行を押したときに、その資産の該当行を開く。 */
  onOpen: (path: string, line: number) => void;
}

/**
 * lint と sql-lint の指摘を1つにまとめた表。重大度・ルール・資産・出所で絞り込み、行を押すと
 * その資産の該当行を本文領域で開く。
 */
export function FindingsTable({ onOpen }: FindingsTableProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const [filter, setFilter] = useState<FindingFilter>(initialFindingFilter);

  const merged = useMemo(
    () =>
      mergeFindings(
        artifactItems(project.findings),
        artifactItems(project.sqlAdvice),
        project.saveFindings,
      ),
    [project.findings, project.sqlAdvice, project.saveFindings],
  );
  const counts = useMemo(
    () => severityCounts(merged, project.catalog),
    [merged, project.catalog],
  );
  const effective: FindingFilter = { ...filter, threshold: settings.severityThreshold };
  const rows = useMemo(
    () => filterFindings(merged, project.catalog, effective),
    [merged, project.catalog, effective],
  );
  const note = thresholdNote(settings.severityThreshold);

  const failed =
    project.findings.status === "error"
      ? project.findings.message
      : project.sqlAdvice.status === "error"
        ? project.sqlAdvice.message
        : null;

  function toggleSeverity(severity: Severity): void {
    setFilter((current) => ({
      ...current,
      severity: { ...current.severity, [severity]: !current.severity[severity] },
    }));
  }

  return (
    <div className="ci-findings">
      <div className="ci-findings__toolbar">
        <div className="ci-findings__chips" role="group" aria-label="重大度で絞る">
          {SEVERITY_ORDER.map((severity) => (
            <FilterChip
              key={severity}
              label={SEVERITY_META[severity].label}
              count={counts[severity]}
              symbol={SEVERITY_META[severity].symbol}
              symbolColorVar={SEVERITY_META[severity].colorVar}
              active={filter.severity[severity]}
              onClick={() => toggleSeverity(severity)}
            />
          ))}
        </div>
        <select
          className="ci-select"
          aria-label="ルールで絞る"
          value={filter.rule}
          onChange={(event) => setFilter((current) => ({ ...current, rule: event.target.value }))}
        >
          {ruleOptions(merged, project.catalog).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <select
          className="ci-select"
          aria-label="資産で絞る"
          value={filter.file}
          onChange={(event) => setFilter((current) => ({ ...current, file: event.target.value }))}
        >
          {fileOptions(merged).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <select
          className="ci-select"
          aria-label="出所で絞る"
          value={filter.source}
          onChange={(event) => setFilter((current) => ({ ...current, source: event.target.value }))}
        >
          {SOURCE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <TextInput
          aria-label="内容で絞る"
          placeholder="内容で絞る"
          value={filter.text}
          onChange={(event) => setFilter((current) => ({ ...current, text: event.target.value }))}
        />
        <span className="ci-findings__count">
          {merged.length} 件中 {rows.length} 件
        </span>
      </div>

      {note !== null ? <p className="ci-findings__note">{note}</p> : null}
      {failed !== null ? (
        <div className="ci-banner ci-banner--error" role="alert">
          指摘の検出に失敗しました。{failed}
        </div>
      ) : null}

      <div className="ci-findings__scroll">
        <table className="ci-table">
          <caption className="ci-visually-hidden">検出した指摘の一覧</caption>
          <thead>
            <tr>
              <th scope="col">重大度</th>
              <th scope="col">ルール</th>
              <th scope="col">資産</th>
              <th scope="col">行</th>
              <th scope="col">出所</th>
              <th scope="col">内容</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={row.key}
                className="ci-table__row"
                data-testid={`finding-${row.key}`}
                onClick={() => onOpen(row.finding.file, row.finding.startLine)}
              >
                <td>
                  <SeverityBadge severity={row.severity} />
                </td>
                <td>
                  <button
                    type="button"
                    className="ci-table__link"
                    aria-label={`${row.finding.file} の ${row.finding.startLine} 行目を開く`}
                    onClick={(event) => {
                      event.stopPropagation();
                      onOpen(row.finding.file, row.finding.startLine);
                    }}
                  >
                    {row.finding.ruleId}
                  </button>
                  <span className="ci-table__sub">{row.ruleName}</span>
                </td>
                <td>{row.finding.file}</td>
                <td className="ci-table__num">{row.finding.startLine}</td>
                <td>{SOURCE_LABELS[row.source]}</td>
                <td>{row.finding.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 ? (
          <p className="ci-findings__blank">
            {merged.length > 0
              ? "絞り込みに合う指摘がありません。"
              : project.inputDir === null
                ? "資産フォルダを選ぶと、指摘をここへ並べます。"
                : "指摘はありません。"}
          </p>
        ) : null}
      </div>
    </div>
  );
}
