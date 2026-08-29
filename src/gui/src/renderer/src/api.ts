/**
 * Access to the API the preload published. Everything the renderer does that leaves the window goes
 * through here, so no component reaches for the global directly.
 */

import type { CobolInsightApi } from "../../shared/ipc";

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
