import { useMemo, type ReactElement } from "react";
import { text } from "../../i18n/text";
import type { CustomRule } from "../../../../shared/rulesFile";
import {
  NOT_AN_ARRAY,
  emptyCustomRule,
  engineErrorsFor,
  localProblems,
} from "../../model/customRules";
import { isCustomDirty, useRulesDispatch, useRulesState } from "../../state/rulesStore";
import { useRules } from "../../state/useRules";
import { CustomRuleForm } from "./CustomRuleForm";
import type { Notify } from "../../state/useShellStartup";

export interface CustomRulesProps {
  notify: Notify;
}

/**
 * The custom-rule editor: a form and the raw JSON of the `custom` array, over one value.
 *
 * The raw pane is a plain text area rather than a code editor. What it holds is a short JSON
 * fragment, and the round trip with the form is the point of the screen; syntax colouring would add
 * a second editor to keep in step with it for no gain.
 *
 * Saving writes the whole rule configuration file, so the toggles the rules view set are carried
 * across untouched.
 */
export function CustomRules({ notify }: CustomRulesProps): ReactElement {
  const rules = useRulesState();
  const dispatch = useRulesDispatch();
  const { saveCustomRules, validateCustomRules } = useRules(notify);

  const problems = useMemo(() => localProblems(rules.draft), [rules.draft]);
  const dirty = isCustomDirty(rules);
  const engineErrors = rules.validation?.errors ?? [];

  const replace = (index: number, rule: CustomRule): void => {
    dispatch({
      type: "SET_DRAFT",
      draft: rules.draft.map((current, at) => (at === index ? rule : current)),
    });
  };

  return (
    <div className="ci-custom" data-testid="custom-rules">
      <header className="ci-custom__head">
        <h2 className="ci-custom__title">{text.customRules.title}</h2>
        <div className="ci-chips" role="radiogroup" aria-label={text.customRules.paneLabel}>
          {(["form", "raw"] as const).map((pane) => (
            <button
              key={pane}
              type="button"
              role="radio"
              aria-checked={rules.pane === pane}
              className={`ci-chip${rules.pane === pane ? " ci-chip--on" : ""}`}
              onClick={() => dispatch({ type: "SET_PANE", pane })}
              data-testid={`custom-pane-${pane}`}
            >
              {pane === "form" ? text.customRules.paneForm : text.customRules.paneRaw}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="ci-button"
          disabled={rules.rawError !== null}
          onClick={validateCustomRules}
          data-testid="custom-validate"
        >
          {text.customRules.validate}
        </button>
        <button
          type="button"
          className="ci-button ci-button--primary"
          disabled={rules.rawError !== null || !dirty}
          onClick={saveCustomRules}
          data-testid="custom-save"
        >
          {text.customRules.save}
        </button>
        {dirty ? <span className="ci-custom__dirty">{text.customRules.dirty}</span> : null}
      </header>

      {rules.rawError === null ? null : (
        <div className="ci-strip ci-strip--error" role="alert" data-testid="custom-raw-error">
          <p className="ci-strip__title">
            {rules.rawError === NOT_AN_ARRAY
              ? text.customRules.rawNotArray
              : text.customRules.rawInvalid(rules.rawError)}
          </p>
          <p>{text.customRules.rawBlocked}</p>
        </div>
      )}

      {rules.validation === null ? null : (
        <div
          className={`ci-strip ${rules.validation.ok ? "ci-strip--ok" : "ci-strip--error"}`}
          role="status"
          data-testid="custom-validation"
        >
          <p className="ci-strip__title">
            {rules.validation.ok ? text.customRules.validationOk : text.customRules.validationFailed}
          </p>
          <ul className="ci-strip__list">
            {rules.validation.errors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        </div>
      )}

      {rules.pane === "raw" ? (
        <textarea
          className="ci-custom__raw"
          spellCheck={false}
          aria-label={text.customRules.rawLabel}
          value={rules.raw}
          onChange={(event) => dispatch({ type: "SET_RAW", raw: event.target.value })}
          data-testid="custom-raw"
        />
      ) : (
        <div className="ci-custom__forms">
          {rules.draft.length === 0 ? null : (
            rules.draft.map((rule, index) => (
              <CustomRuleForm
                key={index}
                rule={rule}
                problems={problems.filter((problem) => problem.index === index)}
                engineErrors={engineErrorsFor(engineErrors, rule.id ?? "")}
                onChange={(next) => replace(index, next)}
                onRemove={() =>
                  dispatch({
                    type: "SET_DRAFT",
                    draft: rules.draft.filter((_current, at) => at !== index),
                  })
                }
              />
            ))
          )}
          <button
            type="button"
            className="ci-button"
            onClick={() =>
              dispatch({ type: "SET_DRAFT", draft: [...rules.draft, emptyCustomRule(rules.draft)] })
            }
            data-testid="custom-add"
          >
            {text.customRules.add}
          </button>
        </div>
      )}
    </div>
  );
}
