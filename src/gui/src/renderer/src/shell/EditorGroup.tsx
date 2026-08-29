import type { ReactElement } from "react";
import { activeTabOf, useWorkbench, type WorkbenchTab } from "../state/workbenchStore";
import { EditorTabs } from "./EditorTabs";
import { Welcome } from "../editors/welcome/Welcome";
import { SourceEditor } from "../editors/source/SourceEditor";
import { Placeholder } from "../editors/Placeholder";

export interface EditorGroupProps {
  onRequestClose: (id: string) => void;
  onSelectFolder: () => void;
}

/** Picks the editor for a tab. Kinds a later phase will fill get the placeholder for now. */
function editorFor(tab: WorkbenchTab): ReactElement {
  if (tab.kind === "source" && tab.path !== null) {
    return <SourceEditor path={tab.path} line={tab.line} />;
  }
  return <Placeholder />;
}

/**
 * The editor area: the tab strip and the body of the selected tab. With no tab open it shows the
 * welcome view, so the area is never blank.
 *
 * Only the selected tab's body is rendered. That is why the unsaved text lives in the workbench
 * store rather than inside an editor, which would lose it on every switch.
 */
export function EditorGroup({ onRequestClose, onSelectFolder }: EditorGroupProps): ReactElement {
  const workbench = useWorkbench();
  const active = activeTabOf(workbench);

  return (
    <section className="ci-editorgroup" data-testid="editorarea">
      <EditorTabs onRequestClose={onRequestClose} />
      {active === null ? (
        <Welcome onSelectFolder={onSelectFolder} />
      ) : (
        <div
          className="ci-editorgroup__body"
          role="tabpanel"
          id={`tabpanel-${active.id}`}
          aria-labelledby={`tabheader-${active.id}`}
          data-testid={`tabpanel-${active.id}`}
        >
          {editorFor(active)}
        </div>
      )}
    </section>
  );
}
