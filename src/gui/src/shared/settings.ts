/**
 * The persisted GUI settings. Shared because main reads and writes the file while the renderer
 * restores and updates the values.
 *
 * Only what is worth surviving a restart is stored: the copybook search paths, the severity
 * threshold, the default encoding, and the shell's pane sizes. Values that mean something only
 * within one session (the search text, the selected asset, the folder open) are not stored.
 *
 * Normalisation here only fixes types. Whether a severity or an encoding name is one the renderer
 * knows is checked on restore, because that vocabulary lives in the renderer.
 */

/** The stored file's version. Bump it when the shape changes. */
export const APP_SETTINGS_VERSION = 2;

export interface AppSettings {
  /** Severity threshold for the problems view (high/medium/low/warning). */
  readonly severityThreshold: string;
  /** Default codepage to hand the engine when nothing else applies. */
  readonly defaultEncoding: string;
  /** Copybook search paths, passed to --copybook-path in this order. */
  readonly copybookPaths: readonly string[];
  /** Where `fix apply` writes the corrected sources. Empty means the engine's own default. */
  readonly fixOutDir: string;
  /** Shell pane sizes in pixels, keyed by pane id. Unknown keys are dropped on restore. */
  readonly paneSizes: Readonly<Record<string, number>>;
  /** The colour theme: "system", "dark" or "light". Empty and unknown values mean "system". */
  readonly theme: string;
}

export interface AppSettingsFile {
  readonly version: number;
  readonly settings: AppSettings;
}

/** The value used when nothing has been stored: every screen keeps its own initial state. */
export function emptyAppSettings(): AppSettings {
  return {
    severityThreshold: "",
    defaultEncoding: "",
    copybookPaths: [],
    fixOutDir: "",
    paneSizes: {},
    theme: "",
  };
}

/** Coerces a parsed value into the settings shape. Missing and ill-typed fields become empty. */
export function normalizeAppSettings(value: unknown): AppSettings {
  const source = asObject(value);
  const settings = asObject(source["settings"] ?? source);
  return {
    severityThreshold: asString(settings["severityThreshold"]),
    defaultEncoding: asString(settings["defaultEncoding"]),
    copybookPaths: asStringArray(settings["copybookPaths"]),
    fixOutDir: asString(settings["fixOutDir"]),
    paneSizes: asNumberRecord(settings["paneSizes"]),
    theme: asString(settings["theme"]),
  };
}

function asObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((element) => typeof element === "string") : [];
}

/** Keeps only the numeric entries; strings, NaN and infinities are dropped. */
function asNumberRecord(value: unknown): Record<string, number> {
  const result: Record<string, number> = {};
  for (const [key, element] of Object.entries(asObject(value))) {
    if (typeof element === "number" && Number.isFinite(element)) {
      result[key] = element;
    }
  }
  return result;
}
