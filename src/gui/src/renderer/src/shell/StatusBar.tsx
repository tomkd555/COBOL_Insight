import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { artifactCount, useProject, useProjectDispatch } from "../state/projectStore";
import { activeTabOf, useWorkbench, useWorkbenchDispatch } from "../state/workbenchStore";
import { useEditorStatus } from "../state/editorStatusStore";
import { CODEPAGES, codepageLabel } from "../../../shared/codepage";

/** The lint and SQL findings as one number; null until a run has produced one of the two. */
function findingCount(code: number | null, sql: number | null): number | null {
  return code === null && sql === null ? null : (code ?? 0) + (sql ?? 0);
}

/**
 * The status bar. It carries the finding count — a route into the panel that holds the detail — the
 * caret position, and the codepage the open asset was decoded with, which is also where the asset is
 * reopened under another codepage, as in VS Code. The asset count is the explorer's own business.
 */
export function StatusBar(): ReactElement {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const dispatch = useWorkbenchDispatch();
  const workbench = useWorkbench();
  const status = useEditorStatus();
  const active = activeTabOf(workbench);
  const sourcePath = active?.kind === "source" ? active.path : null;
  const lintAndSql = findingCount(artifactCount(project.findings), artifactCount(project.sqlFindings));
  // The problems panel also lists the save-time reparse findings; the count must match what it shows.
  const findings = lintAndSql === null ? null : lintAndSql + project.saveFindings.length;

  return (
    <footer className="ci-statusbar" aria-label={text.status.label} data-testid="statusbar">
      {findings === null ? null : (
        <button
          type="button"
          className="ci-statusbar__item ci-statusbar__item--button"
          onClick={() => dispatch({ type: "SHOW_PANEL", view: "problems" })}
          data-testid="status-findings"
        >
          <span className="codicon codicon-warning" aria-hidden="true" />
          {text.status.findings} {findings}
          {text.status.unit}
        </button>
      )}
      <div className="ci-statusbar__spacer" />
      {status === null ? null : (
        <span className="ci-statusbar__item" data-testid="status-caret">
          {text.status.caret(status.line, status.column)}
        </span>
      )}
      {sourcePath === null ? null : (
        // The codepage is also the control that reopens the asset under another one: the first
        // option is the codepage now in force, and choosing any other decodes the file again. It is
        // there whenever a source tab is, because a file whose decode failed is exactly the one that
        // has to be reopened under another codepage.
        <select
          className="ci-statusbar__item ci-statusbar__item--button"
          aria-label={text.command.reopenWithEncoding}
          title={text.command.reopenWithEncoding}
          value={project.codepageOverrides[sourcePath] ?? ""}
          onChange={(event) =>
            projectDispatch({
              type: "SET_CODEPAGE",
              path: sourcePath,
              charset: event.target.value,
            })
          }
          data-testid="status-codepage"
        >
          <option value="">
            {status === null
              ? text.explorer.codepageUnknown
              : codepageLabel(status.codepage, text.explorer.codepageUnknown)}
            {status?.detected === true ? `（${text.sourceView.detected}）` : ""}
          </option>
          {CODEPAGES.map((choice) => (
            <option key={choice.value} value={choice.value}>
              {choice.label}
            </option>
          ))}
        </select>
      )}
    </footer>
  );
}
