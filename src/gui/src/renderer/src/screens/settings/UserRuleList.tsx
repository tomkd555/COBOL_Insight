import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import type { UserRuleDefinition } from "../../../../shared/engine-api";
import { SEVERITY_LABELS_BY_ENGINE_NAME, targetsLabel } from "./userRulesModel";

export interface UserRuleListProps {
  rules: readonly UserRuleDefinition[];
  onEdit: (index: number) => void;
  onRemove: (index: number) => void;
  disabled: boolean;
}

/**
 * 利用者定義ルールの一覧。定義ファイル(user-rules.json)の中身をそのまま並べる。
 * 有効・無効の切替は組み込みルールと同じく上のルール表で行うため、ここでは持たない。
 */
export function UserRuleList({
  rules,
  onEdit,
  onRemove,
  disabled,
}: UserRuleListProps): ReactElement {
  if (rules.length === 0) {
    return (
      <p className="ci-user-rules__empty">
        利用者定義ルールはまだありません。「ルールを追加」から、正規表現で検出する検査を作れます。
      </p>
    );
  }
  return (
    <ul className="ci-user-rules__list">
      {rules.map((rule, index) => (
        <li key={rule.id} className="ci-user-rules__item">
          <div className="ci-user-rules__head">
            <span className="ci-rules__id">{rule.id}</span>
            <span className="ci-user-rules__name">{rule.name}</span>
            <span className="ci-user-rules__meta">
              {targetsLabel(rule.targets)} / 重大度{" "}
              {SEVERITY_LABELS_BY_ENGINE_NAME[rule.severity] ?? rule.severity}
            </span>
            <div className="ci-settings__spacer" />
            <Button disabled={disabled} onClick={() => onEdit(index)}>
              編集
            </Button>
            <Button disabled={disabled} onClick={() => onRemove(index)}>
              削除
            </Button>
          </div>
          <code className="ci-user-rules__pattern">{rule.pattern}</code>
          <p className="ci-user-rules__message">{rule.message}</p>
        </li>
      ))}
    </ul>
  );
}
