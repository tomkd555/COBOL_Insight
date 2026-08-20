import { useMemo, useState, type ReactElement } from "react";
import { Button } from "../components/Button";
import { TextInput } from "../components/TextInput";
import { RuleTable } from "../screens/settings/RuleTable";
import { UserRuleForm } from "../screens/settings/UserRuleForm";
import { UserRuleList } from "../screens/settings/UserRuleList";
import {
  ALL_RULES,
  buildRuleGroups,
  filterCountLabel,
  groupedRuleIds,
  isReadOnly,
  ruleCategories,
  ruleConfigToggling,
  ruleConfigWith,
  ruleCountLabel,
  type RuleFilter,
  type RuleSourceFilter,
} from "../screens/settings/settingsModel";
import { messageOf } from "../services/analysis";
import { loadRuleCatalog } from "../services/appSetup";
import { useProject, useProjectDispatch } from "../state/projectStore";
import type { RuleConfigFile, UserRuleDefinition } from "../../../shared/engine-api";
import {
  USER_RULES_VERSION,
  newUserRuleDraft,
  validateUserRule,
} from "../../../shared/userRules";

/** 出所の絞り込みの選択肢。 */
const SOURCE_OPTIONS: readonly { readonly value: RuleSourceFilter; readonly label: string }[] = [
  { value: "all", label: "すべて" },
  { value: "builtin", label: "組み込み" },
  { value: "user", label: "利用者定義" },
];

/** 編集中の利用者定義ルール。index が null なら新規である。 */
interface UserRuleDraft {
  readonly rule: UserRuleDefinition;
  readonly index: number | null;
}

/**
 * ルールのタブ。engine の `rules` が返す一覧をそのまま並べ、有効・無効の切替と利用者定義ルールの
 * 追加・編集・削除を行う。
 *
 * どのルールが検出に効いているかは engine が rules-config.json を読んで返す enabled が唯一の
 * 答えである。切替は設定ファイルへ書いたうえで一覧を engine から取り直すため、ファイルを直に
 * 書き換えても画面と同じ結果になる。利用者定義ルールも同じく定義ファイルへ書いて取り直す。
 */
export function RulesTab(): ReactElement {
  const project = useProject();
  const dispatch = useProjectDispatch();
  const readOnly = isReadOnly(project.mode);

  const [filter, setFilter] = useState<RuleFilter>(ALL_RULES);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<UserRuleDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const catalog = project.catalog;
  const groups = useMemo(() => buildRuleGroups(catalog, filter), [catalog, filter]);
  const filtered = useMemo(() => groupedRuleIds(groups), [groups]);
  const categories = useMemo(() => ruleCategories(catalog), [catalog]);
  const draftErrors = useMemo(
    () =>
      draft === null ? [] : validateUserRule(draft.rule, project.userRules, draft.index),
    [draft, project.userRules],
  );

  /** 設定ファイルへ書いて一覧を取り直す。書いただけでは表の状態が変わらない。 */
  async function applyRuleConfig(config: RuleConfigFile): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const paths = await window.cobolInsight.getOutputPaths();
      await window.cobolInsight.writeRuleConfig(paths.ruleConfig, config);
      await loadRuleCatalog(dispatch);
    } catch (cause) {
      setError(`ルールの有効・無効を保存できませんでした。${messageOf(cause)}`);
    } finally {
      setBusy(false);
    }
  }

  /** 定義ファイルへ書いて一覧を取り直す。保存と取り直しで1つの操作である。 */
  async function persistUserRules(next: UserRuleDefinition[], done: string): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const paths = await window.cobolInsight.getOutputPaths();
      await window.cobolInsight.writeUserRules(paths.userRules, {
        version: USER_RULES_VERSION,
        rules: next,
      });
      await loadRuleCatalog(dispatch);
      setDraft(null);
      dispatch({ type: "LOG", text: done });
    } catch (cause) {
      setError(`利用者定義ルールを保存できませんでした。${messageOf(cause)}`);
    } finally {
      setBusy(false);
    }
  }

  function saveDraft(): void {
    if (draft === null || draftErrors.length > 0) {
      return;
    }
    const next = [...project.userRules];
    if (draft.index === null) {
      next.push(draft.rule);
    } else {
      next[draft.index] = draft.rule;
    }
    void persistUserRules(next, `${draft.rule.id} を保存しました。`);
  }

  function removeUserRule(index: number): void {
    const removed = project.userRules[index];
    void persistUserRules(
      project.userRules.filter((_, position) => position !== index),
      `${removed.id} を削除しました。`,
    );
  }

  const locked = readOnly || busy;

  return (
    <div className="ci-settings">
      <div className="ci-settings__page">
        <h3 className="ci-settings__title">ルール</h3>
        {readOnly ? (
          <div className="ci-settings__lock" role="status">
            解析の実行中はルールを変更できません。
          </div>
        ) : null}
        {error === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {error}
          </div>
        )}
        {project.ruleConfigWarnings.length === 0 ? null : (
          <ul className="ci-settings__rule-errors" role="alert">
            {project.ruleConfigWarnings.map((warning) => (
              <li key={warning}>ルールの設定ファイル: {warning}</li>
            ))}
          </ul>
        )}
        {project.userRuleErrors.length === 0 ? null : (
          <ul className="ci-settings__rule-errors" role="alert">
            {project.userRuleErrors.map((message) => (
              <li key={message}>利用者定義ルールの定義に誤りがあります: {message}</li>
            ))}
          </ul>
        )}

        <section className="ci-settings__card">
          <div className="ci-settings__head">
            <h4 className="ci-settings__card-title">検出ルールの有効・無効</h4>
            <span className="ci-settings__count">{ruleCountLabel(catalog)}</span>
            <div className="ci-settings__spacer" />
            <label className="ci-field">
              検索
              <TextInput
                className="ci-settings__search"
                placeholder="例: R004 / MOVE / データフロー"
                value={filter.search}
                onChange={(event) => setFilter({ ...filter, search: event.target.value })}
              />
            </label>
          </div>
          <div className="ci-settings__bulk">
            <label className="ci-field">
              出所
              <select
                className="ci-settings__select ci-settings__select--inline"
                value={filter.source}
                onChange={(event) =>
                  setFilter({ ...filter, source: event.target.value as RuleSourceFilter })
                }
              >
                {SOURCE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="ci-field">
              カテゴリ
              <select
                className="ci-settings__select ci-settings__select--inline"
                value={filter.category}
                onChange={(event) => setFilter({ ...filter, category: event.target.value })}
              >
                <option value="">すべて</option>
                {categories.map((category) => (
                  <option key={category} value={category}>
                    {category}
                  </option>
                ))}
              </select>
            </label>
            <div className="ci-settings__spacer" />
            <Button
              disabled={locked || filtered.length === 0}
              onClick={() => void applyRuleConfig(ruleConfigWith(catalog, filtered, true))}
            >
              表示中をすべて有効
            </Button>
            <Button
              disabled={locked || filtered.length === 0}
              onClick={() => void applyRuleConfig(ruleConfigWith(catalog, filtered, false))}
            >
              表示中をすべて無効
            </Button>
            {filterCountLabel(filter, groups) === null ? null : (
              <span className="ci-settings__count">{filterCountLabel(filter, groups)}</span>
            )}
          </div>
          {/* 取り込み前は一覧を描かない。ルール名も重大度もこの取り込みで初めて定まる。 */}
          {catalog.loaded ? (
            <RuleTable
              groups={groups}
              onToggle={(id) => void applyRuleConfig(ruleConfigToggling(catalog, id))}
              onToggleDetail={(id) => setExpandedId(expandedId === id ? null : id)}
              expandedId={expandedId}
              disabled={locked}
            />
          ) : (
            <p className="ci-rules__no-hit" role="status">
              解析エンジンからルールの一覧を読み込んでいます。
            </p>
          )}
        </section>

        <section className="ci-settings__card">
          <div className="ci-settings__head">
            <h4 className="ci-settings__card-title">利用者定義ルール</h4>
            <span className="ci-settings__count">{project.userRules.length} 件</span>
            <div className="ci-settings__spacer" />
            <Button
              variant="primary"
              disabled={locked || draft !== null}
              onClick={() =>
                setDraft({ rule: newUserRuleDraft(project.userRules), index: null })
              }
            >
              <span aria-hidden="true">＋</span> ルールを追加
            </Button>
          </div>
          <UserRuleList
            rules={project.userRules}
            onEdit={(index) => setDraft({ rule: project.userRules[index], index })}
            onRemove={removeUserRule}
            disabled={locked || draft !== null}
          />
          {draft === null ? null : (
            <UserRuleForm
              draft={draft.rule}
              errors={draftErrors}
              isNew={draft.index === null}
              onChange={(patch) => setDraft({ ...draft, rule: { ...draft.rule, ...patch } })}
              onSave={saveDraft}
              onCancel={() => setDraft(null)}
              disabled={locked}
            />
          )}
        </section>
      </div>
    </div>
  );
}
