import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { useWorkbench, type SideView } from "../state/workbenchStore";
import type { Notify } from "../state/useShellStartup";
import { Explorer } from "../views/explorer/Explorer";
import { Problems } from "../views/problems/Problems";
import { Rules } from "../views/rules/Rules";

export interface SideBarProps {
  onSelectFolder: () => void;
  onOpenAsset: (path: string, line: number | null) => void;
  /** How a failed write reaches the user. */
  notify: Notify;
}

const TITLES: Readonly<Record<SideView, string>> = {
  explorer: text.sideBar.explorerTitle,
  rules: text.sideBar.rulesTitle,
  problems: text.sideBar.problemsTitle,
};

/**
 * The side bar. It holds whichever view the activity bar selected; the views a later phase will fill
 * show the placeholder, so every activity entry leads somewhere.
 */
export function SideBar({ onSelectFolder, onOpenAsset, notify }: SideBarProps): ReactElement {
  const workbench = useWorkbench();
  const view = workbench.sideView;

  return (
    <aside className="ci-sidebar" aria-label={text.sideBar.label} data-testid="sidepanel">
      <h2 className="ci-sidebar__title">{TITLES[view]}</h2>
      <div className="ci-sidebar__body">
        {view === "explorer" ? (
          <Explorer onSelectFolder={onSelectFolder} onOpenAsset={onOpenAsset} />
        ) : view === "problems" ? (
          <Problems onOpenAsset={onOpenAsset} compact />
        ) : (
          <Rules notify={notify} />
        )}
      </div>
    </aside>
  );
}
