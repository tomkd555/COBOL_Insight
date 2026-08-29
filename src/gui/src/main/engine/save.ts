import type {
  EngineResult,
  SaveReparseError,
  SaveRequest,
  SaveResult,
  SaveSourceRequest,
} from "../../shared/ipc";
import { resolveSourceFile, type RealPathResolver } from "../fs/pathGuard";

/**
 * Writing edited text back over the original.
 *
 * Node cannot encode Shift_JIS or EBCDIC, so preserving the original codepage is impossible here.
 * The edited text goes into a UTF-8 scratch file and the engine's `save` subcommand re-encodes it.
 *
 * The write target is a file the user opened on screen, so it passes the same boundary check as the
 * read ({@link resolveSourceFile}). The scratch file gets a fresh name per save and is removed
 * whatever the outcome: a fixed name would let a later save overwrite an earlier one's text and
 * write the wrong asset back.
 */

/** The filesystem the write-back needs: the boundary check plus the scratch file. */
export interface SaveFileSystem extends RealPathResolver {
  writeText(absPath: string, text: string): Promise<void>;
  /** Removes a file; an absent file is not an error. */
  remove(absPath: string): Promise<void>;
}

export interface SaveDeps {
  fs: SaveFileSystem;
  /** Picks the scratch file holding the edited text. Called once per save. */
  tempFile(): string;
  /** The project file the engine reads the recorded codepage from. */
  dbPath: string;
  /** Runs the engine's save subcommand once. */
  run(request: SaveRequest): Promise<EngineResult>;
}

/**
 * Turns the save summary into {@link SaveResult}. A missing summary means the engine died before
 * reporting, leaving the screen unable to tell whether the write happened, so it throws.
 */
export function parseSaveSummary(
  summary: Record<string, unknown> | null,
  exitCode: number,
): SaveResult {
  if (summary === null) {
    throw new Error("the engine reported no save result, so it is unknown whether the file was written");
  }
  return {
    written: summary["written"] === true,
    path: asText(summary["path"]),
    changedLineFrom: asInt(summary["changedLineFrom"]),
    changedLineTo: asInt(summary["changedLineTo"]),
    reparseErrors: asReparseErrors(summary["reparseErrors"]),
    error: asText(summary["error"]),
    // Prefer the summary's exit code; fall back to the process's only when it is absent.
    exitCode: typeof summary["exitCode"] === "number" ? summary["exitCode"] : exitCode,
  };
}

/** Writes the edited text back over the original inside the allowed base directory. */
export async function saveSource(
  deps: SaveDeps,
  request: SaveSourceRequest,
): Promise<SaveResult> {
  const target = await resolveSourceFile(deps.fs, request.baseDir, request.path);
  const tempFile = deps.tempFile();
  await deps.fs.writeText(tempFile, request.editedText);
  try {
    const result = await deps.run({
      file: target,
      editedFile: tempFile,
      codepage: request.codepage,
      copybookPaths: request.copybookPaths,
      db: deps.dbPath,
    });
    return parseSaveSummary(result.summary, result.exitCode);
  } finally {
    // A failure to clean up must not overturn the save.
    await deps.fs.remove(tempFile).catch(() => undefined);
  }
}

function asText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asInt(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function asReparseErrors(value: unknown): SaveReparseError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const errors: SaveReparseError[] = [];
  for (const element of value) {
    if (element === null || typeof element !== "object") {
      continue;
    }
    const record = element as Record<string, unknown>;
    errors.push({ line: asInt(record["line"]), message: asText(record["message"]) });
  }
  return errors;
}
