import { useEffect, useState, type ReactElement } from "react";
import { text } from "../../text";
import { api, errorMessage } from "../../api";
import { CODEPAGES } from "../../../../shared/codepage";
import { SEVERITIES, type Severity } from "../../model/severity";
import { artifactSubdir } from "../../model/artifactPaths";
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

function same(left: Draft, right: Draft): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

/**
 * The settings screen. Edits are held until they are saved, so a half-typed copybook path is not
 * handed to the next analysis.
 *
 * A copybook path that is not a directory is reported but does not stop the save: the folder may be
 * about to be created, and refusing would lose every other setting on the screen along with it.
 */
export function Settings({ notify }: SettingsEditorProps): ReactElement {
  const settings = useSettings();
  const dispatch = useSettingsDispatch();
  const project = useProject();
  const [draft, setDraft] = useState<Draft>(() => draftOf(settings));
  const [saved, setSaved] = useState(false);
  const [missing, setMissing] = useState<readonly string[]>([]);

  // The stored settings arrive after the first render; the screen takes them once they are in.
  useEffect(() => {
    setDraft(draftOf(settings));
    setSaved(false);
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

  const edit = (patch: Partial<Draft>): void => {
    setDraft((current) => ({ ...current, ...patch }));
    setSaved(false);
  };

  const dirty = !same(draft, draftOf(settings));

  const save = (): void => {
    const paths = draft.copybookPaths.map((path) => path.trim()).filter((path) => path !== "");
    dispatch({ type: "SET_THEME", theme: draft.theme });
    dispatch({ type: "SET_THRESHOLD", threshold: draft.severityThreshold });
    dispatch({ type: "SET_ENCODING", encoding: draft.defaultEncoding });
    dispatch({ type: "SET_COPYBOOK_PATHS", paths });
    dispatch({ type: "SET_FIX_OUT_DIR", dir: draft.fixOutDir.trim() });
    api()
      .writeSettings(
        toAppSettings({
          ...settings,
          ...draft,
          copybookPaths: paths,
          fixOutDir: draft.fixOutDir.trim(),
        }),
      )
      .then(() => {
        setDraft((current) => ({ ...current, copybookPaths: paths, fixOutDir: current.fixOutDir.trim() }));
        setSaved(true);
      })
      .catch((error: unknown) => notify(errorMessage(error), true));
  };

  return (
    <div className="ci-settings" data-testid="settings">
      <h2 className="ci-settings__title">{text.settings.title}</h2>

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
        <span className="ci-form__note">{text.settings.encodingNote}</span>
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
                edit({
                  copybookPaths: draft.copybookPaths.map((current, at) =>
                    at === index ? event.target.value : current,
                  ),
                })
              }
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
            {missing.includes(path.trim()) ? (
              <span className="ci-form__error">{text.settings.copybookMissing}</span>
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
        <span className="ci-form__note">{text.settings.thresholdNote}</span>
      </label>

      <label className="ci-form__field">
        <span className="ci-form__label">{text.settings.fixOutDir}</span>
        <input
          className="ci-input"
          value={draft.fixOutDir}
          // Left empty, the write-out goes beside the project file; the placeholder names where.
          placeholder={artifactSubdir(project.outputPaths?.db, "fix")}
          onChange={(event) => edit({ fixOutDir: event.target.value })}
          data-testid="settings-fix-outdir"
        />
        <span className="ci-form__note">{text.settings.fixOutDirNote}</span>
      </label>

      <div className="ci-settings__actions">
        <button
          type="button"
          className="ci-button ci-button--primary"
          disabled={!dirty}
          onClick={save}
          data-testid="settings-save"
        >
          {text.settings.save}
        </button>
        {dirty ? <span className="ci-settings__state">{text.settings.dirty}</span> : null}
        {saved && !dirty ? (
          <span className="ci-settings__state" role="status">
            {text.settings.saved}
          </span>
        ) : null}
      </div>
    </div>
  );
}
