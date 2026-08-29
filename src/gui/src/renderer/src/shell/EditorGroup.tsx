import type { ReactElement } from "react";
import { activeTabOf, useWorkbench, type WorkbenchTab } from "../state/workbenchStore";
import type { Notify } from "../state/useShellStartup";
import { EditorTabs } from "./EditorTabs";
import { Welcome } from "../editors/welcome/Welcome";
import { SourceEditor } from "../editors/source/SourceEditor";
import { RuleDetail } from "../editors/rules/RuleDetail";
import { CustomRules } from "../editors/rules/CustomRules";
import { Settings } from "../editors/settings/Settings";
import { FixDiff } from "../editors/diff/FixDiff";
import { TranspilePane } from "../editors/transpile/TranspilePane";
import { Placeholder } from "../editors/Placeholder";

export interface EditorGroupProps {
  onRequestClose: (id: string) => void;
  onSelectFolder: () => void;
  /** How a failed write reaches the user. */
  notify: Notify;
  /** Opens the fix proposal for one asset, from the quick fix on a finding that has one. */
  onShowFix: (path: string) => void;
}

/**
 * The editor area: the tab strip and the body of the selected tab. With no tab open it shows the
 * welcome view, so the area is never blank.
 *
 * Only the selected tab's body is rendered. That is why the unsaved text lives in the workbench
 * store rather than inside an editor, which would lose it on every switch.
 *
 * The source editor is rendered as the same element for every source tab, so switching between two
 * assets keeps one Monaco instance and changes only its model — which is what carries the undo stack
 * and any unsaved edit across the switch.
 */
export function EditorGroup({
  onRequestClose,
  onSelectFolder,
  notify,
  onShowFix,
}: EditorGroupProps): ReactElement {
  const workbench = useWorkbench();
  const active = activeTabOf(workbench);

  /** Picks the editor for a tab. Kinds a later phase will fill get the placeholder for now. */
  const editorFor = (tab: WorkbenchTab): ReactElement => {
    if (tab.kind === "source" && tab.path !== null) {
      return <SourceEditor path={tab.path} line={tab.line} onShowFix={onShowFix} />;
    }
    if (tab.kind === "fix" && tab.path !== null) {
      return <FixDiff path={tab.path} onNotify={notify} />;
    }
    if (tab.kind === "transpile" && tab.path !== null) {
      return <TranspilePane path={tab.path} />;
    }
    // A rules tab either describes one rule (its id is in `path`) or edits the user-defined rules.
    if (tab.kind === "rules") {
      return tab.path === null ? <CustomRules notify={notify} /> : <RuleDetail ruleId={tab.path} />;
    }
    if (tab.kind === "settings") {
      return <Settings notify={notify} />;
    }
    return <Placeholder />;
  };

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
