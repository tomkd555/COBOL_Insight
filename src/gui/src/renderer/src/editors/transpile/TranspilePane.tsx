import { useEffect, useMemo, useState, type ReactElement } from "react";
import type {
  LineMapEntry,
  TranspileGeneratedFile,
  TranspileLanguage,
} from "../../../../shared/ipc";
import { api, engineFailure, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { viewRun } from "../../state/engineGate";
import { engineBusy, structureScanDue, useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { sourceTabId } from "../../state/workbenchStore";
import { artifactSubdir } from "../../model/artifactPaths";
import { cobolLineFor, entriesOf, fileOf, generatedLineFor, languagesOf } from "../../model/lineMap";
import { openDocument } from "../../model/openDocuments";
import { languageIdFor } from "../../vendor/monarch";
import { SideBySide } from "./SideBySide";

export interface TranspilePaneProps {
  /** The COBOL asset's path relative to the asset folder. */
  path: string;
}

interface Artifacts {
  cobol: string;
  files: readonly TranspileGeneratedFile[];
  lineMap: readonly LineMapEntry[];
}

type Load =
  | { status: "loading" }
  | { status: "ready"; artifacts: Artifacts }
  | { status: "empty" }
  | { status: "error"; message: string };

/** The Monaco language id the generated code is highlighted in. */
const GENERATED_LANGUAGE_ID: Readonly<Record<TranspileLanguage, string>> = {
  python: "python",
  java: "java",
};

/**
 * What the output directory was last built from, and the `translate` launch that builds it: a key
 * (folder, artefact epoch `runId`, the count of asset writes, directory, copybook paths, codepage
 * overrides) and a promise that settles when it ends. A tab opened or switched back to under the same key, during
 * that launch or after it, awaits the promise and reads the existing files, so the engine launches
 * once per key.
 * Module-level: every open transpile tab shares one output directory.
 */
let translation: { readonly key: string; readonly done: Promise<void> } | null = null;

/**
 * One asset's translation, beside the COBOL it came from.
 *
 * The translation is produced on opening, outside the analysis run: it is far the most
 * expensive step and most assets are never looked at this way. It writes into a directory beside the
 * project file, and LINE_MAP — which the run persists into the same project file — is what lines the
 * two panes up.
 *
 * The source editor could host this as a pane; it is a tab of its own because two languages side
 * by side need the width of the whole editor area, and the COBOL shown here is read-only while the
 * source view is editable.
 */
export function TranspilePane({ path }: TranspilePaneProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [language, setLanguage] = useState<TranspileLanguage>("python");

  const inputDir = project.inputDir;
  const dbPath = project.outputPaths?.db ?? project.dbPath;
  const outDir = artifactSubdir(dbPath, "transpile");
  const copybookPaths = settings.copybookPaths;
  // The codepages the scan and the lint read the files in, so the translation reads them the same way.
  const codepageOverrides = project.codepageOverrides;
  // The translation is an engine run of its own and reads the project file, so it waits for a run in
  // progress and for the scan that follows a save or an import; what is on screen stays until then.
  const waiting = engineBusy(project);

  useEffect(() => {
    if (inputDir === null || dbPath === null || dbPath === undefined || outDir === "") {
      // Nothing has been scanned yet, so there is nowhere to translate into and nothing to line up
      // against. Saying so beats a spinner that never resolves.
      setLoad({ status: "empty" });
      return;
    }
    if (waiting) {
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading" });
    // The writes are in the key beside the epoch, as in the fix preview (FixDiff).
    const key = `${inputDir}|${project.runId}|${project.staleWrites}|${outDir}|${copybookPaths.join("|")}|${JSON.stringify(codepageOverrides)}`;
    void (async () => {
      try {
        let entry = translation;
        if (entry === null || entry.key !== key) {
          const done = viewRun(() =>
            api().run({
              subcommand: "translate",
              request: {
                inputDir,
                copybookPaths: [...copybookPaths],
                codepageOverrides,
                db: dbPath,
                language: "both",
                outDir,
              },
            }),
          ).then((finished) => {
            const crashed = engineFailure(finished);
            if (crashed !== null) {
              throw new Error(crashed);
            }
          });
          const built = { key, done };
          entry = built;
          translation = built;
          // After a run that failed the out directory still holds the translation of the run before
          // it, which reading it now would present as this one's. The entry is dropped, so a later
          // mount tries again.
          done.catch(() => {
            if (translation === built) {
              translation = null;
            }
          });
        }
        await entry.done;
        // The source editor already decoded this asset when its tab was opened; reading that spares
        // a second engine launch for text this view only shows, never edits.
        const opened = openDocument(sourceTabId(path));
        // The override the scan and the lint read the file in; without one the engine reads the
        // codepage the scan recorded.
        const decoded =
          opened !== null
            ? null
            : await api().decode({
                baseDir: inputDir,
                path,
                db: dbPath,
                codepage: codepageOverrides[path] || undefined,
              });
        const cobolText = opened?.text ?? decoded?.text ?? "";
        const decodeError = opened !== null ? "" : (decoded?.error ?? "");
        const artifacts = await api().readTranspile({ outDir, dbPath, cobolRelPath: path });
        if (cancelled) return;
        if (decodeError !== "") {
          setLoad({ status: "error", message: decodeError });
          return;
        }
        setLoad(
          artifacts.files.length === 0
            ? { status: "empty" }
            : {
                status: "ready",
                artifacts: {
                  cobol: cobolText,
                  files: artifacts.files,
                  lineMap: artifacts.lineMap,
                },
              },
        );
      } catch (error: unknown) {
        if (!cancelled) setLoad({ status: "error", message: errorMessage(error) });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [
    inputDir,
    dbPath,
    outDir,
    path,
    copybookPaths,
    codepageOverrides,
    project.runId,
    project.staleWrites,
    waiting,
  ]);

  // Until the scan after a save has read the files, a translation on screen was made before the save.
  const beforeSave = structureScanDue(project) && load.status === "ready";

  const artifacts = load.status === "ready" ? load.artifacts : null;
  const offered = useMemo(
    () => (artifacts === null ? [] : languagesOf(artifacts.files)),
    [artifacts],
  );
  // The run may have produced only one of the two languages; show one it actually wrote.
  const shown = offered.includes(language) ? language : (offered[0] ?? language);
  /** Nothing is ruled out until a run has said what it produced. */
  const unavailable = (candidate: TranspileLanguage): boolean =>
    offered.length > 0 && !offered.includes(candidate);
  const file = artifacts === null ? null : fileOf(artifacts.files, shown);
  const entries = useMemo(
    () => (artifacts === null || file === null ? [] : entriesOf(artifacts.lineMap, file.name)),
    [artifacts, file],
  );

  return (
    <div className="ci-transpile" data-testid={`transpile-${path}`}>
      <div className="ci-source__meta">
        <label className="ci-source__control">
          <span className="ci-source__control-label">{text.transpileView.language}</span>
          <select
            className="ci-select"
            value={shown}
            onChange={(event) => setLanguage(event.target.value as TranspileLanguage)}
            data-testid="transpile-language"
          >
            {/* A language the run produced no file in cannot be shown, so it cannot be picked. */}
            <option value="python" disabled={unavailable("python")}>
              {text.transpileView.python}
            </option>
            <option value="java" disabled={unavailable("java")}>
              {text.transpileView.java}
            </option>
          </select>
        </label>
        {load.status === "ready" && entries.length === 0 ? (
          <span>{text.transpileView.noMap}</span>
        ) : null}
        {beforeSave ? (
          <span className="ci-source__note" data-testid="transpile-before-save">
            {text.transpileView.beforeSave}
          </span>
        ) : null}
        <div className="ci-source__spacer" />
      </div>

      {load.status === "loading" ? (
        <p className="ci-source__state">{text.transpileView.loading}</p>
      ) : null}
      {load.status === "empty" ? (
        <p className="ci-source__state">
          {inputDir === null
            ? text.empty.noFolder
            : dbPath === null || dbPath === undefined
              ? text.empty.notAnalysed
              : text.transpileView.empty}
        </p>
      ) : null}
      {load.status === "error" ? (
        <p className="ci-source__state ci-source__state--error" role="alert">
          {text.transpileView.error}
          <span className="ci-source__reason">{load.message}</span>
        </p>
      ) : null}

      {artifacts !== null && file !== null ? (
        <SideBySide
          left={artifacts.cobol}
          leftLanguageId={languageIdFor(path)}
          leftLabel={path}
          right={file.text}
          rightLanguageId={GENERATED_LANGUAGE_ID[file.language]}
          rightLabel={file.name}
          toRight={(line) => generatedLineFor(entries, line)}
          toLeft={(line) => cobolLineFor(entries, line)}
        />
      ) : null}
    </div>
  );
}
