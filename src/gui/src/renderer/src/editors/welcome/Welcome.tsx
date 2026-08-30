import type { ReactElement } from "react";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { PALETTE_CHORD } from "../../state/keybindings";

export interface WelcomeProps {
  onSelectFolder: () => void;
}

/**
 * What the editor area shows with no tab open. It offers the one action that gets the user started
 * and, when there is one, names the folder from the previous session.
 *
 * Once a folder is open, that action and the folder name it would repeat are both redundant with the
 * title bar, so only the palette hint remains.
 */
export function Welcome({ onSelectFolder }: WelcomeProps): ReactElement {
  const settings = useSettings();
  const project = useProject();

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
      ) : null}
      {settings.lastInputDir === "" || settings.lastInputDir === project.inputDir ? null : (
        <p className="ci-welcome__recent">
          <span className="ci-welcome__recent-label">{text.welcome.recent}</span>
          <span className="ci-welcome__recent-path">{settings.lastInputDir}</span>
        </p>
      )}
      <p className="ci-welcome__hint">
        {text.welcome.shortcutHint} <kbd>{PALETTE_CHORD.label}</kbd>
      </p>
    </div>
  );
}
