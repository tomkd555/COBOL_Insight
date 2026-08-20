import { useRef, type KeyboardEvent, type ReactElement } from "react";
import { useWorkbench, useWorkbenchDispatch, type WorkbenchTab } from "../state/workbenchStore";

/** 矢印キーによる移動量(1=次、-1=前)。Home・End は端へ移す。 */
const STEP_KEYS: Readonly<Record<string, number>> = {
  ArrowRight: 1,
  ArrowLeft: -1,
};

/**
 * 本文領域の上に並ぶタブ帯。ARIA タブパターンに従い、選択中のタブへ Tab 停止を集約する
 * (roving tabindex)。閉じるボタンはタブの中に置き、タブ自体の押下と取り違えないよう
 * クリックの伝播を止める。
 */
export function EditorTabs(): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();
  const listRef = useRef<HTMLDivElement>(null);
  const { tabs, activeTabId } = workbench;

  function focusTab(index: number): void {
    const items = listRef.current?.querySelectorAll<HTMLElement>('[role="tab"]');
    items?.[index]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    if (tabs.length === 0) return;
    const current = Math.max(
      tabs.findIndex((tab) => tab.id === activeTabId),
      0,
    );
    let next: number;
    if (event.key === "Home") {
      next = 0;
    } else if (event.key === "End") {
      next = tabs.length - 1;
    } else {
      const step = STEP_KEYS[event.key];
      if (step === undefined) return;
      // 端では反対の端へ回す。
      next = (current + step + tabs.length) % tabs.length;
    }
    event.preventDefault();
    dispatch({ type: "ACTIVATE_TAB", id: tabs[next].id });
    focusTab(next);
  }

  function tabLabel(tab: WorkbenchTab): string {
    return tab.path === null ? tab.title : tab.path;
  }

  return (
    <div
      ref={listRef}
      className="ci-tabstrip"
      role="tablist"
      aria-label="開いているタブ"
      onKeyDown={onKeyDown}
    >
      {tabs.map((tab) => {
        const selected = tab.id === activeTabId;
        const dirty = workbench.dirtyTabIds.includes(tab.id);
        return (
          <div
            key={tab.id}
            role="tab"
            id={`ci-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`ci-tabpanel-${tab.id}`}
            aria-label={tabLabel(tab)}
            tabIndex={selected ? 0 : -1}
            data-testid={`tab-${tab.id}`}
            className={selected ? "ci-tab ci-tab--active" : "ci-tab"}
            onClick={() => dispatch({ type: "ACTIVATE_TAB", id: tab.id })}
            onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                dispatch({ type: "ACTIVATE_TAB", id: tab.id });
              }
            }}
          >
            {dirty ? (
              <span className="ci-tab__dirty" aria-hidden="true">
                ●
              </span>
            ) : null}
            <span className="ci-tab__title">{tab.title}</span>
            <button
              type="button"
              className="ci-tab__close"
              aria-label={`${tab.title} を閉じる`}
              tabIndex={-1}
              onClick={(event) => {
                event.stopPropagation();
                dispatch({ type: "CLOSE_TAB", id: tab.id });
              }}
            >
              ×
            </button>
          </div>
        );
      })}
    </div>
  );
}
