import { useEffect, useRef, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../i18n/text";
import {
  CUSTOM_RULES_TAB_ID,
  isTabDirty,
  useWorkbench,
  useWorkbenchDispatch,
} from "../state/workbenchStore";
import { isCustomDirty, useRulesState } from "../state/rulesStore";

export interface EditorTabsProps {
  /** Closing goes through the shell, which asks before discarding unsaved edits. */
  onRequestClose: (id: string) => void;
}

/**
 * The tab strip. It is a tab list with roving tabindex: exactly one tab is in the tab order, and the
 * arrow keys move the selection, so a long strip does not have to be traversed with Tab.
 */
export function EditorTabs({ onRequestClose }: EditorTabsProps): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();
  const rules = useRulesState();
  const stripRef = useRef<HTMLDivElement | null>(null);

  /**
   * Whether the tab holds unsaved work. Source tabs keep theirs in the workbench store; the
   * custom-rule editor keeps its draft in the rules store, and it earns the same mark.
   */
  const dirtyTab = (id: string): boolean =>
    isTabDirty(workbench, id) || (id === CUSTOM_RULES_TAB_ID && isCustomDirty(rules));

  // A strip wider than the group scrolls; the selected tab is brought into view when it changes.
  useEffect(() => {
    const selected = stripRef.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    // jsdom has no scrollIntoView; the guard keeps the shell tests running.
    if (selected !== null && selected !== undefined && typeof selected.scrollIntoView === "function") {
      selected.scrollIntoView({ inline: "nearest", block: "nearest" });
    }
  }, [workbench.activeTabId]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      dispatch({ type: "STEP_TAB", step: event.key === "ArrowRight" ? 1 : -1 });
      // Keep the focus on the strip: the newly selected tab takes the tab stop on the next render.
      queueMicrotask(() => stripRef.current?.querySelector<HTMLElement>('[tabindex="0"]')?.focus());
    }
  };

  return (
    <div
      ref={stripRef}
      className="ci-tabs"
      role="tablist"
      aria-label={text.editor.tabs}
      onKeyDown={onKeyDown}
      data-testid="editor-tabs"
    >
      {workbench.tabs.map((tab) => {
        const selected = tab.id === workbench.activeTabId;
        const dirty = dirtyTab(tab.id);
        return (
          <div
            key={tab.id}
            className={`ci-tab${selected ? " ci-tab--active" : ""}`}
            data-testid={`tab-${tab.id}`}
          >
            <button
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={`tabpanel-${tab.id}`}
              id={`tabheader-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              className="ci-tab__label"
              onClick={() => dispatch({ type: "ACTIVATE_TAB", id: tab.id })}
              title={tab.path ?? tab.title}
            >
              {tab.title}
            </button>
            <button
              type="button"
              className={`ci-tab__close${dirty ? " ci-tab__close--dirty" : ""}`}
              aria-label={dirty ? `${tab.title} ${text.editor.unsaved}` : `${tab.title} ${text.editor.close}`}
              tabIndex={-1}
              onClick={() => onRequestClose(tab.id)}
              data-testid={`close-${tab.id}`}
            >
              <span className="ci-tab__dirty-dot" aria-hidden="true" data-testid={`dirty-${tab.id}`} />
              <span className="codicon codicon-close" aria-hidden="true" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
