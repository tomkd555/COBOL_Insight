import type { ReactElement } from "react";
import { text } from "../i18n/text";
import { useProject } from "../state/projectStore";

/** The stages a run passes through, in order, indexed by ProjectState.runStage. */
const STAGE_NAMES: readonly string[] = [text.run.scan, text.run.lint, text.run.sqlLint];

export interface TitleBarProps {
  onRun: () => void;
  onCancel: () => void;
  onSelectFolder: () => void;
}

/**
 * The title bar: the product name, the folder under analysis, and the run and cancel actions. The
 * run button becomes the cancel button while an analysis is in progress, so the two never contend
 * for the same click.
 *
 * The folder is both the name of what is being analysed and the control that switches it. With no
 * folder the same control carries the action's name, so the picker stays reachable from every
 * screen, including the ones that replace the welcome view.
 */
export function TitleBar({ onRun, onCancel, onSelectFolder }: TitleBarProps): ReactElement {
  const project = useProject();
  const running = project.mode === "running";

  return (
    <header className="ci-titlebar" data-testid="titlebar">
      <span className="ci-titlebar__name">{text.app.name}</span>
      <button
        type="button"
        className="ci-titlebar__folder"
        aria-label={text.command.selectFolder}
        onClick={onSelectFolder}
        disabled={running}
        data-testid="select-folder"
      >
        <span className="codicon codicon-folder-opened" aria-hidden="true" />
        <span className="ci-titlebar__folder-path">
          {project.inputDir ?? text.command.selectFolder}
        </span>
      </button>
      <div className="ci-titlebar__spacer" />
      {running ? (
        <>
          <span className="ci-titlebar__stage" data-testid="run-stage">
            {STAGE_NAMES[project.runStage - 1]}
          </span>
          {/* Indeterminate: a stage's length is unknown, and a run may have one stage or three. */}
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
        <button
          type="button"
          className="ci-button ci-button--primary ci-titlebar__action"
          onClick={onRun}
          disabled={project.inputDir === null}
          data-testid="run-analysis"
        >
          <span className="codicon codicon-play" aria-hidden="true" />
          {text.app.run}
        </button>
      )}
    </header>
  );
}
