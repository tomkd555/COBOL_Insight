import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { useWorkbench, useWorkbenchDispatch, type PanelView } from "../state/workbenchStore";
import { Problems } from "../views/problems/Problems";
import { Output } from "../views/output/Output";

export interface PanelProps {
  onOpenAsset: (path: string, line: number | null) => void;
  onShowFix: (path: string) => void;
}

const TABS: readonly { view: PanelView; label: string }[] = [
  { view: "problems", label: text.panel.problems },
  { view: "output", label: text.panel.output },
];

/**
 * The bottom panel: the problems table and the run log. Its tab strip is a tab list with roving
 * tabindex, so the arrow keys move between the two views.
 */
export function Panel({ onOpenAsset, onShowFix }: PanelProps): ReactElement {
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();

  return (
    <section className="ci-panel" aria-label={text.panel.label} data-testid="bottompanel">
      <div className="ci-panel__header">
        <div
          className="ci-panel__tabs"
          role="tablist"
          aria-label={text.panel.label}
          onKeyDown={(event) => {
            const step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
            if (step === 0) return;
            event.preventDefault();
            const index = TABS.findIndex((tab) => tab.view === workbench.panelView);
            const next = TABS[(index + step + TABS.length) % TABS.length];
            dispatch({ type: "SHOW_PANEL", view: next.view });
          }}
        >
          {TABS.map((tab) => {
            const selected = workbench.panelView === tab.view;
            return (
              <button
                key={tab.view}
                type="button"
                role="tab"
                aria-selected={selected}
                aria-controls={`panelview-${tab.view}`}
                tabIndex={selected ? 0 : -1}
                className={`ci-panel__tab${selected ? " ci-panel__tab--active" : ""}`}
                onClick={() => dispatch({ type: "SHOW_PANEL", view: tab.view })}
                data-testid={`panel-tab-${tab.view}`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
        <button
          type="button"
          className="ci-panel__close"
          aria-label={text.panel.close}
          onClick={() => dispatch({ type: "TOGGLE_PANEL" })}
          data-testid="panel-close"
        >
          <span className="codicon codicon-close" aria-hidden="true" />
        </button>
      </div>
      <div
        className="ci-panel__body"
        role="tabpanel"
        id={`panelview-${workbench.panelView}`}
        data-testid={`panelview-${workbench.panelView}`}
      >
        {workbench.panelView === "problems" ? (
          <Problems onOpenAsset={onOpenAsset} onShowFix={onShowFix} />
        ) : (
          <Output />
        )}
      </div>
    </section>
  );
}
