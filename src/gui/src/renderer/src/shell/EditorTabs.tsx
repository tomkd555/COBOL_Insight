import { useRef, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../i18n/text";
import { isTabDirty, useWorkbench, useWorkbenchDispatch } from "../state/workbenchStore";

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
  const stripRef = useRef<HTMLDivElement | null>(null);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      dispatch({ type: "STEP_TAB", step: event.key === "ArrowRight" ? 1 : -1 });
      // Keep the focus on the strip: the newly selected tab takes the tab stop on the next render.
      queueMicrotask(() => stripRef.current?.querySelector<HTMLElement>('[tabindex="0"]')?.focus());
      return;
    }
    if (event.key === "Delete" && workbench.activeTabId !== null) {
      event.preventDefault();
      onRequestClose(workbench.activeTabId);
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
        const dirty = isTabDirty(workbench, tab.id);
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
              {dirty ? (
                <span
                  className="ci-tab__dirty"
                  aria-label={text.editor.unsaved}
                  data-testid={`dirty-${tab.id}`}
                />
              ) : null}
            </button>
            <button
              type="button"
              className="ci-tab__close"
              aria-label={`${tab.title} ${text.editor.close}`}
              tabIndex={-1}
              onClick={() => onRequestClose(tab.id)}
              data-testid={`close-${tab.id}`}
            >
              <span className="codicon codicon-close" aria-hidden="true" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
