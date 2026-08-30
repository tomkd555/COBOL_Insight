import { useEffect, useState, type ReactElement } from "react";
import type { FixDiff as FixDiffData } from "../../../../shared/ipc";
import { api, errorMessage } from "../../api";
import { text } from "../../i18n/text";
import { useProject } from "../../state/projectStore";
import { useSettings } from "../../state/settingsStore";
import { artifactSubdir, fixOutDirOf } from "../../model/artifactPaths";
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

/** Joins a relative path onto a base, using whichever separator the base already uses. */
function joinPath(base: string, relPath: string): string {
  const separator = base.includes("\\") ? "\\" : "/";
  const trimmed = base.endsWith("\\") || base.endsWith("/") ? base.slice(0, -1) : base;
  return `${trimmed}${separator}${relPath.replace(/[\\/]/g, separator)}`;
}

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
    void (async () => {
      try {
        await api().run({
          subcommand: "fix-apply",
          request: { inputDir, copybookPaths: [...copybookPaths], rulesFile, outDir: previewDir },
        });
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
  }, [inputDir, path, previewDir, copybookPaths, rulesFile]);

  const apply = (): void => {
    if (inputDir === null) {
      return;
    }
    setApplying(true);
    api()
      .run({
        subcommand: "fix-apply",
        request: { inputDir, copybookPaths: [...copybookPaths], rulesFile, outDir: applyDir },
      })
      .then(() => onNotify(text.fixView.applied(applyDir)))
      .catch((error: unknown) => onNotify(errorMessage(error), true))
      .finally(() => setApplying(false));
  };

  return (
    <div className="ci-source" data-testid={`fix-${path}`}>
      <div className="ci-source__meta">
        <span>
          {text.fixView.title} {path}
        </span>
        <div className="ci-source__spacer" />
        <button
          type="button"
          className="ci-button"
          onClick={apply}
          disabled={applying || load.status !== "ready"}
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
        <DiffView
          original={load.diff.originalText}
          modified={load.diff.fixedText}
          languageId={languageIdFor(path)}
          ariaLabel={`${text.fixView.title} ${path}`}
        />
      ) : null}
    </div>
  );
}
