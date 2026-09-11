import { useEffect, useState, type ReactElement } from "react";
import type { FixDiff as FixDiffData } from "../../../../shared/ipc";
import { api, engineFailure, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
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
 * The `fix` request the preview directory was last built from, so mounting a second tab (or
 * reopening this one) after the same run does not launch the engine again for a diff already on
 * disk. Module-level rather than per-instance: every open tab shares one preview directory.
 */
let previewReadyFor = "";

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
  /** How many files the preview run wrote out; the write-out button is only worth pressing above 0. */
  const [previewWritten, setPreviewWritten] = useState(0);

  const inputDir = project.inputDir;
  const dbPath = project.outputPaths?.db;
  const previewDir = artifactSubdir(dbPath, "fix-preview");
  const applyDir = fixOutDirOf(settings.fixOutDir, dbPath);
  const copybookPaths = settings.copybookPaths;
  const rulesFile = project.outputPaths?.rules;

  useEffect(() => {
    if (inputDir === null || previewDir === "") {
      return;
    }
    let cancelled = false;
    setLoad({ status: "loading" });
    const key = `${project.runId}|${previewDir}|${rulesFile ?? ""}|${copybookPaths.join("|")}`;
    void (async () => {
      if (key !== previewReadyFor) {
        try {
          const result = await api().run({
            subcommand: "fix",
            request: { inputDir, copybookPaths: [...copybookPaths], rulesFile, outDir: previewDir },
          });
          const crashed = engineFailure(result);
          if (crashed !== null) {
            // The preview directory still holds the previous run's proposals; reading one now would
            // show it as this run's. The key is left as it was, so the next mount tries again.
            if (!cancelled) setLoad({ status: "error", message: crashed });
            return;
          }
          previewReadyFor = key;
          if (!cancelled) setPreviewWritten(writtenCount(result.summary));
        } catch (error: unknown) {
          if (!cancelled) setLoad({ status: "error", message: errorMessage(error) });
          return;
        }
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
  }, [inputDir, path, previewDir, copybookPaths, rulesFile, project.runId]);

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
    api()
      .run({
        subcommand: "fix",
        request: { inputDir, copybookPaths: [...copybookPaths], rulesFile, outDir: applyDir },
      })
      // The write-out covers the whole asset folder, not the file on screen, so it reports how many
      // files it wrote rather than leaving the user to assume it was this one.
      .then((result) => onNotify(text.fixView.applied(applyDir, writtenCount(result.summary))))
      .catch((error: unknown) => onNotify(errorMessage(error), true))
      .finally(() => setApplying(false));
  };

  return (
    <div className="ci-source" data-testid={`fix-${path}`}>
      <div className="ci-source__meta">
        {/* The tab already names the asset and the proposal, so the row carries the action alone. */}
        <div className="ci-source__spacer" />
        <span className="ci-source__note">{text.report.path}: {applyDir}</span>
        <button
          type="button"
          className="ci-button"
          onClick={apply}
          disabled={applying || previewWritten === 0}
          data-testid="fix-apply"
        >
          {text.fixView.apply}
        </button>
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
