import { useMemo, useState, type ReactElement } from "react";
import { text } from "../../text";
import { artifactItems, useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import {
  ALL,
  fileNames,
  filterFindings,
  initialFindingFilter,
  mergeFindings,
  ruleIds,
  thresholdHides,
  type FindingFilter,
  type FindingSort,
} from "../../model/findings";
import { ruleOf } from "../../model/ruleIndex";
import { SEVERITIES, type Severity } from "../../model/severity";

export interface ProblemsProps {
  onOpenAsset: (path: string, line: number | null) => void;
  /** The side-bar rendering, which drops the filter row for want of width. */
  compact?: boolean;
}

/**
 * The problems table. Selecting a row opens that asset's tab at the offending line, so a finding is
 * a route into the source rather than a dead end.
 *
 * The five states — empty, loading, results, no match and error — are all reachable, and a failed
 * analysis is never shown as "no findings".
 */
export function Problems({ onOpenAsset, compact = false }: ProblemsProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const [filter, setFilter] = useState<FindingFilter>(initialFindingFilter);

  const merged = useMemo(
    () =>
      mergeFindings(
        artifactItems(project.findings),
        artifactItems(project.sqlFindings),
        project.saveFindings,
      ),
    [project.findings, project.sqlFindings, project.saveFindings],
  );

  const effective = useMemo<FindingFilter>(
    () => ({ ...filter, threshold: settings.severityThreshold }),
    [filter, settings.severityThreshold],
  );

  const rows = useMemo(
    () => filterFindings(merged, project.rules, effective),
    [merged, project.rules, effective],
  );

  const update = (patch: Partial<FindingFilter>): void =>
    setFilter((current) => ({ ...current, ...patch }));

  if (project.mode === "running" && project.findings.status === "none") {
    return <p className="ci-problems__state">{text.problems.loading}</p>;
  }
  if (project.findings.status === "error") {
    return (
      <p className="ci-problems__state ci-problems__state--error" role="alert">
        {text.problems.error}
        <span className="ci-problems__reason">{project.findings.message}</span>
      </p>
    );
  }
  if (project.findings.status === "none" && project.sqlFindings.status === "none") {
    return <p className="ci-problems__state">{text.problems.empty}</p>;
  }

  return (
    <div className={`ci-problems${compact ? " ci-problems--compact" : ""}`}>
      {compact ? null : (
        <div className="ci-problems__filters">
          <div className="ci-chips" role="group" aria-label={text.problems.columnSeverity}>
            {SEVERITIES.map((severity: Severity) => (
              <button
                key={severity}
                type="button"
                className={`ci-chip ci-chip--${severity}${filter.severity[severity] ? " ci-chip--on" : ""}`}
                aria-pressed={filter.severity[severity]}
                onClick={() =>
                  update({
                    severity: { ...filter.severity, [severity]: !filter.severity[severity] },
                  })
                }
                data-testid={`severity-chip-${severity}`}
              >
                {text.severity[severity]}
              </button>
            ))}
          </div>
          <input
            type="search"
            className="ci-input"
            placeholder={text.problems.search}
            aria-label={text.problems.searchLabel}
            value={filter.text}
            onChange={(event) => update({ text: event.target.value })}
            data-testid="problems-search"
          />
          <select
            className="ci-select"
            aria-label={text.problems.columnRule}
            value={filter.rule}
            onChange={(event) => update({ rule: event.target.value })}
            data-testid="problems-rule-filter"
          >
            <option value={ALL}>{text.problems.allRules}</option>
            {ruleIds(merged).map((id) => (
              <option key={id} value={id}>
                {id} {ruleOf(project.rules, id).name}
              </option>
            ))}
          </select>
          <select
            className="ci-select"
            aria-label={text.problems.columnFile}
            value={filter.file}
            onChange={(event) => update({ file: event.target.value })}
            data-testid="problems-file-filter"
          >
            <option value={ALL}>{text.problems.allFiles}</option>
            {fileNames(merged).map((file) => (
              <option key={file} value={file}>
                {file}
              </option>
            ))}
          </select>
          <select
            className="ci-select"
            aria-label={text.problems.sortLabel}
            value={filter.sort}
            onChange={(event) => update({ sort: event.target.value as FindingSort })}
            data-testid="problems-sort"
          >
            <option value="severity">{text.problems.sortSeverity}</option>
            <option value="file">{text.problems.sortFile}</option>
            <option value="rule">{text.problems.sortRule}</option>
          </select>
        </div>
      )}

      {thresholdHides(settings.severityThreshold) ? (
        <p className="ci-problems__note">{text.problems.thresholdNote}</p>
      ) : null}

      {rows.length === 0 ? (
        <p className="ci-problems__state" data-testid="problems-no-match">
          {merged.length === 0 ? text.problems.clean : text.problems.noMatch}
        </p>
      ) : (
        <table className="ci-problems__table">
          <caption className="ci-visually-hidden">{text.problems.title}</caption>
          <thead>
            <tr>
              <th scope="col">{text.problems.columnSeverity}</th>
              <th scope="col">{text.problems.columnRule}</th>
              <th scope="col">{text.problems.columnMessage}</th>
              <th scope="col">{text.problems.columnFile}</th>
              <th scope="col">{text.problems.columnLine}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={row.key}
                className="ci-problems__row"
                tabIndex={0}
                onClick={() => onOpenAsset(row.finding.file, row.finding.startLine)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onOpenAsset(row.finding.file, row.finding.startLine);
                  }
                }}
                data-testid={`finding-${row.key}`}
              >
                <td>
                  <span className={`ci-severity ci-severity--${row.severity}`}>
                    {text.severity[row.severity]}
                  </span>
                </td>
                <td className="ci-problems__rule">
                  {row.finding.ruleId} {row.ruleName}
                </td>
                <td className="ci-problems__message">{row.finding.message}</td>
                <td className="ci-problems__file">{row.finding.file}</td>
                <td className="ci-problems__line">{row.finding.startLine}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
