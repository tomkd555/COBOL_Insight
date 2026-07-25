import { useRef, type KeyboardEvent, type ReactElement } from "react";
import type { GraphNodeListItem } from "./graphModel";

/** 矢印キーによる移動量(1=次、-1=前)。Home・End は端へ移す。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowDown: 1,
  ArrowRight: 1,
  ArrowUp: -1,
  ArrowLeft: -1,
};

export interface GraphNodeListProps {
  /** 図に表示しているノード(図へ渡した要素から導いたもの)。 */
  items: readonly GraphNodeListItem[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

/**
 * 図と併置するノード一覧。Cytoscape は canvas へ描くためノードをキーボードで選べない。
 * この一覧が図と等価な操作口になり、ここからの選択で詳細ペイン・隣接の展開・ソースを開く操作へ
 * 到達できる。
 *
 * 単一選択の一覧(role=listbox / role=option)として表し、roving tabindex で焦点を選択中の項目へ
 * 集約する。矢印キーで選択を移し(端では反対の端へ回す)、Home・End で端へ移す。
 */
export function GraphNodeList({ items, selectedId, onSelect }: GraphNodeListProps): ReactElement {
  const listRef = useRef<HTMLUListElement>(null);
  const selectedIndex = items.findIndex((item) => item.id === selectedId);
  const activeIndex = selectedIndex < 0 ? 0 : selectedIndex;

  function focusOption(index: number): void {
    listRef.current?.querySelectorAll<HTMLLIElement>('[role="option"]')[index]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLUListElement>): void {
    if (items.length === 0) {
      return;
    }
    const last = items.length - 1;
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
      next = (activeIndex + step + items.length) % items.length;
    }
    event.preventDefault();
    onSelect(items[next].id);
    focusOption(next);
  }

  return (
    <div className="ci-graph__nodes">
      <p className="ci-graph__nodes-title" id="ci-graph-nodes-title">
        {`表示中のノード（${items.length}）`}
      </p>
      <ul
        ref={listRef}
        className="ci-graph__nodes-list"
        role="listbox"
        aria-label="表示中のノード一覧。図と同じノードをキーボードで選べる"
        onKeyDown={onKeyDown}
      >
        {items.map((item, index) => (
          <li
            key={item.id}
            role="option"
            aria-selected={item.id === selectedId}
            tabIndex={index === activeIndex ? 0 : -1}
            className={
              item.id === selectedId
                ? "ci-graph__node ci-graph__node--selected"
                : "ci-graph__node"
            }
            onClick={() => onSelect(item.id)}
          >
            <span className="ci-graph__node-label">{item.label}</span>
            <span className="ci-graph__node-kind">{item.kindLabel}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
