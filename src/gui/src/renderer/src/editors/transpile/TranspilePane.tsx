import { useEffect, useMemo, useState, type ReactElement } from "react";
import type {
  LineMapEntry,
  TranspileGeneratedFile,
  TranspileLanguage,
} from "../../../../shared/ipc";
import { api, errorMessage } from "../../api";
import { text } from "../../text";
import { useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { artifactSubdir } from "../../model/artifactPaths";
import { cobolLineFor, entriesOf, fileOf, generatedLineFor, languagesOf } from "../../model/lineMap";
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
 * One asset's translation, beside the COBOL it came from.
 *
 * The translation is produced on opening rather than during the analysis run: it is far the most
 * expensive step and most assets are never looked at this way. It writes into a directory beside the
 * project file, and LINE_MAP — which the run persists into the same project file — is what lines the
 * two panes up.
 *
 * This is its own tab rather than a pane of the source editor. Two languages side by side need the
 * width of the whole editor area, and the COBOL shown here is read-only, which the editable source
 * view is not.
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

  useEffect(() => {
    if (inputDir === null || dbPath === null || dbPath === undefined || outDir === "") {
      // Nothing has been scanned yet, so there is nowhere to translate into and nothing to line up
      // against. Saying so beats a spinner that never resolves.
      setLoad({ status: "empty" });
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading" });
    void (async () => {
      try {
        await api().run({
          subcommand: "translate",
          request: {
            inputDir,
            copybookPaths: [...copybookPaths],
            db: dbPath,
            language: "both",
            outDir,
          },
        });
        const decoded = await api().decode({ baseDir: inputDir, path, db: dbPath });
        const artifacts = await api().readTranspile({ outDir, dbPath, cobolRelPath: path });
        if (cancelled) return;
        if (decoded.error !== "") {
          setLoad({ status: "error", message: decoded.error });
          return;
        }
        setLoad(
          artifacts.files.length === 0
            ? { status: "empty" }
            : {
                status: "ready",
                artifacts: {
                  cobol: decoded.text,
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
  }, [inputDir, dbPath, outDir, path, copybookPaths]);

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
        <div className="ci-source__spacer" />
      </div>

      {load.status === "loading" ? (
        <p className="ci-source__state">{text.transpileView.loading}</p>
      ) : null}
      {load.status === "empty" ? (
        <p className="ci-source__state">{text.transpileView.empty}</p>
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
          leftLabel={text.transpileView.left(path)}
          right={file.text}
          rightLanguageId={GENERATED_LANGUAGE_ID[file.language]}
          rightLabel={text.transpileView.right(file.name)}
          toRight={(line) => generatedLineFor(entries, line)}
          toLeft={(line) => cobolLineFor(entries, line)}
        />
      ) : null}
    </div>
  );
}
