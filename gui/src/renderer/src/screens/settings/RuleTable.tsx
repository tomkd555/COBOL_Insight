import type { ReactElement } from "react";
import { SeverityBadge } from "../../components/SeverityBadge";
import type { RuleGroup } from "./settingsModel";

export interface RuleTableProps {
  groups: readonly RuleGroup[];
  /** 有効・無効を切り替える。読み取り専用のときは呼ばれない。 */
  onToggle: (id: string) => void;
  disabled: boolean;
}

/**
 * 検出ルールの一覧(design scSet のルール表)。カテゴリ別の見出しの下に、ルールごとの有効・無効の
 * トグル・ID・名称・修正案の有無・重大度を並べる。トグルは switch ロールのボタンとし、
 * 状態を aria-checked で表す(色だけに依存しない)。
 */
export function RuleTable({ groups, onToggle, disabled }: RuleTableProps): ReactElement {
  if (groups.length === 0) {
    return (
      <p className="ci-rules__no-hit">
        検索に一致するルールがない。ルール ID・名称・カテゴリで探せる。
      </p>
    );
  }
  return (
    <div className="ci-rules">
      {groups.map((group) => (
        <section key={group.category} className="ci-rules__group" aria-label={group.category}>
          <h5 className="ci-rules__category">{group.category}</h5>
          {group.rows.map((row) => (
            <div key={row.id} className="ci-rules__row">
              <button
                type="button"
                className={row.disabled ? "ci-switch" : "ci-switch ci-switch--on"}
                role="switch"
                aria-checked={!row.disabled}
                aria-label={`${row.id} ${row.name}`}
                disabled={disabled}
                onClick={() => onToggle(row.id)}
              >
                <span className="ci-switch__knob" aria-hidden="true" />
              </button>
              <span className="ci-rules__id">{row.id}</span>
              <span className={row.disabled ? "ci-rules__name ci-rules__name--off" : "ci-rules__name"}>
                {row.name}
                {/* 無効であることを色以外でも示す。読み上げは同じ行の switch が担うため隠す。 */}
                {row.disabled ? (
                  <span className="ci-rules__off-mark" aria-hidden="true">
                    （無効）
                  </span>
                ) : null}
              </span>
              {row.hasFix ? <span className="ci-rules__fix-badge">修正案</span> : null}
              <SeverityBadge severity={row.severity} className="ci-rules__severity" />
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
