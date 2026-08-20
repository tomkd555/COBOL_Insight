import type { ReactElement } from "react";
import { SeverityBadge } from "../../components/SeverityBadge";
import { RuleDetail } from "./RuleDetail";
import type { RuleGroup } from "./settingsModel";

export interface RuleTableProps {
  groups: readonly RuleGroup[];
  /** 有効・無効を切り替える。読み取り専用のときは呼ばれない。 */
  onToggle: (id: string) => void;
  /** 説明の開閉を切り替える。読み取り専用でも操作できる(状態を変えないため)。 */
  onToggleDetail: (id: string) => void;
  /** 説明を開いているルール ID。null はいずれも開いていない。 */
  expandedId: string | null;
  disabled: boolean;
}

/**
 * 検出ルールの一覧。カテゴリ別の見出しの下に、ルールごとの有効・無効のトグル・ID・名称・
 * 出所・修正案の有無・重大度を並べる。トグルは switch ロールのボタンとし、状態を aria-checked
 * で表す(色だけに依存しない)。有効・無効は engine が返した値であり、この表は控えを持たない。
 *
 * 行ごとに説明を開ける。ルール名だけでは何を検出するのか分からないため、engine が持つ
 * 検出条件・理由・対処・例をその場で読めるようにする。
 */
export function RuleTable({
  groups,
  onToggle,
  onToggleDetail,
  expandedId,
  disabled,
}: RuleTableProps): ReactElement {
  if (groups.length === 0) {
    return (
      <p className="ci-rules__no-hit">
        絞り込みに一致するルールはありません。ルール ID・名称・カテゴリで探せます。
      </p>
    );
  }
  return (
    <div className="ci-rules">
      {groups.map((group) => (
        <section key={group.category} className="ci-rules__group" aria-label={group.category}>
          <h5 className="ci-rules__category">{group.category}</h5>
          {group.rows.map((row) => (
            <div key={row.id} className="ci-rules__entry">
              <div className="ci-rules__row">
                <button
                  type="button"
                  className={row.enabled ? "ci-switch ci-switch--on" : "ci-switch"}
                  role="switch"
                  aria-checked={row.enabled}
                  aria-label={`${row.id} ${row.name}`}
                  data-testid={`rule-switch-${row.id}`}
                  disabled={disabled}
                  onClick={() => onToggle(row.id)}
                >
                  <span className="ci-switch__knob" aria-hidden="true" />
                </button>
                <span className="ci-rules__id">{row.id}</span>
                <span
                  className={row.enabled ? "ci-rules__name" : "ci-rules__name ci-rules__name--off"}
                >
                  {row.name}
                  {/* 無効であることを色以外でも示す。読み上げは同じ行の switch が担うため隠す。 */}
                  {row.enabled ? null : (
                    <span className="ci-rules__off-mark" aria-hidden="true">
                      （無効）
                    </span>
                  )}
                </span>
                {row.source === "user" ? (
                  <span className="ci-rules__user-badge">利用者定義</span>
                ) : null}
                {row.hasFix ? <span className="ci-rules__fix-badge">修正案</span> : null}
                <SeverityBadge severity={row.severity} className="ci-rules__severity" />
                <button
                  type="button"
                  className="ci-rules__detail-toggle"
                  aria-expanded={expandedId === row.id}
                  aria-label={`${row.id} ${row.name} の説明`}
                  onClick={() => onToggleDetail(row.id)}
                >
                  {expandedId === row.id ? "説明を閉じる" : "説明"}
                </button>
              </div>
              {expandedId === row.id ? <RuleDetail rule={row} /> : null}
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
