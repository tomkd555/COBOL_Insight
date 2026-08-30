import type { ReactElement } from "react";
import { text } from "../text";
import { artifactCount, useProject } from "../state/projectStore";
import { useWorkbenchDispatch } from "../state/workbenchStore";
import { useEditorStatus } from "../state/editorStatusStore";
import { codepageLabel } from "../../../shared/codepage";

/** A count, or a dash when the artefact was never fetched or could not be read. */
function countText(count: number | null): string {
  return count === null ? "—" : `${count}${text.status.unit}`;
}

/**
 * The status bar. It carries the counts of the three artefacts, and pressing the finding counts
 * opens the problems panel, so the numbers are a route to the detail rather than decoration.
 */
export function StatusBar(): ReactElement {
  const project = useProject();
  const dispatch = useWorkbenchDispatch();
  const status = useEditorStatus();
  const showProblems = (): void => dispatch({ type: "SHOW_PANEL", view: "problems" });

  return (
    <footer className="ci-statusbar" aria-label={text.status.label} data-testid="statusbar">
      <span className="ci-statusbar__item" data-testid="status-assets">
        {text.status.assets} {countText(artifactCount(project.inventory))}
      </span>
      <button
        type="button"
        className="ci-statusbar__item ci-statusbar__item--button"
        onClick={showProblems}
        data-testid="status-findings"
      >
        <span className="codicon codicon-warning" aria-hidden="true" />
        {text.status.findings} {countText(artifactCount(project.findings))}
      </button>
      <button
        type="button"
        className="ci-statusbar__item ci-statusbar__item--button"
        onClick={showProblems}
        data-testid="status-sql-findings"
      >
        {text.status.sqlFindings} {countText(artifactCount(project.sqlFindings))}
      </button>
      <div className="ci-statusbar__spacer" />
      {status === null ? null : (
        <>
          <span className="ci-statusbar__item" data-testid="status-caret">
            {text.status.caret(status.line, status.column)}
          </span>
          <span className="ci-statusbar__item" data-testid="status-codepage">
            {codepageLabel(status.codepage, text.explorer.codepageUnknown)}
            {status.detected ? `（${text.sourceView.detected}）` : ""}
          </span>
        </>
      )}
      <span className="ci-statusbar__item ci-statusbar__item--dim">
        {project.inputDir ?? text.status.noFolder}
      </span>
    </footer>
  );
}
