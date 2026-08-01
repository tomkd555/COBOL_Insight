import type { ReactElement } from "react";
import type { RuleInfo } from "../../data/ruleCatalog";

export interface RuleDetailProps {
  rule: RuleInfo;
}

/** 説明の項目。値が空のものは節ごと出さない(利用者定義ルールでは理由と対処を省ける)。 */
const SECTIONS: readonly { readonly title: string; readonly key: keyof RuleInfo }[] = [
  { title: "何を検出するか", key: "summary" },
  { title: "なぜ問題か", key: "rationale" },
  { title: "検出条件", key: "detection" },
  { title: "どう直すか", key: "remedy" },
];

/**
 * ルール1件の説明。engine の RuleDoc をそのまま示し、画面側で文言を持たない。
 * 例は該当する例と直した例を並べ、対比で読めるようにする。
 */
export function RuleDetail({ rule }: RuleDetailProps): ReactElement {
  return (
    <div className="ci-rule-detail">
      <dl className="ci-rule-detail__list">
        {SECTIONS.filter((section) => String(rule[section.key]).trim() !== "").map((section) => (
          <div key={section.title} className="ci-rule-detail__item">
            <dt className="ci-rule-detail__term">{section.title}</dt>
            <dd className="ci-rule-detail__desc">{String(rule[section.key])}</dd>
          </div>
        ))}
      </dl>
      {rule.badExample !== "" && rule.goodExample !== "" ? (
        <div className="ci-rule-detail__examples">
          <figure className="ci-rule-detail__example">
            <figcaption className="ci-rule-detail__example-title ci-rule-detail__example-title--bad">
              該当する例
            </figcaption>
            <pre className="ci-rule-detail__code">{rule.badExample}</pre>
          </figure>
          <figure className="ci-rule-detail__example">
            <figcaption className="ci-rule-detail__example-title ci-rule-detail__example-title--good">
              直した例
            </figcaption>
            <pre className="ci-rule-detail__code">{rule.goodExample}</pre>
          </figure>
        </div>
      ) : null}
    </div>
  );
}
