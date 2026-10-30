import { useEffect, useMemo, useState, type ReactElement, type ReactNode } from "react";
import { text } from "../../i18n/text";
import { artifactItems, useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import {
  graphTab,
  ruleTab,
  settingsTab,
  useWorkbenchDispatch,
} from "../../state/workbenchStore";
import {
  filterFindings,
  hiddenByThreshold,
  initialFindingFilter,
  mergeFindings,
  type FindingFilter,
  type FindingRow,
  type FindingSort,
} from "../../model/findings";
import { SEVERITIES, SEVERITY_ICON, type Severity } from "../../model/severity";

/** The program a finding's file stands for: the file name without its folders and extension. */
function programNameOf(file: string): string {
  const name = file.split("/").pop() ?? file;
  const dot = name.lastIndexOf(".");
  return dot <= 0 ? name : name.slice(0, dot);
}

/**
 * How each sortable column reads when it is the one in force. Severity runs worst first, so its head
 * is descending; the other two run from the top of the alphabet.
 */
const SORT_ORDER: Readonly<Record<FindingSort, "ascending" | "descending">> = {
  severity: "descending",
  rule: "ascending",
  file: "ascending",
};

export interface ProblemsProps {
  onOpenAsset: (path: string, line: number | null) => void;
  /** Opens the fix diff for one asset. */
  onShowFix?: (path: string) => void;
  /** Analyses the whole asset folder. */
  onRunAll?: () => void;
}

interface DetailProps {
  row: FindingRow;
  onOpenAsset: (path: string, line: number | null) => void;
  onShowFix?: (path: string) => void;
}

/**
 * The selected finding, read in full: which rule found it where, the engine's sentence, the lines it
 * is about, why the rule exists and how to fix it. The rule's id and name, the file, the sentence,
 * the labels of the related lines, the rationale and the remedy come from the engine's finding or its
 * rule catalog entry; the severity's word, the action buttons, the line numbers' wording and the two
 * section headings come from text.ts.
 *
 * The actions sit under the head, so they stay in view however long the text beneath them runs.
 */
function Detail({ row, onOpenAsset, onShowFix }: DetailProps): ReactElement {
  const project = useProject();
  const dispatch = useWorkbenchDispatch();
  const entry = project.rules.entries.find(
    (candidate) => candidate.id === row.finding.ruleId,
  );
  const { finding } = row;
  const related = finding.related ?? [];
  return (
    <aside
      className="ci-problems__detail"
      aria-label={text.problems.title}
      data-testid="problem-detail"
    >
      <div className="ci-problems__detail-head">
        <span className={`ci-problems__detail-severity ci-severity--${row.severity}`}>
          <span className={`codicon codicon-${SEVERITY_ICON[row.severity]}`} aria-hidden="true" />
          {text.severity[row.severity]}
        </span>
        <h3 className="ci-problems__detail-title">
          {row.ruleName === "" ? finding.ruleId : `${finding.ruleId} ${row.ruleName}`}
        </h3>
        <span className="ci-problems__detail-file">{finding.file}</span>
      </div>
      <div className="ci-problems__detail-actions">
        {row.hasFix && onShowFix !== undefined ? (
          <button
            type="button"
            className="ci-button"
            onClick={() => onShowFix(finding.file)}
            data-testid="problem-detail-fix"
          >
            {text.problems.showFix}
          </button>
        ) : null}
        <button
          type="button"
          className="ci-button"
          onClick={() =>
            dispatch({ type: "OPEN_TAB", tab: ruleTab(finding.ruleId, row.ruleName) })
          }
        >
          {text.problems.detailRule}
        </button>
        {/* The row's own button is hidden with its column in a narrow table; this one stays. */}
        <button
          type="button"
          className="ci-button"
          onClick={() =>
            dispatch({
              type: "OPEN_TAB",
              tab: graphTab(text.graph.title, programNameOf(finding.file)),
            })
          }
          data-testid="problem-detail-graph"
        >
          {text.command.showGraph}
        </button>
      </div>
      <p className="ci-problems__detail-message">{finding.message}</p>
      {related.length > 0 ? (
        <section className="ci-problems__detail-section">
          {/* The lines carry their own labels; only a screen reader needs the list named. */}
          <ol className="ci-problems__related" aria-label={text.problems.detailRelated}>
            {related.map((place, index) => (
              <li key={index}>
                <button
                  type="button"
                  className="ci-problems__jump"
                  aria-label={text.problems.jumpTo(place.file, place.line)}
                  title={text.problems.jumpTo(place.file, place.line)}
                  onClick={() => onOpenAsset(place.file, place.line)}
                >
                  {text.problems.line(place.line)}
                </button>
                <span className="ci-problems__related-label">
                  {place.label}
                </span>
              </li>
            ))}
          </ol>
        </section>
      ) : null}
      {entry === undefined || entry.rationale === "" ? null : (
        <section className="ci-problems__detail-section">
          <h4 className="ci-problems__detail-label">
            {text.rules.detailRationale}
          </h4>
          <p className="ci-problems__detail-prose">{entry.rationale}</p>
        </section>
      )}
      {entry === undefined || entry.remedy === "" ? null : (
        <section className="ci-problems__detail-section">
          <h4 className="ci-problems__detail-label">
            {text.rules.detailRemedy}
          </h4>
          <p className="ci-problems__detail-prose">{entry.remedy}</p>
        </section>
      )}
    </aside>
  );
}

/**
 * The problems table. Selecting a row opens that asset's tab at the offending line, so a finding is
 * a route into the source.
 *
 * The five states — empty, loading, results, no match and error — are all reachable, and a failed
 * analysis is never shown as "no findings".
 */
export function Problems({ onOpenAsset, onShowFix, onRunAll }: ProblemsProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const dispatch = useWorkbenchDispatch();
  const [filter, setFilter] = useState<FindingFilter>(initialFindingFilter);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

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

  const hidden = useMemo(
    () => hiddenByThreshold(merged, project.rules, settings.severityThreshold),
    [merged, project.rules, settings.severityThreshold],
  );

  const selected = rows.find((row) => row.key === selectedKey) ?? null;
  // A selection that filtering or a re-run removed is dropped, so no stale selection is shown.
  useEffect(() => {
    if (selectedKey !== null && selected === null) setSelectedKey(null);
  }, [selectedKey, selected]);

  const update = (patch: Partial<FindingFilter>): void =>
    setFilter((current) => ({ ...current, ...patch }));

  const choose = (row: FindingRow): void => {
    setSelectedKey(row.key);
    onOpenAsset(row.finding.file, row.finding.startLine);
  };

  /** A column head that sets the order. Only the head in force carries aria-sort and its marker. */
  const sortHead = (sort: FindingSort, label: ReactNode): ReactElement => (
    <th scope="col" aria-sort={filter.sort === sort ? SORT_ORDER[sort] : undefined}>
      <button
        type="button"
        className="ci-problems__sort"
        onClick={() => update({ sort })}
        data-testid={`problems-sort-${sort}`}
      >
        {label}
      </button>
    </th>
  );

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
  if (
    project.findings.status === "none" &&
    project.sqlFindings.status === "none"
  ) {
    // The side bar is where a missing folder is said; this panel speaks for the analysis alone.
    return <p className="ci-problems__state">{text.empty.notAnalysed}</p>;
  }

  return (
    <div className="ci-problems">
      <div className="ci-problems__filters">
        <div className="ci-problems__filter">
          <input
            type="search"
            placeholder={text.problems.search}
            aria-label={text.problems.searchLabel}
            value={filter.text}
            onChange={(event) => update({ text: event.target.value })}
            data-testid="problems-search"
          />
          <div
            className="ci-chips"
            role="group"
            aria-label={text.problems.columnSeverity}
          >
            {SEVERITIES.map((severity: Severity) => (
              <button
                key={severity}
                type="button"
                className={`ci-chip ci-chip--${severity}${filter.severity[severity] ? " ci-chip--on" : ""}`}
                aria-pressed={filter.severity[severity]}
                onClick={() =>
                  update({
                    severity: {
                      ...filter.severity,
                      [severity]: !filter.severity[severity],
                    },
                  })
                }
                data-testid={`severity-chip-${severity}`}
              >
                {/* The rows show the glyph alone, so the chip is where the glyph meets its word. */}
                <span className={`codicon codicon-${SEVERITY_ICON[severity]}`} aria-hidden="true" />
                {text.severity[severity]}
              </button>
            ))}
          </div>
        </div>
      </div>

      {project.rulesChangedAfterRun ? (
        <p className="ci-problems__note ci-problems__note--stale">
          <span>{text.problems.staleRules}</span>
          {/* Only a whole-folder run clears the stale mark (state/projectStore.tsx). */}
          <button
            type="button"
            className="ci-button"
            onClick={onRunAll}
            disabled={project.mode === "running"}
            data-testid="problems-stale-run"
          >
            {text.command.runAll}
          </button>
        </p>
      ) : null}

      {hidden > 0 ? (
        <p className="ci-problems__note">
          <span>{text.problems.hidden(hidden)}</span>
          <button
            type="button"
            className="ci-button ci-button--quiet"
            onClick={() => dispatch({ type: "OPEN_TAB", tab: settingsTab(text.settings.title) })}
            data-testid="problems-hidden-settings"
          >
            {text.command.showSettings}
          </button>
        </p>
      ) : null}

      {rows.length === 0 ? (
        <p className="ci-problems__state" data-testid="problems-no-match">
          {merged.length === 0 ? text.problems.clean : text.problems.noMatch}
        </p>
      ) : (
        <div className="ci-problems__body">
          <div className="ci-problems__list">
            <table className="ci-problems__table">
              <caption className="ci-visually-hidden">
                {text.problems.title}
              </caption>
              <thead>
                <tr>
                  {sortHead("severity", text.problems.columnSeverity)}
                  {sortHead("rule", text.problems.columnRule)}
                  <th scope="col">{text.problems.columnMessage}</th>
                  {sortHead("file", text.problems.columnFile)}
                  <th scope="col">{text.problems.columnLine}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.key}
                    className={`ci-problems__row${row.key === selectedKey ? " ci-problems__row--selected" : ""}`}
                    tabIndex={0}
                    aria-selected={row.key === selectedKey}
                    onClick={() => choose(row)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        choose(row);
                      }
                    }}
                    data-testid={`finding-${row.key}`}
                  >
                    <td>
                      <span
                        className={`ci-severity ci-severity--${row.severity}`}
                        title={text.severity[row.severity]}
                      >
                        <span
                          className={`codicon codicon-${SEVERITY_ICON[row.severity]}`}
                          aria-hidden="true"
                        />
                        <span className="ci-visually-hidden">
                          {text.severity[row.severity]}
                        </span>
                      </span>
                    </td>
                    <td className="ci-problems__rule" title={`${row.finding.ruleId} ${row.ruleName}`}>
                      {row.finding.ruleId}
                    </td>
                    <td className="ci-problems__message" title={row.finding.message}>
                      {row.finding.message}
                    </td>
                    <td className="ci-problems__file">
                      {/* The path is cut from its head (styles/lists.css), so the file name stays. */}
                      <span className="ci-problems__file-path" title={row.finding.file}>
                        <bdi>{row.finding.file}</bdi>
                      </span>
                      <button
                        type="button"
                        className="ci-problems__graph"
                        aria-label={text.problems.openInGraph(row.finding.file)}
                        title={text.problems.openInGraph(row.finding.file)}
                        onClick={(event) => {
                          event.stopPropagation();
                          dispatch({
                            type: "OPEN_TAB",
                            tab: graphTab(
                              text.graph.title,
                              programNameOf(row.finding.file),
                            ),
                          });
                        }}
                        data-testid={`finding-graph-${row.key}`}
                      >
                        <span
                          className="codicon codicon-type-hierarchy"
                          aria-hidden="true"
                        />
                      </button>
                    </td>
                    <td className="ci-problems__line">
                      {row.finding.startLine}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {/* Until a row is chosen the list has the panel's whole width. */}
          {selected === null ? null : (
            <Detail
              row={selected}
              onOpenAsset={onOpenAsset}
              onShowFix={onShowFix}
            />
          )}
        </div>
      )}
    </div>
  );
}
