import { useRef, type KeyboardEvent, type ReactElement } from "react";
import type { ScreenId, ScreenTab } from "./screens";

export interface TabBarProps {
  tabs: readonly ScreenTab[];
  activeId: ScreenId;
  onSelect: (id: ScreenId) => void;
}

/** 矢印キーによる移動量(1=次、-1=前)。Home/End は端へ移す。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowRight: 1,
  ArrowDown: 1,
  ArrowLeft: -1,
  ArrowUp: -1,
};

/**
 * タブバー。8画面を横並びのタブとして提示する。ARIA タブパターンに従い、選択タブは
 * aria-selected=true・上辺2px青ボーダー・太字で示す。roving tabindex でキーボード操作の焦点を
 * アクティブタブに集約し、矢印キーでタブを移す(Home・End で端へ移す)。
 */
export function TabBar({ tabs, activeId, onSelect }: TabBarProps): ReactElement {
  const listRef = useRef<HTMLDivElement>(null);

  /** タブバー内の index 番目のタブへ焦点を移す。 */
  function focusTab(index: number): void {
    const items = listRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    items?.[index]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const current = tabs.findIndex((tab) => tab.id === activeId);
    const last = tabs.length - 1;
    let next: number;
    if (event.key === "Home") {
      next = 0;
    } else if (event.key === "End") {
      next = last;
    } else {
      const step = STEP_KEYS[event.key];
      if (step === undefined) return;
      // 端では反対の端へ回す。
      next = (current + step + tabs.length) % tabs.length;
    }
    event.preventDefault();
    onSelect(tabs[next].id);
    focusTab(next);
  }

  return (
    <div ref={listRef} className="ci-tabbar" role="tablist" aria-label="画面切り替え" onKeyDown={onKeyDown}>
      {tabs.map((tab) => {
        const selected = tab.id === activeId;
        const classes = ["ci-tab"];
        if (selected) classes.push("ci-tab--active");
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`ci-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`ci-screen-${tab.id}`}
            tabIndex={selected ? 0 : -1}
            className={classes.join(" ")}
            onClick={() => onSelect(tab.id)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
