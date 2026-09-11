/**
 * Access to the API the preload published. Everything the renderer does that leaves the window goes
 * through here, so no component reaches for the global directly.
 */

import type { CobolInsightApi, EngineResult } from "../../shared/ipc";
import { text } from "./i18n/text";

declare global {
  interface Window {
    readonly cobolInsight?: CobolInsightApi;
  }
}

/**
 * The published API. It is absent only when the renderer is loaded without the preload, which no
 * supported configuration does; failing loudly beats every call site guarding against undefined.
 */
export function api(): CobolInsightApi {
  const published = window.cobolInsight;
  if (published === undefined) {
    throw new Error("window.cobolInsight is missing: the preload did not run");
  }
  return published;
}

/** The message of a rejected call, in a form a screen can show. */
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** How many lines of the engine's stderr stand as a failed run's reason. */
const REASON_LINE_COUNT = 5;

/**
 * Why an engine run failed, or null when it finished.
 *
 * A finished run prints its summary JSON as the last line of stdout; a run without one died, and the
 * artefacts on disk are the previous run's. Every caller that goes on to read what a run was
 * supposed to write asks here first, so no screen presents an older run's output as the current one.
 *
 * The reason is the end of stderr, with the first line that is not indented in front of it: the JVM
 * writes the exception on that line and its stack frames, each indented, below it, so the tail alone
 * would be frames. With nothing on stderr the standing wording says the engine stopped short.
 */
export function engineFailure(finished: EngineResult): string | null {
  if (finished.summary !== null) {
    return null;
  }
  const lines = finished.stderr.split(/\r?\n/).filter((line) => line.trim() !== "");
  if (lines.length === 0) {
    return text.run.engineFailed;
  }
  const tail = lines.slice(-REASON_LINE_COUNT);
  const first = lines.find((line) => !/^\s/.test(line));
  return (first === undefined || tail.includes(first) ? tail : [first, ...tail]).join("\n");
}
