import { useRef, type KeyboardEvent, type ReactElement } from "react";
import type { FixDecision } from "../../state/appState";
import {
  candidateLocation,
  candidateRuleSummary,
  decisionLabel,
  decisionModifier,
  fixCountLabel,
  type FixCandidate,
} from "./diffModel";

/** 矢印キーによる移動量(1=次、-1=前)。Home・End は端へ移す。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
};

export interface FixListProps {
  candidates: readonly FixCandidate[];
  /** 選択中の修正案の相対パス。 */
  selected: string | null;
  decisions: Readonly<Record<string, FixDecision>>;
  onSelect: (relPath: string) => void;
}

/**
 * 修正案カードの一覧(design scDiff の左 300px)。1件はファイル単位で、ルール・位置・判定状態を示し、
 * コピー句の修正はバッジで区別する。
 *
 * 単一選択の一覧(role=listbox / role=option)として表し、roving tabindex で焦点を選択中の項目へ
 * 集約する。矢印キーで選択を移し(端では反対の端へ回す)、Home・End で端へ移す。
 */
export function FixList({ candidates, selected, decisions, onSelect }: FixListProps): ReactElement {
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
    const last = candidates.length - 1;
    let next: number;
    if (event.key === "Home") {
      next = 0;
    } else if (event.key === "End") {
      next = last;
    } else if (event.key === "Enter" || event.key === " ") {
      next = activeIndex;
    } else {
      const step = STEP_KEYS[event.key];
      if (step === undefined) return;
      next = (activeIndex + step + candidates.length) % candidates.length;
    }
    event.preventDefault();
    onSelect(candidates[next].relPath);
    focusOption(next);
  }

  return (
    <div className="ci-fix-list">
      <p className="ci-fix-list__head">修正案 {fixCountLabel(candidates)}</p>
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
                <span className="ci-fix-card__rule">{candidateRuleSummary(candidate)}</span>
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
        原本は変更しません。「適用（書き出し）」は出力先へ相対パス構造を保って書き出します。採用・棄却は常に人の判断です。
      </p>
    </div>
  );
}
