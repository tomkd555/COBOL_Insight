import { useRef, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../text";
import { useWorkbench, useWorkbenchDispatch, type SideView } from "../state/workbenchStore";

/** The activity bar entries, in order. Icons are Monaco's codicons; no icon package is involved. */
const ENTRIES: readonly { view: SideView; icon: string; label: string }[] = [
  { view: "explorer", icon: "codicon-files", label: text.activity.explorer },
  { view: "search", icon: "codicon-search", label: text.activity.search },
  { view: "rules", icon: "codicon-checklist", label: text.activity.rules },
  { view: "problems", icon: "codicon-warning", label: text.activity.problems },
];

/**
 * The activity bar. It behaves as a tab list over the side bar views, with roving tabindex: one
 * button is in the tab order and the arrow keys move between them, so reaching the last entry does
 * not cost four presses of Tab.
 */
export function ActivityBar(): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();
  const listRef = useRef<HTMLDivElement | null>(null);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const step = event.key === "ArrowDown" ? 1 : event.key === "ArrowUp" ? -1 : 0;
    if (step === 0) {
      return;
    }
    event.preventDefault();
    const buttons = [...(listRef.current?.querySelectorAll<HTMLButtonElement>("button") ?? [])];
    const current = buttons.findIndex((button) => button === document.activeElement);
    buttons[(Math.max(current, 0) + step + buttons.length) % buttons.length]?.focus();
  };

  return (
    <div
      ref={listRef}
      className="ci-activitybar"
      role="tablist"
      aria-orientation="vertical"
      aria-label={text.activity.label}
      onKeyDown={onKeyDown}
      data-testid="activitybar"
    >
      {ENTRIES.map((entry) => {
        const selected = workbench.sideVisible && workbench.sideView === entry.view;
        return (
          <button
            key={entry.view}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-label={entry.label}
            title={entry.label}
            // Roving tabindex: only the selected entry is reachable with Tab.
            tabIndex={selected ? 0 : -1}
            className={`ci-activitybar__item${selected ? " ci-activitybar__item--active" : ""}`}
            onClick={() => dispatch({ type: "SHOW_SIDE", view: entry.view })}
            data-testid={`activity-${entry.view}`}
          >
            <span className={`codicon ${entry.icon}`} aria-hidden="true" />
          </button>
        );
      })}
    </div>
  );
}
