import { useEffect, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { api, errorMessage } from "../../api";
import { CODEPAGES } from "../../../../shared/codepage";
import { SEVERITIES, type Severity } from "../../model/severity";
import { artifactSubdir, insideAssetFolder } from "../../model/artifactPaths";
import { useProject } from "../../state/projectStore";
import {
  toAppSettings,
  useSettings,
  useSettingsDispatch,
  type SettingsState,
} from "../../state/settingsStore";
import type { Notify } from "../../state/useShellStartup";
import { THEME_CHOICES, type ThemeChoice } from "../../theme";

export interface SettingsEditorProps {
  notify: Notify;
}

/** The part of the settings this screen edits. The pane sizes and the last folder are not here. */
interface Draft {
  readonly theme: ThemeChoice;
  readonly severityThreshold: Severity;
  readonly defaultEncoding: string;
  readonly copybookPaths: readonly string[];
  readonly fixOutDir: string;
}

function draftOf(settings: SettingsState): Draft {
  return {
    theme: settings.theme,
    severityThreshold: settings.severityThreshold,
    defaultEncoding: settings.defaultEncoding,
    copybookPaths: [...settings.copybookPaths],
    fixOutDir: settings.fixOutDir,
  };
}

const THEME_LABEL: Record<ThemeChoice, string> = {
  system: text.settings.themeSystem,
  dark: text.settings.themeDark,
  light: text.settings.themeLight,
};

/** Enter in a text field applies it, the way leaving it does. */
function blurOnEnter(event: KeyboardEvent<HTMLInputElement>): void {
  if (event.key === "Enter") {
    event.currentTarget.blur();
  }
}

/**
 * The settings screen. Every control applies as it is changed, as VS Code's own settings do, so the
 * screen has no save button and no unsaved state. A text field applies when it is left (or on
 * Enter), not on every keystroke: a path is not a value until it is typed out, and the file on disk
 * is written once per change rather than once per character.
 *
 * A copybook path that is not a directory is reported but still applied: the folder may be about to
 * be created. A fix output directory inside the asset folder is the one value that is refused — the
 * write-out must not land among the originals — and the stored setting keeps its last value.
 */
export function Settings({ notify }: SettingsEditorProps): ReactElement {
  const settings = useSettings();
  const dispatch = useSettingsDispatch();
  const project = useProject();
  const [draft, setDraft] = useState<Draft>(() => draftOf(settings));
  const [missing, setMissing] = useState<readonly string[]>([]);
  /** Whether a text field has been typed into since it was last applied. */
  const typed = useRef(false);

  // The stored settings arrive after the first render; the screen takes them once they are in.
  useEffect(() => {
    setDraft(draftOf(settings));
    // Only the arrival of the stored settings resets the screen; later edits are the user's own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings.restored]);

  useEffect(() => {
    let cancelled = false;
    const paths = draft.copybookPaths.filter((path) => path.trim() !== "");
    Promise.all(paths.map((path) => api().dirExists(path)))
      .then((flags) => {
        if (!cancelled) {
          setMissing(paths.filter((_path, index) => !flags[index]));
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMissing([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [draft.copybookPaths]);

  // The write-out must not land among the originals, so a fix output directory inside the asset
  // folder is refused: the field keeps what was typed and the stored setting keeps its last value.
  const fixOutDirInside = insideAssetFolder(draft.fixOutDir, project.inputDir);

  /** Applies a draft: the store takes it, and the file is written once. */
  const commit = (next: Draft): void => {
    const paths = next.copybookPaths.map((path) => path.trim()).filter((path) => path !== "");
    const fixOutDir = insideAssetFolder(next.fixOutDir, project.inputDir)
      ? settings.fixOutDir
      : next.fixOutDir.trim();
    dispatch({ type: "SET_THEME", theme: next.theme });
    dispatch({ type: "SET_THRESHOLD", threshold: next.severityThreshold });
    dispatch({ type: "SET_ENCODING", encoding: next.defaultEncoding });
    dispatch({ type: "SET_COPYBOOK_PATHS", paths });
    dispatch({ type: "SET_FIX_OUT_DIR", dir: fixOutDir });
    api()
      .writeSettings(toAppSettings({ ...settings, ...next, copybookPaths: paths, fixOutDir }))
      .catch((error: unknown) => notify(errorMessage(error), true));
  };

  /** A control that applies as it changes: the theme, the selects, the path list's buttons. */
  const edit = (patch: Partial<Draft>): void => {
    const next = { ...draft, ...patch };
    setDraft(next);
    commit(next);
  };

  /** A text field being typed into. It applies when it is left. */
  const type = (patch: Partial<Draft>): void => {
    typed.current = true;
    setDraft({ ...draft, ...patch });
  };

  const leave = (): void => {
    if (typed.current) {
      typed.current = false;
      commit(draft);
    }
  };

  const browseFixOutDir = (): void => {
    void api()
      .selectFolder()
      .then((dir) => {
        if (dir !== null) {
          edit({ fixOutDir: dir });
        }
      });
  };

  return (
    <div className="ci-settings" data-testid="settings">
      <fieldset className="ci-form">
        <legend className="ci-form__legend">{text.settings.theme}</legend>
        <div className="ci-chips" role="radiogroup" aria-label={text.settings.theme}>
          {THEME_CHOICES.map((choice) => (
            <label key={choice} className={`ci-chip${draft.theme === choice ? " ci-chip--on" : ""}`}>
              <input
                type="radio"
                name="theme"
                className="ci-visually-hidden"
                value={choice}
                checked={draft.theme === choice}
                onChange={() => edit({ theme: choice })}
                data-testid={`settings-theme-${choice}`}
              />
              {THEME_LABEL[choice]}
            </label>
          ))}
        </div>
      </fieldset>

      <label className="ci-form__field">
        <span className="ci-form__label">{text.settings.encoding}</span>
        <select
          className="ci-select"
          value={draft.defaultEncoding}
          onChange={(event) => edit({ defaultEncoding: event.target.value })}
          data-testid="settings-encoding"
        >
          <option value="">{text.settings.encodingAuto}</option>
          {CODEPAGES.map((codepage) => (
            <option key={codepage.value} value={codepage.value}>
              {codepage.label}
            </option>
          ))}
        </select>
      </label>

      <fieldset className="ci-form">
        <legend className="ci-form__legend">{text.settings.copybookPaths}</legend>
        {draft.copybookPaths.map((path, index) => (
          <div className="ci-settings__path" key={index}>
            <input
              className="ci-input"
              value={path}
              placeholder={text.settings.copybookPlaceholder}
              aria-label={`${text.settings.copybookPaths} ${index + 1}`}
              onChange={(event) =>
                type({
                  copybookPaths: draft.copybookPaths.map((current, at) =>
                    at === index ? event.target.value : current,
                  ),
                })
              }
              onBlur={leave}
              onKeyDown={blurOnEnter}
              data-testid={`settings-copybook-${index}`}
            />
            <button
              type="button"
              className="ci-button"
              aria-label={text.settings.copybookRemove}
              title={text.settings.copybookRemove}
              onClick={() =>
                edit({ copybookPaths: draft.copybookPaths.filter((_current, at) => at !== index) })
              }
              data-testid={`settings-copybook-remove-${index}`}
            >
              <span className="codicon codicon-trash" aria-hidden="true" />
            </button>
            {/* A note, not an error: the folder may be about to be created, and the path applies. */}
            {missing.includes(path.trim()) ? (
              <span className="ci-form__note">{text.settings.copybookMissing}</span>
            ) : null}
          </div>
        ))}
        <button
          type="button"
          className="ci-button"
          onClick={() => edit({ copybookPaths: [...draft.copybookPaths, ""] })}
          data-testid="settings-copybook-add"
        >
          {text.settings.copybookAdd}
        </button>
      </fieldset>

      <label className="ci-form__field">
        <span className="ci-form__label">{text.settings.threshold}</span>
        <select
          className="ci-select"
          value={draft.severityThreshold}
          onChange={(event) => edit({ severityThreshold: event.target.value as Severity })}
          data-testid="settings-threshold"
        >
          {SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {text.severity[severity]}
            </option>
          ))}
        </select>
      </label>

      <label className="ci-form__field">
        <span className="ci-form__label">{text.settings.fixOutDir}</span>
        <div className="ci-settings__path">
          <input
            className="ci-input"
            value={draft.fixOutDir}
            // Left empty, the write-out goes beside the project file; the placeholder names where.
            placeholder={artifactSubdir(project.outputPaths?.db, "fix")}
            onChange={(event) => type({ fixOutDir: event.target.value })}
            onBlur={leave}
            onKeyDown={blurOnEnter}
            data-testid="settings-fix-outdir"
          />
          <button
            type="button"
            className="ci-button"
            onClick={browseFixOutDir}
            data-testid="settings-fix-outdir-browse"
          >
            {text.settings.browse}
          </button>
        </div>
        {fixOutDirInside ? (
          <span className="ci-form__error">{text.settings.fixOutDirInside}</span>
        ) : null}
      </label>
    </div>
  );
}
