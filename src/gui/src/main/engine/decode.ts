import type {
  DecodeRequest,
  DecodeResult,
  DecodeSourceRequest,
  DecodedLine,
  EngineResult,
  SourceStamp,
} from "../../shared/ipc";
import { resolveSourceFile, type RealPathResolver } from "../fs/pathGuard";

/**
 * Decoding one source file for display.
 *
 * The engine owns every codepage. Node's TextDecoder has UTF-8 and shift_jis and no EBCDIC
 * (CP930/CP939) converter at all, so decoding in the main process would silently exclude the
 * mainframe encodings that matter most. `decode` therefore runs in the engine, writes its result to
 * a scratch JSON file, and the main process reads that file and hands the renderer plain strings.
 *
 * The read target is a file the user opened, so the same boundary check the write-back uses
 * ({@link resolveSourceFile}) applies. The scratch file gets a fresh name per call and is removed
 * whatever the outcome: a fixed name would let two concurrent decodes read each other's output.
 */

/** The filesystem decoding needs: the boundary check plus the scratch file. */
export interface DecodeFileSystem extends RealPathResolver {
  readText(absPath: string): Promise<string>;
  /** Removes a file; an absent file is not an error. */
  remove(absPath: string): Promise<void>;
}

export interface DecodeDeps {
  fs: DecodeFileSystem;
  /** Picks the scratch file the engine writes its result to. Called once per decode. */
  tempFile(): string;
  /** Runs the engine's decode subcommand once. */
  run(request: DecodeRequest): Promise<EngineResult>;
}

/** An empty result carrying only the reason the file could not be decoded. */
function failed(error: string): DecodeResult {
  return {
    text: "",
    codepage: "",
    detected: false,
    soSiPresent: false,
    lines: [],
    stamp: { mtimeMs: 0, byteSize: 0 },
    error,
  };
}

/** Converts the engine's decode JSON into {@link DecodeResult}, defaulting what is missing. */
export function parseDecodeJson(text: string): DecodeResult {
  const doc = asObject(JSON.parse(text) as unknown);
  const error = asText(doc["error"]);
  return {
    text: asText(doc["text"]),
    codepage: asText(doc["codepage"]),
    detected: doc["detected"] === true,
    soSiPresent: doc["soSiPresent"] === true,
    lines: asLines(doc["lines"]),
    stamp: asStamp(doc["stamp"]),
    error,
  };
}

/**
 * Decodes one file inside an allowed base directory. A failure to start the engine, a non-zero exit
 * with no result file, or unreadable JSON all come back as a result whose `error` is set, so the
 * screen can report why nothing is shown rather than showing an empty file.
 */
export async function decodeSource(
  deps: DecodeDeps,
  request: DecodeSourceRequest,
): Promise<DecodeResult> {
  const target = await resolveSourceFile(deps.fs, request.baseDir, request.path);
  const outFile = deps.tempFile();
  try {
    const result = await deps.run({
      file: target,
      codepage: request.codepage,
      db: request.db,
      outFile,
    });
    try {
      return parseDecodeJson(await deps.fs.readText(outFile));
    } catch {
      return failed(
        result.stderr.trim() === ""
          ? `解析エンジンが復号結果を書き出しませんでした（終了コード${result.exitCode}）。文字コードを指定して開き直してください。`
          : result.stderr.trim(),
      );
    }
  } finally {
    // A failure to clean up must not overturn a successful decode.
    await deps.fs.remove(outFile).catch(() => undefined);
  }
}

function asObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function asInt(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function asStamp(value: unknown): SourceStamp {
  const stamp = asObject(value);
  return { mtimeMs: asInt(stamp["mtimeMs"]), byteSize: asInt(stamp["byteSize"]) };
}

function asLines(value: unknown): DecodedLine[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((element) => {
    const line = asObject(element);
    const raw = Array.isArray(line["boundaries"]) ? line["boundaries"] : [];
    return {
      byteLength: asInt(line["byteLength"]),
      boundaries: [asInt(raw[0]), asInt(raw[1]), asInt(raw[2]), asInt(raw[3])],
    };
  });
}
