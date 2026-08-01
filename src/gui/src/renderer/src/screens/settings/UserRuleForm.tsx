import { useState, type ReactElement } from "react";
import { Button } from "../../components/Button";
import { TextInput } from "../../components/TextInput";
import type { UserRuleDefinition } from "../../../../shared/engine-api";
import { USER_RULE_SEVERITIES, USER_RULE_TARGETS } from "../../../../shared/userRules";
import {
  SEVERITY_LABELS_BY_ENGINE_NAME,
  TARGET_LABELS,
  testResultLabel,
  testUserRulePattern,
} from "./userRulesModel";

export interface UserRuleFormProps {
  draft: UserRuleDefinition;
  /** 保存を妨げる誤りの説明。空なら保存できる。 */
  errors: readonly string[];
  /** 新規追加か(見出しと保存の文言を変える)。 */
  isNew: boolean;
  onChange: (patch: Partial<UserRuleDefinition>) => void;
  onSave: () => void;
  onCancel: () => void;
  disabled: boolean;
}

/**
 * 利用者定義ルールの作成・編集フォーム。入力した正規表現を試験用の本文へその場で当て、
 * どの行が指摘になるかを保存前に確かめられるようにする。
 *
 * 試験の一致は画面(JavaScript)の正規表現で求めるため、解析を実行したときの engine(Java)の
 * 結果と細部が異なりうる。その旨をフォームへ書き添える。
 */
export function UserRuleForm({
  draft,
  errors,
  isNew,
  onChange,
  onSave,
  onCancel,
  disabled,
}: UserRuleFormProps): ReactElement {
  // 試験用の本文は保存の対象ではないため、フォームの内側だけで持つ。
  const [sample, setSample] = useState("");
  const result = testUserRulePattern(draft, sample);

  function toggleTarget(target: string): void {
    const next = draft.targets.includes(target)
      ? draft.targets.filter((element) => element !== target)
      : [...draft.targets, target];
    onChange({ targets: next });
  }

  return (
    <form
      className="ci-user-rule-form"
      aria-label={isNew ? "利用者定義ルールの追加" : `${draft.id} の編集`}
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
    >
      <h5 className="ci-user-rule-form__title">
        {isNew ? "利用者定義ルールを追加する" : `${draft.id} を編集する`}
      </h5>

      <div className="ci-user-rule-form__grid">
        <label className="ci-field">
          ID
          <TextInput
            value={draft.id}
            disabled={disabled}
            onChange={(event) => onChange({ id: event.target.value.toUpperCase() })}
          />
        </label>
        <label className="ci-field ci-user-rule-form__wide">
          名称
          <TextInput
            value={draft.name}
            placeholder="例: コンソール入力の使用"
            disabled={disabled}
            onChange={(event) => onChange({ name: event.target.value })}
          />
        </label>
        <label className="ci-field">
          カテゴリ
          <TextInput
            value={draft.category}
            disabled={disabled}
            onChange={(event) => onChange({ category: event.target.value })}
          />
        </label>
        <label className="ci-field">
          重大度
          <select
            className="ci-settings__select"
            value={draft.severity}
            disabled={disabled}
            onChange={(event) => onChange({ severity: event.target.value })}
          >
            {USER_RULE_SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>
                {SEVERITY_LABELS_BY_ENGINE_NAME[severity]}
              </option>
            ))}
          </select>
        </label>
      </div>

      <fieldset className="ci-user-rule-form__fieldset">
        <legend className="ci-user-rule-form__legend">走査する資産</legend>
        {USER_RULE_TARGETS.map((target) => (
          <label key={target} className="ci-user-rule-form__check">
            <input
              type="checkbox"
              checked={draft.targets.includes(target)}
              disabled={disabled}
              onChange={() => toggleTarget(target)}
            />
            {TARGET_LABELS[target]}
          </label>
        ))}
      </fieldset>

      <label className="ci-field">
        正規表現
        <TextInput
          value={draft.pattern}
          placeholder="例: ACCEPT\\s+\\S+\\s+FROM\\s+CONSOLE"
          disabled={disabled}
          onChange={(event) => onChange({ pattern: event.target.value })}
        />
      </label>
      <p className="ci-settings__note">
        1 行ずつ当てる。既定では注記行を除き、8〜72 桁の範囲だけを見る。
        解析を実行したときの判定は解析エンジン（Java の正規表現）が行うため、
        後方参照や先読みの細部でこの試験結果と異なることがある。
      </p>

      <label className="ci-field">
        除外する正規表現（任意）
        <TextInput
          value={draft.excludePattern}
          placeholder="同じ行がこれにも一致する場合は検出しない"
          disabled={disabled}
          onChange={(event) => onChange({ excludePattern: event.target.value })}
        />
      </label>

      <div className="ci-user-rule-form__options">
        <label className="ci-user-rule-form__check">
          <input
            type="checkbox"
            checked={draft.ignoreCase}
            disabled={disabled}
            onChange={(event) => onChange({ ignoreCase: event.target.checked })}
          />
          大文字と小文字を区別しない
        </label>
        <label className="ci-user-rule-form__check">
          <input
            type="checkbox"
            checked={draft.wholeLine}
            disabled={disabled}
            onChange={(event) => onChange({ wholeLine: event.target.checked })}
          />
          行全体を対象にする（注記行と欄外も走査する）
        </label>
      </div>

      <label className="ci-field">
        指摘のメッセージ
        <TextInput
          value={draft.message}
          placeholder="例: コンソール入力は運用規約で禁止されている"
          disabled={disabled}
          onChange={(event) => onChange({ message: event.target.value })}
        />
      </label>
      <p className="ci-settings__note">
        メッセージに <code>{"${match}"}</code> と書くと、一致した文字列へ置き換わる。
      </p>

      <label className="ci-field">
        なぜ問題か（任意）
        <textarea
          className="ci-user-rule-form__textarea"
          rows={2}
          value={draft.rationale}
          disabled={disabled}
          onChange={(event) => onChange({ rationale: event.target.value })}
        />
      </label>
      <label className="ci-field">
        どう直すか（任意）
        <textarea
          className="ci-user-rule-form__textarea"
          rows={2}
          value={draft.remedy}
          disabled={disabled}
          onChange={(event) => onChange({ remedy: event.target.value })}
        />
      </label>

      <label className="ci-field">
        試験する本文
        <textarea
          className="ci-user-rule-form__textarea"
          rows={5}
          placeholder="COBOL の数行を貼り付けると、どの行が指摘になるかを示す。"
          value={sample}
          onChange={(event) => setSample(event.target.value)}
        />
      </label>
      <p className="ci-user-rule-form__test-summary" role="status">
        {testResultLabel(result)}
      </p>
      {result.matches.length === 0 ? null : (
        <ul className="ci-user-rule-form__matches">
          {result.matches.map((match) => (
            <li key={`${match.line}-${match.column}`} className="ci-user-rule-form__match">
              <span className="ci-user-rule-form__match-pos">
                {match.line} 行 {match.column} 桁
              </span>
              <code className="ci-user-rule-form__match-text">{match.text}</code>
              <span className="ci-user-rule-form__match-message">{match.message}</span>
            </li>
          ))}
        </ul>
      )}

      {errors.length === 0 ? null : (
        <ul className="ci-user-rule-form__errors" role="alert">
          {errors.map((error) => (
            <li key={error}>{error}</li>
          ))}
        </ul>
      )}

      <div className="ci-user-rule-form__actions">
        <Button type="submit" disabled={disabled || errors.length > 0}>
          {isNew ? "追加する" : "保存する"}
        </Button>
        <Button type="button" onClick={onCancel} disabled={disabled}>
          取り消す
        </Button>
      </div>
    </form>
  );
}
