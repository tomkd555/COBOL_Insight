import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { useWorkbench, type SideView } from "../state/workbenchStore";
import type { Notify } from "../state/useShellStartup";
import { Explorer } from "../views/explorer/Explorer";
import { Rules } from "../views/rules/Rules";

export interface SideBarProps {
  onOpenAsset: (path: string, line: number | null) => void;
  /** How a failed write reaches the user. */
  notify: Notify;
}

const TITLES: Readonly<Record<SideView, string>> = {
  explorer: text.sideBar.explorerTitle,
  rules: text.sideBar.rulesTitle,
};

/** The side bar. It holds whichever of the two views the activity bar selected. */
export function SideBar({ onOpenAsset, notify }: SideBarProps): ReactElement {
  const workbench = useWorkbench();
  const view = workbench.sideView;

  return (
    <aside className="ci-sidebar" aria-label={text.sideBar.label} data-testid="sidepanel">
      <h2 className="ci-sidebar__title">{TITLES[view]}</h2>
      <div className="ci-sidebar__body">
        {view === "explorer" ? (
          <Explorer onOpenAsset={onOpenAsset} />
        ) : (
          <Rules notify={notify} />
        )}
      </div>
    </aside>
  );
}
