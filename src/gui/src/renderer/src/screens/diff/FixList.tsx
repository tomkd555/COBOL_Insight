import { useRef, type KeyboardEvent, type ReactElement } from "react";
import { nextRovingIndex } from "../../components/rovingList";
import type { RuleCatalogIndex } from "../../data/ruleCatalog";
import type { FixDecision } from "./diffModel";
import {
  candidateLocation,
  candidateRuleSummary,
  decisionLabel,
  decisionModifier,
  fixCountLabel,
  type FixCandidate,
} from "./diffModel";

export interface FixListProps {
  /** ルール名を引く索引。 */
  catalog: RuleCatalogIndex;
  candidates: readonly FixCandidate[];
  /** 選択中の修正案の相対パス。 */
  selected: string | null;
  decisions: Readonly<Record<string, FixDecision>>;
  onSelect: (relPath: string) => void;
}

/**
 * 修正案カードの一覧。1件はファイル単位で、ルール・位置・判定状態を示し、コピー句の修正は
 * バッジで区別する。
 *
 * 単一選択の一覧(role=listbox / role=option)として表し、roving tabindex で焦点を選択中の項目へ
 * 集約する。矢印キーで選択を移し(端では反対の端へ回す)、Home・End で端へ移す。
 */
export function FixList({
  catalog,
  candidates,
  selected,
  decisions,
  onSelect,
}: FixListProps): ReactElement {
  const listRef = useRef<HTMLUListElement>(null);
  const selectedIndex = candidates.findIndex((candidate) => candidate.relPath === selected);
  const activeIndex = selectedIndex < 0 ? 0 : selectedIndex;

  function focusOption(index: number): void {
    listRef.current?.querySelectorAll<HTMLLIElement>('[role="option"]')[index]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLUListElement>): void {
    if (candidates.length === 0) {
      return;
    }
    // 焦点のある項目をそのまま選び直す。選択が消えている場合はここで先頭が選択される。
    const next =
      event.key === "Enter" || event.key === " "
        ? activeIndex
        : nextRovingIndex(event.key, activeIndex, candidates.length);
    if (next === null) return;
    event.preventDefault();
    onSelect(candidates[next].relPath);
    focusOption(next);
  }

  return (
    <div className="ci-fix-list">
      <h4 className="ci-fix-list__head">修正案 {fixCountLabel(candidates)}</h4>
      <ul
        ref={listRef}
        className="ci-fix-list__items"
        role="listbox"
        aria-label="修正案の一覧"
        onKeyDown={onKeyDown}
      >
        {candidates.map((candidate, index) => {
          const active = candidate.relPath === selected;
          const decision = decisions[candidate.relPath];
          return (
            <li
              key={candidate.relPath}
              className={active ? "ci-fix-card ci-fix-card--selected" : "ci-fix-card"}
              role="option"
              aria-selected={active}
              tabIndex={index === activeIndex ? 0 : -1}
              onClick={() => onSelect(candidate.relPath)}
            >
              <span className="ci-fix-card__head">
                <span className="ci-fix-card__rule">{candidateRuleSummary(catalog, candidate)}</span>
                {candidate.copybook ? (
                  <span className="ci-fix-card__badge ci-fix-card__badge--copybook">コピー句</span>
                ) : null}
              </span>
              <span className="ci-fix-card__loc">{candidateLocation(candidate)}</span>
              <span
                className={`ci-fix-card__decision ci-fix-card__decision--${decisionModifier(decision)}`}
              >
                {decisionLabel(decision)}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="ci-fix-list__note">
        原本は変更しない。「適用（書き出し）」は出力先へ相対パス構造を保って書き出す。採用・棄却は常に人の判断である。
      </p>
    </div>
  );
}
