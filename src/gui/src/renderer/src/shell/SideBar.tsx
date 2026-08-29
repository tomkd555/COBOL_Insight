import type { ReactElement } from "react";
import { text } from "../text";
import { useWorkbench, type SideView } from "../state/workbenchStore";
import { Explorer } from "../views/explorer/Explorer";
import { Problems } from "../views/problems/Problems";
import { Placeholder } from "../editors/Placeholder";

export interface SideBarProps {
  onSelectFolder: () => void;
  onOpenAsset: (path: string, line: number | null) => void;
}

const TITLES: Readonly<Record<SideView, string>> = {
  explorer: text.sideBar.explorerTitle,
  search: text.sideBar.searchTitle,
  rules: text.sideBar.rulesTitle,
  problems: text.sideBar.problemsTitle,
};

/**
 * The side bar. It holds whichever view the activity bar selected; the views a later phase will fill
 * show the placeholder, so every activity entry leads somewhere.
 */
export function SideBar({ onSelectFolder, onOpenAsset }: SideBarProps): ReactElement {
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
          <Placeholder />
        )}
      </div>
    </aside>
  );
}
