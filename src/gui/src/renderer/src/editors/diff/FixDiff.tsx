import { useEffect, useState, type ReactElement } from "react";
import type { FixDiff as FixDiffData } from "../../../../shared/ipc";
import { api, engineFailure, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { viewRun } from "../../state/engineGate";
import { engineBusy, structureScanDue, useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { artifactSubdir, fixOutDirOf, insideAssetFolder } from "../../model/artifactPaths";
import { languageIdFor } from "../../vendor/monarch";
import { DiffView } from "./DiffView";

export interface FixDiffProps {
  /** The asset's path relative to the asset folder. */
  path: string;
  /** Reports what happened, so the shell can raise a notification. */
  onNotify: (message: string, failed?: boolean) => void;
}

type Load =
  | { status: "loading" }
  | { status: "ready"; diff: FixDiffData }
  | { status: "empty" }
  | { status: "error"; message: string };

/**
 * How many files the write-out wrote. The engine's `fix apply` summary lists them under
 * `writtenFiles`; a summary that is missing or shaped otherwise counts as none.
 */
function writtenCount(summary: Record<string, unknown> | null): number {
  const written = summary?.["writtenFiles"];
  return Array.isArray(written) ? written.length : 0;
}

/** Joins a relative path onto a base, using whichever separator the base already uses. */
function joinPath(base: string, relPath: string): string {
  const separator = base.includes("\\") ? "\\" : "/";
  const trimmed = base.endsWith("\\") || base.endsWith("/") ? base.slice(0, -1) : base;
  return `${trimmed}${separator}${relPath.replace(/[\\/]/g, separator)}`;
}

/**
 * What the preview directory was last built from, and the `fix` launch that builds it: a key
 * (folder, artefact epoch `runId`, the count of asset writes, directory, rule file, copybook paths,
 * codepage overrides) and a promise that settles with the number of files that launch wrote. A tab mounted or remounted
 * under the same key awaits that promise and reads the diff already on disk, so the engine launches
 * once per key.
 * Module-level, because every open tab shares one preview directory.
 */
let preview: { readonly key: string; readonly written: Promise<number> } | null = null;

/**
 * The fix proposal for one asset, shown against the original.
 *
 * The original is never written to. `fix apply` writes the corrected sources into a directory of its
 * own, and this view compares one of those against the file in the asset folder; the engine has no
 * subcommand that returns the corrected text without writing it, and `fix preview` emits only a
 * unified diff. The scratch directory sits beside the project file, apart from the directory the
 * "write out" button uses, so looking at a proposal never leaves anything in the place a user
 * collects results from. Where the button writes is the user's own setting when they have set one.
 */
export function FixDiff({ path, onNotify }: FixDiffProps): ReactElement {
  const project = useProject();
  const settings = useSettings();
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [applying, setApplying] = useState(false);
  /** How many files the preview run wrote out; the write-out button and its path are shown above 0. */
  const [previewWritten, setPreviewWritten] = useState(0);

  const inputDir = project.inputDir;
  const dbPath = project.outputPaths?.db;
  const previewDir = artifactSubdir(dbPath, "fix-preview");
  const applyDir = fixOutDirOf(settings.fixOutDir, dbPath);
  const copybookPaths = settings.copybookPaths;
  const rulesFile = project.outputPaths?.rules;
  // The codepages the scan and the lint read the files in, so the proposal reads them the same way.
  const codepageOverrides = project.codepageOverrides;

  // The preview is an engine run of its own, so it waits for a run in progress and for the scan that
  // follows a save or an import; what is on screen stays until then.
  const waiting = engineBusy(project);
  useEffect(() => {
    if (inputDir === null || previewDir === "") {
      setLoad({ status: "empty" });
      setPreviewWritten(0);
      return;
    }
    if (waiting) {
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading" });
    setPreviewWritten(0);
    // The writes are in the key beside the epoch: a save whose scan was stopped leaves the epoch
    // where it was, and the preview built before that save shows the saved file as it no longer is.
    const key = `${inputDir}|${project.runId}|${project.staleWrites}|${previewDir}|${rulesFile ?? ""}|${copybookPaths.join("|")}|${JSON.stringify(codepageOverrides)}`;
    void (async () => {
      let entry = preview;
      if (entry === null || entry.key !== key) {
        const written = viewRun(() =>
          api().run({
            subcommand: "fix",
            request: {
              inputDir,
              copybookPaths: [...copybookPaths],
              codepageOverrides,
              rulesFile,
              outDir: previewDir,
            },
          }),
        ).then((result) => {
          const crashed = engineFailure(result);
          if (crashed !== null) {
            throw new Error(crashed);
          }
          return writtenCount(result.summary);
        });
        const built = { key, written };
        entry = built;
        preview = built;
        // After a run that failed the directory still holds the proposals of the run before it, and
        // reading one would show it as this run's. The entry is dropped, so the next mount tries again.
        written.catch(() => {
          if (preview === built) {
            preview = null;
          }
        });
      }
      try {
        const count = await entry.written;
        if (!cancelled) setPreviewWritten(count);
      } catch (error: unknown) {
        if (!cancelled) setLoad({ status: "error", message: errorMessage(error) });
        return;
      }
      try {
        const diff = await api().readFixDiff({
          originalPath: joinPath(inputDir, path),
          fixedPath: joinPath(previewDir, path),
          relPath: path,
        });
        if (cancelled) return;
        setLoad(
          diff.originalText === diff.fixedText ? { status: "empty" } : { status: "ready", diff },
        );
      } catch {
        // The engine writes out only the files it could fix, so an absent one means "no proposal".
        if (!cancelled) setLoad({ status: "empty" });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [
    inputDir,
    path,
    previewDir,
    copybookPaths,
    rulesFile,
    codepageOverrides,
    project.runId,
    project.staleWrites,
    waiting,
  ]);

  // Until the scan after a save has read the files, a proposal on screen was built before the save.
  const beforeSave = structureScanDue(project) && load.status === "ready";

  const apply = (): void => {
    if (inputDir === null) {
      return;
    }
    // The settings screen refuses this value as it is typed, but a stored one can still point inside
    // the folder that was opened afterwards; the write is where the originals are actually at stake.
    if (insideAssetFolder(applyDir, inputDir)) {
      onNotify(text.fixView.outDirInside, true);
      return;
    }
    setApplying(true);
    viewRun(() =>
      api().run({
        subcommand: "fix",
        request: {
          inputDir,
          copybookPaths: [...copybookPaths],
          codepageOverrides,
          rulesFile,
          outDir: applyDir,
        },
      }),
    )
      // The write-out covers every file in the asset folder that has a proposal, so the notice gives
      // the number of files written and the user sees it reached past the file on screen.
      .then((result) => {
        const crashed = engineFailure(result);
        if (crashed !== null) {
          onNotify(text.fixView.exportFailed(crashed), true);
          return;
        }
        onNotify(text.fixView.applied(applyDir, writtenCount(result.summary)));
      })
      .catch((error: unknown) => onNotify(errorMessage(error), true))
      .finally(() => setApplying(false));
  };

  return (
    <div className="ci-source ci-fix" data-testid={`fix-${path}`}>
      <div className="ci-source__meta">
        {/* The tab already names the asset and the proposal, so the row carries the action alone,
            and the note while the proposal on screen predates a save. */}
        {beforeSave ? (
          <span className="ci-source__note" data-testid="fix-before-save">
            {text.fixView.beforeSave}
          </span>
        ) : null}
        <div className="ci-source__spacer" />
        {/* The action and where it writes are offered once the folder has a proposal to write out. */}
        {previewWritten === 0 ? null : (
          <>
            <span className="ci-source__note">{text.report.pathAt(applyDir)}</span>
            <button
              type="button"
              className="ci-button"
              onClick={apply}
              // The write-out is an engine run of its own: the button is off while an analysis runs
              // or a scan after a write is due, and a press queues behind other views' runs.
              disabled={applying || waiting}
              title={waiting ? text.fixView.applyWaits : undefined}
              aria-description={waiting ? text.fixView.applyWaits : undefined}
              data-testid="fix-apply"
            >
              {text.fixView.apply}
            </button>
          </>
        )}
      </div>
      {load.status === "loading" ? <p className="ci-source__state">{text.fixView.loading}</p> : null}
      {load.status === "empty" ? <p className="ci-source__state">{text.fixView.empty}</p> : null}
      {load.status === "error" ? (
        <p className="ci-source__state ci-source__state--error" role="alert">
          {text.fixView.error}
          <span className="ci-source__reason">{load.message}</span>
        </p>
      ) : null}
      {load.status === "ready" ? (
        <>
          <div className="ci-sidebyside ci-sidebyside--labels">
            <div className="ci-sidebyside__pane">
              <span className="ci-sidebyside__label">{text.fixView.original}</span>
            </div>
            <div className="ci-sidebyside__pane">
              <span className="ci-sidebyside__label">{text.fixView.fixed}</span>
            </div>
          </div>
          <DiffView
            original={load.diff.originalText}
            modified={load.diff.fixedText}
            languageId={languageIdFor(path)}
            ariaLabel={`${text.fixView.title} ${path}`}
          />
        </>
      ) : null}
    </div>
  );
}
