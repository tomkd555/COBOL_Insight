import { useEffect, useState, type ReactElement, type ReactNode } from "react";
import { text } from "../../i18n/text";
import type {
  CheckedAfterMatch,
  CustomMatch,
  CustomRule,
  LineMatch,
  StatementMatch,
} from "../../../../shared/rulesFile";
import { CUSTOM_MATCH_KINDS } from "../../../../shared/rulesFile";
import {
  CUSTOM_COMMANDS,
  CUSTOM_SCOPES,
  CUSTOM_SEVERITIES,
  CUSTOM_TARGETS,
  emptyMatch,
  matchOf,
  type CustomRuleProblem,
  type CustomRuleProblemCode,
} from "../../model/customRules";
import { severityOf } from "../../model/severity";

/** Items of a list field are separated by a comma, a Japanese comma or a line break. */
function parseList(raw: string): string[] {
  return raw
    .split(/[,、\n]/)
    .map((item) => item.trim())
    .filter((item) => item !== "");
}

function formatList(items: readonly string[] | undefined): string {
  return (items ?? []).join(", ");
}

export interface CustomRuleFormProps {
  rule: CustomRule;
  /** What is wrong with this rule, as the local checks found it. */
  problems: readonly CustomRuleProblem[];
  /** The engine's own complaints that name this rule. */
  engineErrors: readonly string[];
  onChange: (rule: CustomRule) => void;
  onRemove: () => void;
}

/**
 * One field per member of a custom rule.
 *
 * The regular expressions are never compiled here: the engine reads Java syntax, which is not this
 * runtime's syntax, so 「検証する」 is what settles whether a pattern is usable.
 */
export function CustomRuleForm({
  rule,
  problems,
  engineErrors,
  onChange,
  onRemove,
}: CustomRuleFormProps): ReactElement {
  const match = matchOf(rule);
  const set = (patch: Partial<CustomRule>): void => onChange({ ...rule, ...patch });
  const setMatch = (next: CustomMatch): void => onChange({ ...rule, match: next });
  const has = (code: CustomRuleProblemCode): boolean =>
    problems.some((problem) => problem.code === code);
  const problemNote = (code: CustomRuleProblemCode): ReactElement | null =>
    has(code) ? (
      <p className="ci-form__error" role="alert">
        {text.customRules.problem[code]}
      </p>
    ) : null;

  return (
    <fieldset className="ci-form" data-testid={`custom-rule-${rule.id}`}>
      <legend className="ci-form__legend">{rule.id === "" ? text.customRules.fieldId : rule.id}</legend>

      <Field label={text.customRules.fieldId}>
        <input
          className="ci-input"
          value={rule.id}
          onChange={(event) => set({ id: event.target.value })}
          data-testid={`custom-id-${rule.id}`}
        />
        {problemNote("idFormat")}
        {problemNote("idDuplicate")}
      </Field>

      <Field label={text.customRules.fieldName}>
        <input
          className="ci-input"
          value={rule.name ?? ""}
          onChange={(event) => set({ name: event.target.value })}
        />
        {problemNote("nameRequired")}
      </Field>

      <Field label={text.customRules.fieldCategory}>
        <input
          className="ci-input"
          value={rule.category ?? ""}
          onChange={(event) => set({ category: event.target.value })}
        />
      </Field>

      <Field label={text.customRules.fieldSummary}>
        <input
          className="ci-input"
          value={rule.summary ?? ""}
          onChange={(event) => set({ summary: event.target.value })}
        />
      </Field>

      <Field label={text.customRules.fieldSeverity}>
        <select
          className="ci-select"
          value={rule.severity ?? "MEDIUM"}
          onChange={(event) => set({ severity: event.target.value })}
        >
          {CUSTOM_SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {text.severity[severityOf(severity)]}
            </option>
          ))}
        </select>
      </Field>

      <Field label={text.customRules.fieldCommands}>
        <CheckList
          options={CUSTOM_COMMANDS}
          selected={rule.commands ?? []}
          onChange={(commands) => set({ commands })}
        />
      </Field>

      <Field label={text.customRules.fieldTargets}>
        <CheckList
          options={CUSTOM_TARGETS}
          selected={rule.targets ?? []}
          onChange={(targets) => set({ targets })}
        />
      </Field>

      <Field label={text.customRules.fieldMessage} note={text.customRules.messageHint}>
        <input
          className="ci-input"
          value={rule.message ?? ""}
          onChange={(event) => set({ message: event.target.value })}
        />
        {problemNote("messageRequired")}
      </Field>

      <Field label={text.customRules.fieldMatchKind}>
        <select
          className="ci-select"
          value={match.kind}
          onChange={(event) => setMatch(emptyMatch(event.target.value as CustomMatch["kind"]))}
          data-testid={`custom-kind-${rule.id}`}
        >
          {CUSTOM_MATCH_KINDS.map((kind) => (
            <option key={kind} value={kind}>
              {text.customRules.matchKind[kind]}
            </option>
          ))}
        </select>
      </Field>

      {match.kind === "line" ? (
        <LineFields match={match} onChange={setMatch} problemNote={problemNote} />
      ) : match.kind === "statement" ? (
        <StatementFields match={match} onChange={setMatch} problemNote={problemNote} />
      ) : (
        <CheckedAfterFields match={match} onChange={setMatch} problemNote={problemNote} />
      )}

      <Field label={text.customRules.fieldRationale}>
        <input
          className="ci-input"
          value={rule.rationale ?? ""}
          onChange={(event) => set({ rationale: event.target.value })}
        />
      </Field>

      <Field label={text.customRules.fieldRemedy}>
        <input
          className="ci-input"
          value={rule.remedy ?? ""}
          onChange={(event) => set({ remedy: event.target.value })}
        />
      </Field>

      {engineErrors.length === 0 ? null : (
        <div className="ci-strip ci-strip--error" role="alert">
          <ul className="ci-strip__list">
            {engineErrors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        </div>
      )}

      <button
        type="button"
        className="ci-button ci-button--danger"
        onClick={onRemove}
        data-testid={`custom-remove-${rule.id}`}
      >
        {text.customRules.remove}
      </button>
    </fieldset>
  );
}

type ProblemNote = (code: CustomRuleProblemCode) => ReactElement | null;

function LineFields({
  match,
  onChange,
  problemNote,
}: {
  match: LineMatch;
  onChange: (match: CustomMatch) => void;
  problemNote: ProblemNote;
}): ReactElement {
  return (
    <>
      <Field label={text.customRules.fieldRegex}>
        <input
          className="ci-input ci-input--code"
          value={match.regex ?? ""}
          onChange={(event) => onChange({ ...match, regex: event.target.value })}
        />
        {problemNote("regexRequired")}
      </Field>
      <Field label={text.customRules.fieldArea}>
        <select
          className="ci-select"
          value={match.area ?? "programArea"}
          onChange={(event) =>
            onChange({ ...match, area: event.target.value as LineMatch["area"] })
          }
        >
          <option value="programArea">{text.customRules.area.programArea}</option>
          <option value="wholeLine">{text.customRules.area.wholeLine}</option>
        </select>
      </Field>
      <Field label={text.customRules.fieldExcludeRegex}>
        <input
          className="ci-input ci-input--code"
          value={match.excludeRegex ?? ""}
          onChange={(event) => onChange({ ...match, excludeRegex: event.target.value })}
        />
      </Field>
      <label className="ci-form__check">
        <input
          type="checkbox"
          checked={match.ignoreCase === true}
          onChange={(event) => onChange({ ...match, ignoreCase: event.target.checked })}
        />
        {text.customRules.fieldIgnoreCase}
      </label>
    </>
  );
}

function StatementFields({
  match,
  onChange,
  problemNote,
}: {
  match: StatementMatch;
  onChange: (match: CustomMatch) => void;
  problemNote: ProblemNote;
}): ReactElement {
  return (
    <>
      <Field label={text.customRules.fieldVerb} note={text.customRules.listHint}>
        <ListField value={match.verb} onChange={(verb) => onChange({ ...match, verb })} />
        {problemNote("verbRequired")}
      </Field>
      <Field label={text.customRules.fieldMissingClause} note={text.customRules.listHint}>
        <ListField
          value={match.missingClause ?? []}
          onChange={(missingClause) => onChange({ ...match, missingClause })}
        />
      </Field>
      <Field label={text.customRules.fieldInParagraph}>
        <input
          className="ci-input ci-input--code"
          value={match.inParagraph ?? ""}
          onChange={(event) => onChange({ ...match, inParagraph: event.target.value })}
        />
      </Field>
    </>
  );
}

function CheckedAfterFields({
  match,
  onChange,
  problemNote,
}: {
  match: CheckedAfterMatch;
  onChange: (match: CustomMatch) => void;
  problemNote: ProblemNote;
}): ReactElement {
  const after = match.after ?? { verb: "" };
  return (
    <>
      <Field label={text.customRules.fieldAfterVerb}>
        <input
          className="ci-input"
          value={after.verb ?? ""}
          onChange={(event) => onChange({ ...match, after: { ...after, verb: event.target.value } })}
        />
        {problemNote("afterVerbRequired")}
      </Field>
      <Field label={text.customRules.fieldAfterTextRegex}>
        <input
          className="ci-input ci-input--code"
          value={after.textRegex ?? ""}
          onChange={(event) =>
            onChange({ ...match, after: { ...after, textRegex: event.target.value } })
          }
        />
      </Field>
      <Field label={text.customRules.fieldDataItem} note={text.customRules.listHint}>
        <ListField
          value={match.checks?.dataItem ?? []}
          onChange={(dataItem) => onChange({ ...match, checks: { dataItem } })}
        />
        {problemNote("dataItemRequired")}
      </Field>
      <Field label={text.customRules.fieldScope}>
        <select
          className="ci-select"
          value={match.scope ?? "untilNextMatchingStatement"}
          onChange={(event) =>
            onChange({ ...match, scope: event.target.value as CheckedAfterMatch["scope"] })
          }
        >
          {CUSTOM_SCOPES.map((scope) => (
            <option key={scope} value={scope}>
              {text.customRules.scope[scope as keyof typeof text.customRules.scope]}
            </option>
          ))}
        </select>
      </Field>
      <label className="ci-form__check">
        <input
          type="checkbox"
          checked={match.onEveryPath === true}
          onChange={(event) => onChange({ ...match, onEveryPath: event.target.checked })}
        />
        {text.customRules.fieldOnEveryPath}
      </label>
    </>
  );
}

function Field({
  label,
  note,
  children,
}: {
  label: string;
  note?: string;
  children: ReactNode;
}): ReactElement {
  return (
    <label className="ci-form__field">
      <span className="ci-form__label">{label}</span>
      {children}
      {note === undefined ? null : <span className="ci-form__note">{note}</span>}
    </label>
  );
}

/** A group of checkboxes over a fixed vocabulary, in place of a multiple-selection list box. */
function CheckList({
  options,
  selected,
  onChange,
}: {
  options: readonly string[];
  selected: readonly string[];
  onChange: (selected: string[]) => void;
}): ReactElement {
  return (
    <span className="ci-form__checks">
      {options.map((option) => (
        <label className="ci-form__check" key={option}>
          <input
            type="checkbox"
            checked={selected.includes(option)}
            onChange={(event) =>
              onChange(
                event.target.checked
                  ? [...options.filter((o) => o === option || selected.includes(o))]
                  : selected.filter((value) => value !== option),
              )
            }
          />
          {option}
        </label>
      ))}
    </span>
  );
}

/**
 * A list of short words in one text field.
 *
 * The typed text is held here rather than derived from the value on every keystroke: reformatting
 * mid-word would eat the separator the moment it was typed. The field is re-synchronised only when
 * the value changes from elsewhere — the raw pane, or a switch to another rule.
 */
function ListField({
  value,
  onChange,
}: {
  value: readonly string[];
  onChange: (value: string[]) => void;
}): ReactElement {
  const [draft, setDraft] = useState(() => formatList(value));

  useEffect(() => {
    if (parseList(draft).join(" ") !== value.join(" ")) {
      setDraft(formatList(value));
    }
    // Only an outside change re-synchronises the field; comparing against `draft` here would undo
    // the user's own typing on the very next render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  return (
    <input
      className="ci-input"
      value={draft}
      onChange={(event) => {
        setDraft(event.target.value);
        onChange(parseList(event.target.value));
      }}
    />
  );
}
