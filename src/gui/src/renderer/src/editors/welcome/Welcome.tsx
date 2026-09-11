import type { ReactElement } from "react";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { PALETTE_CHORD } from "../../state/keybindings";

export interface WelcomeProps {
  onSelectFolder: () => void;
  /** Analyses the whole asset folder. */
  onRunAll: () => void;
}

/**
 * What the editor area shows with no tab open. It offers the one action that gets the user started.
 *
 * Once a folder is open, opening it is redundant with the title bar, but running the analysis is
 * not yet offered anywhere else on this screen, so that action takes its place until a run finishes.
 * It analyses the whole folder: this screen is shown with no tab open, which is where the user has
 * chosen nothing to analyse yet, and choosing an asset in the tree opens it and replaces this view.
 */
export function Welcome({ onSelectFolder, onRunAll }: WelcomeProps): ReactElement {
  const project = useProject();
  // Opening a folder only scans it (no auto-analyse); the lint findings are what says a run has
  // actually finished, whether or not the scan itself already moved the mode past "empty".
  const notAnalysed = project.findings.status === "none" && project.sqlFindings.status === "none";

  return (
    <div className="ci-welcome" data-testid="welcome">
      {project.inputDir === null ? (
        <button
          type="button"
          className="ci-button ci-button--primary"
          onClick={onSelectFolder}
          data-testid="welcome-select-folder"
        >
          {text.welcome.selectFolder}
        </button>
      ) : notAnalysed ? (
        <button
          type="button"
          className="ci-button ci-button--primary"
          disabled={project.mode === "running"}
          onClick={onRunAll}
          data-testid="welcome-run"
        >
          {text.command.runAll}
        </button>
      ) : null}
      <p className="ci-welcome__hint">
        {text.welcome.shortcutHint} <kbd>{PALETTE_CHORD.label}</kbd>
      </p>
    </div>
  );
}
