import { useMemo, type ReactElement } from "react";
import { Button } from "../../components/Button";
import { ChipRadioGroup } from "../../components/ChipRadioGroup";
import { TextInput } from "../../components/TextInput";
import { SEVERITY_BY_LABEL, SEVERITY_META, SEVERITY_ORDER } from "../../components/severity";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { CopybookPathList } from "./CopybookPathList";
import { RuleTable } from "./RuleTable";
import {
  ENCODING_OPTIONS,
  ENGINE_LAUNCH_INFO,
  addPath,
  buildRuleGroups,
  filterCountLabel,
  groupedRuleIds,
  isReadOnly,
  movePath,
  removePath,
  ruleCountLabel,
  severityThresholdNote,
} from "./settingsModel";

/** 重大度しきい値のチップに出すラベル(高/中/低/警告)。 */
const SEVERITY_LABELS: readonly string[] = SEVERITY_ORDER.map(
  (severity) => SEVERITY_META[severity].label,
);

/**
 * 設定画面(GUI 専用。CLI サブコマンドに対応しない唯一の画面)。既定の文字コード・コピー句探索パス・
 * 検出ルール 37 件の有効/無効・表示する重大度のしきい値を扱う。解析の実行中は読み取り専用にする。
 *
 * 設定値はすべて AppState を正とし、画面内に控えを持たない。無効化したルールは解析実行が engine の
 * `--disable-rule` へ渡し、しきい値は指摘一覧・SQL助言の絞り込みへ効き、コピー句探索パスは
 * `--copybook-path` へ同じ順序で渡る。したがってタブを移動しても設定は失われない。
 */
export function SettingsScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();
  const readOnly = isReadOnly(state.mode);

  const search = state.ruleSearch;
  const disabledRules = state.rulesDisabled;
  const threshold = state.severityThreshold;
  const draft = state.newCopybookPath;

  const groups = useMemo(() => buildRuleGroups(search, disabledRules), [search, disabledRules]);
  const filtered = useMemo(() => groupedRuleIds(groups), [groups]);
  const paths = state.project.copybookPaths;

  /** コピー句探索パスの並びを全画面へ反映する。 */
  function applyPaths(next: string[]): void {
    dispatch({ type: "SET_PROJECT", project: { copybookPaths: next } });
  }

  function onAddPath(): void {
    const result = addPath(paths, draft);
    if (!result.added) {
      dispatch({ type: "SHOW_TOAST", message: result.reason ?? "" });
      return;
    }
    applyPaths(result.paths);
    dispatch({ type: "SET_NEW_COPYBOOK_PATH", value: "" });
  }

  function onRemovePath(index: number): void {
    const removed = paths[index];
    applyPaths(removePath(paths, index));
    dispatch({ type: "SHOW_TOAST", message: `コピー句探索パスから ${removed} を削除しました。` });
  }

  return (
    <div className="ci-settings">
      <div className="ci-settings__page">
        {readOnly ? (
          <div className="ci-settings__lock" role="status">
            解析の実行中は設定を変更できません（読み取り専用）。
          </div>
        ) : null}

        <section className="ci-settings__card">
          <h3 className="ci-settings__title">既定の文字コード</h3>
          <p className="ci-settings__desc">
            engine が文字コードを判定できなかった資産で、文字コード選択欄とデコードプレビューの
            初期値として用いる。資産ごとの手動指定（資産エクスプローラー）が常に優先される。engine
            には既定コードページの指定が無いため、解析実行へ渡るのは資産ごとの手動指定だけである。
          </p>
          <select
            className="ci-settings__select"
            aria-label="既定の文字コード"
            value={state.defaultEncoding}
            disabled={readOnly}
            onChange={(event) => dispatch({ type: "SET_DEFAULT_ENCODING", value: event.target.value })}
          >
            {ENCODING_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </section>

        <section className="ci-settings__card">
          <h3 className="ci-settings__title">コピー句検索パス</h3>
          <p className="ci-settings__desc">
            上から順に検索する。同名のコピー句が複数ある場合、先に見つかったものを使う。並びは
            engine の --copybook-path へ同じ順序で渡る。
          </p>
          <CopybookPathList
            paths={paths}
            draft={draft}
            onDraftChange={(value) => dispatch({ type: "SET_NEW_COPYBOOK_PATH", value })}
            onMove={(index, delta) => applyPaths(movePath(paths, index, delta))}
            onRemove={onRemovePath}
            onAdd={onAddPath}
            disabled={readOnly}
          />
        </section>

        <section className="ci-settings__card">
          <div className="ci-settings__head">
            <h3 className="ci-settings__title">検出ルールの有効・無効</h3>
            <span className="ci-settings__count">{ruleCountLabel(disabledRules)}</span>
            <div className="ci-settings__spacer" />
            <TextInput
              aria-label="ルールを検索（ID / 名称 / カテゴリ）"
              className="ci-settings__search"
              placeholder="ルールを検索（ID / 名称 / カテゴリ）"
              value={search}
              onChange={(event) => dispatch({ type: "SET_RULE_SEARCH", value: event.target.value })}
            />
          </div>
          <p className="ci-settings__desc">
            バグ検出 31 件（R001〜R031）と SQL 最適化助言 6 件（S001〜S006）。すべて既定で有効である。
            無効にしたルールは engine の --disable-rule として検出から除く。
          </p>
          <div className="ci-settings__bulk">
            <Button
              disabled={readOnly || filtered.length === 0}
              onClick={() => dispatch({ type: "SET_RULES_ENABLED", ids: filtered, enabled: true })}
            >
              {search.trim() === "" ? "すべて有効" : "表示中をすべて有効"}
            </Button>
            <Button
              disabled={readOnly || filtered.length === 0}
              onClick={() => dispatch({ type: "SET_RULES_ENABLED", ids: filtered, enabled: false })}
            >
              {search.trim() === "" ? "すべて無効" : "表示中をすべて無効"}
            </Button>
            {filterCountLabel(search, groups) === null ? null : (
              <span className="ci-settings__count">{filterCountLabel(search, groups)}</span>
            )}
          </div>
          <RuleTable
            groups={groups}
            onToggle={(id) => dispatch({ type: "TOGGLE_RULE", id })}
            disabled={readOnly}
          />
        </section>

        <section className="ci-settings__card">
          <h3 className="ci-settings__title">表示する重大度のしきい値</h3>
          <p className="ci-settings__desc">
            選んだ重大度以上の指摘・助言を指摘一覧と SQL助言へ表示する。しきい値より低い重大度は
            一覧から外れ、その重大度のフィルタチップも操作できなくなる。
          </p>
          <ChipRadioGroup
            label="表示する重大度のしきい値"
            options={SEVERITY_LABELS}
            value={SEVERITY_META[threshold].label}
            onChange={(label) =>
              dispatch({ type: "SET_SEVERITY_THRESHOLD", severity: SEVERITY_BY_LABEL[label] })
            }
          />
          <p className="ci-settings__note">{severityThresholdNote(threshold)}</p>
        </section>

        <section className="ci-settings__card">
          <h3 className="ci-settings__title">engine の実行</h3>
          <p className="ci-settings__desc">
            解析は engine（Java の CLI）が担う。GUI は main プロセスから engine を子プロセスとして
            起動し、結果はファイルで受け取る。ネットワーク接続は行わない。
          </p>
          <dl className="ci-settings__versions">
            {ENGINE_LAUNCH_INFO.map((entry) => (
              <div key={entry.label} className="ci-settings__version">
                <dt className="ci-settings__version-label">{entry.label}</dt>
                <dd className="ci-settings__version-value">{entry.value}</dd>
              </div>
            ))}
            <div className="ci-settings__version">
              <dt className="ci-settings__version-label">COBOL Insight</dt>
              <dd className="ci-settings__version-value">{state.version}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  );
}
