import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { Button } from "../../components/Button";
import { ChipRadioGroup } from "../../components/ChipRadioGroup";
import { TextInput } from "../../components/TextInput";
import { SEVERITY_BY_LABEL, SEVERITY_META, SEVERITY_ORDER } from "../../components/severity";
import { useAppDispatch, useAppState } from "../../state/AppStateContext";
import { loadRuleCatalog } from "../../data/loadRuleCatalog";
import { isRuleCatalogLoaded } from "../../data/ruleCatalog";
import type { UserRuleDefinition } from "../../../../shared/engine-api";
import {
  USER_RULES_VERSION,
  newUserRuleDraft,
  validateUserRule,
} from "../../../../shared/userRules";
import { CopybookPathList } from "./CopybookPathList";
import { RuleTable } from "./RuleTable";
import { UserRuleForm } from "./UserRuleForm";
import { UserRuleList } from "./UserRuleList";
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

  // カタログは data/ruleCatalog がモジュール共通で保つため、取り込み世代の変化を依存に加えて
  // 描き直す(利用者定義ルールを保存すると件数と並びが変わる)。
  const generation = state.ruleCatalogGeneration;
  const groups = useMemo(
    () => buildRuleGroups(search, disabledRules),
    [search, disabledRules, generation],
  );
  const filtered = useMemo(() => groupedRuleIds(groups), [groups]);
  const paths = state.project.copybookPaths;

  // コピー句探索パスの実在確認。パスごとの結果を保ち、追加操作の直後にも入力欄の近くへ
  // 警告を出せるよう、最後に追加したパスを別に覚えておく。
  const [pathExists, setPathExists] = useState<Record<string, boolean>>({});
  const [lastAddedPath, setLastAddedPath] = useState<string | null>(null);
  const checkedPathsRef = useRef<Set<string>>(new Set());

  // 画面を開いた時点、および新しいパスが加わった時点で実在確認を行う。既に確認したパスは
  // 再確認しない。
  useEffect(() => {
    const toCheck = paths.filter((path) => !checkedPathsRef.current.has(path));
    for (const path of toCheck) {
      checkedPathsRef.current.add(path);
      window.cobolInsight
        .checkDirectoryExists(path)
        .then((exists) => setPathExists((prev) => ({ ...prev, [path]: exists })))
        .catch(() => {
          checkedPathsRef.current.delete(path);
        });
    }
  }, [paths]);

  const addWarning =
    lastAddedPath !== null && pathExists[lastAddedPath] === false
      ? `${lastAddedPath} が見つからない。`
      : null;

  /** コピー句探索パスの並びを全画面へ反映する。 */
  function applyPaths(next: string[]): void {
    dispatch({ type: "SET_PROJECT", project: { copybookPaths: next } });
  }

  function onDraftChange(value: string): void {
    setLastAddedPath(null);
    dispatch({ type: "SET_NEW_COPYBOOK_PATH", value });
  }

  function onAddPath(): void {
    const result = addPath(paths, draft);
    if (!result.added) {
      dispatch({ type: "SHOW_TOAST", message: result.reason ?? "" });
      return;
    }
    applyPaths(result.paths);
    dispatch({ type: "SET_NEW_COPYBOOK_PATH", value: "" });
    setLastAddedPath(result.paths[result.paths.length - 1] ?? null);
  }

  function onRemovePath(index: number): void {
    const removed = paths[index];
    applyPaths(removePath(paths, index));
    if (removed === lastAddedPath) {
      setLastAddedPath(null);
    }
    dispatch({ type: "SHOW_TOAST", message: `コピー句探索パスから ${removed} を削除しました。` });
  }

  const userRules = state.userRules;
  const ruleDraft = state.userRuleDraft;
  const draftIndex = state.userRuleDraftIndex;
  const draftErrors = useMemo(
    () => (ruleDraft === null ? [] : validateUserRule(ruleDraft, userRules, draftIndex)),
    [ruleDraft, userRules, draftIndex],
  );

  /**
   * 定義ファイルへ書き、engine からカタログを取り直す。書いただけではルール表に現れないため、
   * 保存と取り直しを1つの操作として扱う。
   */
  async function persistUserRules(next: UserRuleDefinition[], toast: string): Promise<void> {
    try {
      const outputs = await window.cobolInsight.getOutputPaths();
      await window.cobolInsight.writeUserRules(outputs.userRules, {
        version: USER_RULES_VERSION,
        rules: next,
      });
      await loadRuleCatalog(dispatch);
      dispatch({ type: "CANCEL_USER_RULE_EDIT" });
      dispatch({ type: "SHOW_TOAST", message: toast });
    } catch (error) {
      dispatch({
        type: "SHOW_TOAST",
        message: `利用者定義ルールを保存できなかった: ${
          error instanceof Error ? error.message : String(error)
        }`,
      });
    }
  }

  function onSaveUserRule(): void {
    if (ruleDraft === null || draftErrors.length > 0) {
      return;
    }
    const next = [...userRules];
    if (draftIndex === null) {
      next.push(ruleDraft);
    } else {
      next[draftIndex] = ruleDraft;
    }
    void persistUserRules(next, `${ruleDraft.id} を保存しました。`);
  }

  function onRemoveUserRule(index: number): void {
    const removed = userRules[index];
    void persistUserRules(
      userRules.filter((_, position) => position !== index),
      `${removed.id} を削除しました。`,
    );
  }

  return (
    <div className="ci-settings">
      <div className="ci-settings__page">
        <h3 className="ci-settings__title">設定</h3>
        {readOnly ? (
          <div className="ci-settings__lock" role="status">
            解析の実行中は設定を変更できない（読み取り専用）。
          </div>
        ) : null}

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">既定の文字コード</h4>
          <p className="ci-settings__desc">
            解析エンジンが文字コードを判定できなかった資産で、文字コード選択欄とデコードプレビューの
            初期値として用いる。資産ごとの手動指定（資産エクスプローラー）が常に優先される。
            解析エンジンは既定の文字コードを受け取らないため、解析実行へ渡るのは資産ごとの
            手動指定だけである。
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
          <h4 className="ci-settings__card-title">コピー句検索パス</h4>
          <p className="ci-settings__desc">
            上から順に検索する。同名のコピー句が複数ある場合、先に見つかったものを使う。
            解析エンジンはここで並べた順序のままコピー句を探索する。
          </p>
          <CopybookPathList
            paths={paths}
            draft={draft}
            onDraftChange={onDraftChange}
            onMove={(index, delta) => applyPaths(movePath(paths, index, delta))}
            onRemove={onRemovePath}
            onAdd={onAddPath}
            disabled={readOnly}
            existence={pathExists}
            addWarning={addWarning}
          />
        </section>

        <section className="ci-settings__card">
          <div className="ci-settings__head">
            <h4 className="ci-settings__card-title">検出ルールの有効・無効</h4>
            <span className="ci-settings__count">{ruleCountLabel(disabledRules)}</span>
            <div className="ci-settings__spacer" />
            <label className="ci-field">
              検索
              <TextInput
                className="ci-settings__search"
                placeholder="例: R004 / MOVE / データフロー"
                value={search}
                onChange={(event) => dispatch({ type: "SET_RULE_SEARCH", value: event.target.value })}
              />
            </label>
          </div>
          <p className="ci-settings__desc">
            指摘の検出（R001〜R031）と SQL 最適化助言（S001〜S006）、および利用者定義ルール（U〜）。
            すべて既定で有効である。無効にしたルールは解析実行の検出対象から除く。
            各行の「説明」から、そのルールが何を検出し、なぜ問題で、どう直すかを読める。
          </p>
          {state.userRuleErrors.length === 0 ? null : (
            <ul className="ci-settings__rule-errors" role="alert">
              {state.userRuleErrors.map((error) => (
                <li key={error}>利用者定義ルールの定義に誤りがある: {error}</li>
              ))}
            </ul>
          )}
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
          {/* 取り込み前は一覧を描かない。ルール名も重大度もこの取り込みで初めて定まるためである。 */}
          {!isRuleCatalogLoaded() ? (
            <p className="ci-rules__no-hit" role="status">
              解析エンジンからルール一覧を読み込んでいる。
            </p>
          ) : (
            <RuleTable
              groups={groups}
              onToggle={(id) => dispatch({ type: "TOGGLE_RULE", id })}
              onToggleDetail={(id) => dispatch({ type: "TOGGLE_RULE_DETAIL", id })}
              expandedId={state.ruleDetailId}
              disabled={readOnly}
            />
          )}
        </section>

        <section className="ci-settings__card">
          <div className="ci-settings__head">
            <h4 className="ci-settings__card-title">利用者定義ルール</h4>
            <span className="ci-settings__count">{userRules.length} 件</span>
            <div className="ci-settings__spacer" />
            <Button
              variant="primary"
              disabled={readOnly || ruleDraft !== null}
              onClick={() =>
                dispatch({ type: "EDIT_USER_RULE", rule: newUserRuleDraft(userRules), index: null })
              }
            >
              ＋ ルールを追加
            </Button>
          </div>
          <p className="ci-settings__desc">
            正規表現で1行ずつ検査する自前のルールを作れる。作ったルールは組み込みルールと同じく
            解析実行の対象になり、上のルール表にも並ぶ。定義は解析エンジンが読む JSON
            ファイルへ保存する。
          </p>
          <UserRuleList
            rules={userRules}
            onEdit={(index) =>
              dispatch({ type: "EDIT_USER_RULE", rule: userRules[index], index })
            }
            onRemove={onRemoveUserRule}
            disabled={readOnly || ruleDraft !== null}
          />
          {ruleDraft === null ? null : (
            <UserRuleForm
              draft={ruleDraft}
              errors={draftErrors}
              isNew={draftIndex === null}
              onChange={(patch) => dispatch({ type: "UPDATE_USER_RULE_DRAFT", patch })}
              onSave={onSaveUserRule}
              onCancel={() => dispatch({ type: "CANCEL_USER_RULE_EDIT" })}
              disabled={readOnly}
            />
          )}
        </section>

        <section className="ci-settings__card">
          <h4 className="ci-settings__card-title">表示する重大度のしきい値</h4>
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
          <h4 className="ci-settings__card-title">解析エンジンの実行</h4>
          <p className="ci-settings__desc">
            解析は同梱の解析エンジンが別のプログラムとして担い、結果をファイルで受け取る。
            ネットワーク接続は行わない。
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
