import type { ReactElement } from "react";
import { text } from "../../text";
import { useProject } from "../../state/projectStore";
import { severityOf } from "../../model/severity";

export interface RuleDetailProps {
  ruleId: string;
}

/** One labelled block of the description. An empty value renders nothing at all. */
function section(label: string, value: string): ReactElement | null {
  return value === "" ? null : (
    <section className="ci-ruledetail__section">
      <h3 className="ci-ruledetail__label">{label}</h3>
      <p className="ci-ruledetail__prose">{value}</p>
    </section>
  );
}

/** The same, for a code fragment. */
function example(label: string, value: string): ReactElement | null {
  return value === "" ? null : (
    <section className="ci-ruledetail__section">
      <h3 className="ci-ruledetail__label">{label}</h3>
      <pre className="ci-ruledetail__code">{value}</pre>
    </section>
  );
}

/**
 * One rule's description, exactly as the engine wrote it. The GUI authors no rule text of its own:
 * a field the engine left empty is left out rather than filled in with a stand-in sentence.
 */
export function RuleDetail({ ruleId }: RuleDetailProps): ReactElement {
  const project = useProject();
  const entry = project.rules.entries.find((candidate) => candidate.id === ruleId);

  if (entry === undefined) {
    return (
      <p className="ci-ruledetail__state" data-testid="rule-detail-unknown">
        {text.rules.detailUnknown}
      </p>
    );
  }

  const severity = severityOf(entry.severity);
  return (
    <article className="ci-ruledetail" data-testid={`rule-detail-${entry.id}`}>
      <header className="ci-ruledetail__head">
        <h2 className="ci-ruledetail__title">
          <span className="ci-rules__id">{entry.id}</span>
          {entry.name}
        </h2>
        <span className={`ci-severity ci-severity--${severity}`}>{text.severity[severity]}</span>
        <span className="ci-badge">{entry.category}</span>
        <span className={`ci-badge ci-badge--${entry.source === "user" ? "copybook" : "other"}`}>
          {entry.source === "user" ? text.rules.user : text.rules.builtin}
        </span>
        {entry.hasFix ? <span className="ci-badge">{text.rules.hasFix}</span> : null}
      </header>

      {section(text.rules.detailSummary, entry.summary)}
      {section(text.rules.detailRationale, entry.rationale)}
      {section(text.rules.detailDetection, entry.detection)}
      {section(text.rules.detailRemedy, entry.remedy)}
      {example(text.rules.detailBad, entry.badExample)}
      {example(text.rules.detailGood, entry.goodExample)}
      {section(text.rules.detailCommands, entry.commands.join(" / "))}
      {section(text.rules.detailTargets, entry.targets.join(" / "))}
    </article>
  );
}
