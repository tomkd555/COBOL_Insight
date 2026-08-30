import { useMemo, useState, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { useRulesState } from "../../state/rulesStore";
import { useRules } from "../../state/useRules";
import { useWorkbenchDispatch, customRulesTab, ruleTab } from "../../state/workbenchStore";
import { filterRules, groupByCategory, overrideOf } from "../../model/rulesView";
import { CUSTOM_SEVERITIES } from "../../model/customRules";
import { severityOf } from "../../model/severity";
import type { RuleCatalogEntry } from "../../../../shared/ipc";
import type { Notify } from "../../state/useShellStartup";

export interface RulesProps {
  notify: Notify;
}

/** The severity as the engine reports it after the configuration was applied. */
function severityBadge(entry: RuleCatalogEntry): ReactElement {
  const severity = severityOf(entry.severity);
  return <span className={`ci-severity ci-severity--${severity}`}>{text.severity[severity]}</span>;
}

/**
 * The rule list: every rule the engine knows, grouped by category, with its state and its severity.
 *
 * A toggle writes the rule configuration file and then asks the engine again, so what the checkbox
 * shows is always the engine's own answer rather than a guess made from the file just written.
 */
export function Rules({ notify }: RulesProps): ReactElement {
  const project = useProject();
  const rules = useRulesState();
  const { setRulesEnabled, setRuleSeverity } = useRules(notify);
  const dispatch = useWorkbenchDispatch();
  const [query, setQuery] = useState("");

  const visible = useMemo(
    () => filterRules(project.rules.entries, query),
    [project.rules.entries, query],
  );
  const groups = useMemo(() => groupByCategory(visible), [visible]);
  const visibleIds = useMemo(() => visible.map((entry) => entry.id), [visible]);

  return (
    <div className="ci-rules">
      <div className="ci-rules__toolbar">
        <input
          type="search"
          className="ci-input"
          placeholder={text.rules.search}
          aria-label={text.rules.searchLabel}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          data-testid="rules-search"
        />
      </div>
      <div className="ci-rules__toolbar">
        <button
          type="button"
          className="ci-button"
          disabled={visibleIds.length === 0}
          onClick={() => setRulesEnabled(visibleIds, true)}
          data-testid="rules-enable-all"
        >
          {text.rules.enableAll}
        </button>
        <button
          type="button"
          className="ci-button"
          disabled={visibleIds.length === 0}
          onClick={() => setRulesEnabled(visibleIds, false)}
          data-testid="rules-disable-all"
        >
          {text.rules.disableAll}
        </button>
        <button
          type="button"
          className="ci-button"
          onClick={() => dispatch({ type: "OPEN_TAB", tab: customRulesTab(text.customRules.title) })}
          data-testid="rules-open-custom"
        >
          {text.rules.openCustom}
        </button>
      </div>

      {project.ruleErrors.length === 0 ? null : (
        <div className="ci-strip ci-strip--error" role="alert" data-testid="rules-errors">
          <p className="ci-strip__title">{text.rules.ruleErrors}</p>
          <ul className="ci-strip__list">
            {project.ruleErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        </div>
      )}

      {project.rules.entries.length === 0 ? (
        <p className="ci-rules__state">{text.rules.empty}</p>
      ) : groups.length === 0 ? (
        <p className="ci-rules__state" data-testid="rules-no-match">
          {text.rules.noMatch}
        </p>
      ) : (
        groups.map((group) => (
          <section className="ci-rules__group" key={group.category}>
            <header className="ci-rules__grouphead">
              <h3 className="ci-rules__groupname">{group.category}</h3>
              <button
                type="button"
                className="ci-button ci-button--quiet"
                title={text.rules.enableGroup}
                aria-label={text.rules.enableGroup}
                onClick={() =>
                  setRulesEnabled(
                    group.rules.map((entry) => entry.id),
                    true,
                  )
                }
              >
                <span className="codicon codicon-check-all" aria-hidden="true" />
              </button>
              <button
                type="button"
                className="ci-button ci-button--quiet"
                title={text.rules.disableGroup}
                aria-label={text.rules.disableGroup}
                onClick={() =>
                  setRulesEnabled(
                    group.rules.map((entry) => entry.id),
                    false,
                  )
                }
              >
                <span className="codicon codicon-close-all" aria-hidden="true" />
              </button>
            </header>
            {group.rules.map((entry) => (
              <div className="ci-rules__row" key={entry.id} data-testid={`rule-${entry.id}`}>
                <input
                  type="checkbox"
                  checked={entry.enabled}
                  aria-label={text.rules.enabledLabel(entry.id)}
                  onChange={(event) => setRulesEnabled([entry.id], event.target.checked)}
                  data-testid={`rule-toggle-${entry.id}`}
                />
                <button
                  type="button"
                  className="ci-rules__name"
                  onClick={() => dispatch({ type: "OPEN_TAB", tab: ruleTab(entry.id) })}
                  data-testid={`rule-open-${entry.id}`}
                >
                  <span className="ci-rules__id">{entry.id}</span>
                  {entry.name}
                </button>
                {severityBadge(entry)}
                {entry.hasFix ? (
                  <span className="ci-badge">{text.rules.hasFix}</span>
                ) : null}
                <span className={`ci-badge ci-badge--${entry.source === "user" ? "copybook" : "other"}`}>
                  {entry.source === "user" ? text.rules.user : text.rules.builtin}
                </span>
                <select
                  className="ci-select"
                  aria-label={text.rules.severityLabel(entry.id)}
                  value={overrideOf(rules.file, entry.id).severity ?? ""}
                  onChange={(event) =>
                    setRuleSeverity(entry.id, event.target.value === "" ? null : event.target.value)
                  }
                  data-testid={`rule-severity-${entry.id}`}
                >
                  <option value="">{text.rules.severityDefault}</option>
                  {CUSTOM_SEVERITIES.map((severity) => (
                    <option key={severity} value={severity}>
                      {text.severity[severityOf(severity)]}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </section>
        ))
      )}
    </div>
  );
}
