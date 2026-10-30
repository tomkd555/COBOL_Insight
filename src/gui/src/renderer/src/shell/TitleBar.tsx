import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { notAnalysed, reportReady, useProject } from "../state/projectStore";
import { customSavable, useRulesState } from "../state/rulesStore";
import {
  CUSTOM_RULES_TAB_ID,
  activeTabOf,
  useWorkbench,
  type TreeSelection,
} from "../state/workbenchStore";

/** The stages a run passes through, in order, indexed by ProjectState.runStage. */
const STAGE_NAMES: readonly string[] = [text.run.scan, text.run.lint];

export interface TitleBarProps {
  /** Analyses the run target: what the explorer has selected. */
  onRun: () => void;
  /** Analyses the whole asset folder, whatever is selected. */
  onRunAll: () => void;
  /** What the run button will analyse, as the explorer has it. Null while nothing is selected. */
  target: TreeSelection | null;
  onCancel: () => void;
  onSelectFolder: () => void;
}

/**
 * The title bar: the product name, the folder under analysis, and the run and cancel actions. The
 * run button becomes the cancel button while an analysis is in progress, so the two never contend
 * for the same click.
 *
 * The run button analyses one asset or one folder, so what it will analyse is named beside it; the
 * whole folder is a button of its own, which is also the way in when the side bar is closed and
 * nothing can be selected.
 *
 * The folder is both the name of what is being analysed and the control that switches it. With no
 * folder the same control carries the action's name, so the picker stays reachable from every
 * screen, including the ones that replace the welcome view.
 */
export function TitleBar({
  onRun,
  onRunAll,
  target,
  onCancel,
  onSelectFolder,
}: TitleBarProps): ReactElement {
  const project = useProject();
  const running = project.mode === "running";
  // One filled button to a screen. Where the editor in front shows its own, the run button is drawn
  // outlined: the welcome view until the first analysis, the report once there is an analysis of the
  // whole folder to report on, and the custom-rule editor while it has something to save.
  const active = activeTabOf(useWorkbench());
  const rules = useRulesState();
  const editorLeads =
    active === null
      ? notAnalysed(project)
      : active.kind === "report"
        ? reportReady(project)
        : active.id === CUSTOM_RULES_TAB_ID && customSavable(rules);

  return (
    <header className="ci-titlebar" data-testid="titlebar">
      <h1 className="ci-titlebar__name">{text.app.name}</h1>
      <button
        type="button"
        className="ci-titlebar__folder"
        // With a folder open the button shows its path, so what a press does is said in its name and
        // on hover, with the whole of a path the bar had to cut.
        aria-label={
          project.inputDir === null
            ? text.command.selectFolder
            : text.command.selectFolderAt(project.inputDir)
        }
        title={project.inputDir === null ? undefined : text.command.selectFolderAt(project.inputDir)}
        onClick={onSelectFolder}
        disabled={running}
        data-testid="select-folder"
      >
        <span className="codicon codicon-folder-opened" aria-hidden="true" />
        <span className="ci-titlebar__folder-path">
          {project.inputDir ?? text.command.selectFolder}
        </span>
        {/* The path names the folder; the chevron says the control picks another one. */}
        {project.inputDir === null ? null : (
          <span className="codicon codicon-chevron-down" aria-hidden="true" />
        )}
      </button>
      <div className="ci-titlebar__spacer" />
      {running ? (
        <>
          <span className="ci-titlebar__stage" data-testid="run-stage">
            {STAGE_NAMES[project.runStage - 1]}
          </span>
          {/* Indeterminate: a stage's length is unknown, and a run may have one stage or two. */}
          <progress className="ci-titlebar__progress" aria-label={text.app.running} />
          <button
            type="button"
            className="ci-button ci-titlebar__action"
            onClick={onCancel}
            data-testid="cancel-run"
          >
            <span className="codicon codicon-debug-stop" aria-hidden="true" />
            {text.app.cancel}
          </button>
        </>
      ) : (
        <>
          {target === null ? null : (
            <span
              className="ci-titlebar__target"
              title={text.app.runTarget(target.path, target.kind)}
              data-testid="run-target"
            >
              {text.app.runTarget(target.path, target.kind)}
            </span>
          )}
          <button
            type="button"
            className="ci-button ci-titlebar__action"
            onClick={onRunAll}
            disabled={project.inputDir === null}
            data-testid="run-analysis-all"
          >
            {text.command.runAll}
          </button>
          <button
            type="button"
            className={`ci-button${editorLeads ? "" : " ci-button--primary"} ci-titlebar__action`}
            onClick={onRun}
            disabled={project.inputDir === null || target === null}
            data-testid="run-analysis"
          >
            <span className="codicon codicon-play" aria-hidden="true" />
            {text.app.run}
          </button>
        </>
      )}
    </header>
  );
}
