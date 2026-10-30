/**
 * A cache in front of the engine's `decode`.
 *
 * Decoding launches a JVM. Opening an asset, switching to another tab and switching back would run
 * it three times for the same unchanged bytes, and the second and third runs would take as long as
 * the first. The cache keys on what actually decides the answer — the real path, the file's
 * modification time and size, and the codepage the caller asked for — so a file that has been edited
 * outside the tool is never served from it.
 *
 * The project file is part of the key as well: with no explicit codepage, the engine reads the one
 * recorded at scan time from that database, so two databases can decode the same bytes differently.
 *
 * Entries are evicted least-recently-used. A JavaScript Map iterates in insertion order, so
 * re-inserting on a hit is all the ordering the eviction needs.
 *
 * What is stored is the pending decode, so two requests for the same file that arrive before the
 * first has finished share one JVM launch, and two decodes of one file never race each other.
 */

import type { DecodeResult, DecodeSourceRequest, SourceStamp } from "../../shared/ipc";

/** How many decoded files are kept. Enough for every tab a session realistically has open. */
export const DECODE_CACHE_CAPACITY = 64;

/**
 * What joins the parts of a key. No path, codepage name or number can hold it, so two different
 * files cannot produce the same key. It is written as an escape: the byte itself in the source would
 * make the file binary to git and to every diff.
 */
const SEPARATOR = "\u0000";

/** What the cache needs: the file's identity, and the decode itself. */
export interface DecodeCacheDeps {
  /**
   * The real path and stamp of the requested file, or null when it cannot be reached (absent, or
   * outside the allowed base directory). A file with no stamp is never cached.
   */
  locate(request: DecodeSourceRequest): Promise<{ realPath: string; stamp: SourceStamp } | null>;
  /** Runs the engine's decode. */
  decode(request: DecodeSourceRequest): Promise<DecodeResult>;
}

/** The cached decode, with the means to empty it. */
export interface CachedDecode {
  (request: DecodeSourceRequest): Promise<DecodeResult>;
  /**
   * Drops every entry. A new scan can record a different codepage for a file whose bytes never
   * changed, and the key cannot see that: the stamp is the same and the caller sends no codepage.
   */
  clear(): void;
}

/**
 * Builds the cached decode function. Each renderer session uses one; the cache lives as long as the
 * function does.
 */
export function createCachedDecode(
  deps: DecodeCacheDeps,
  capacity: number = DECODE_CACHE_CAPACITY,
): CachedDecode {
  const entries = new Map<string, Promise<DecodeResult>>();

  const cached = async (request: DecodeSourceRequest): Promise<DecodeResult> => {
    const located = await deps.locate(request).catch(() => null);
    if (located === null) {
      // Nothing to key on. Let the decode run and report the failure itself.
      return deps.decode(request);
    }
    const key = [
      located.realPath,
      located.stamp.mtimeMs,
      located.stamp.byteSize,
      request.codepage ?? "",
      request.db ?? "",
    ].join(SEPARATOR);

    const hit = entries.get(key);
    if (hit !== undefined) {
      // Re-insert so this entry becomes the most recently used one.
      entries.delete(key);
      entries.set(key, hit);
      return hit;
    }

    // The pending decode goes in before it is awaited, so a second request for the same file joins
    // this one and launches no second JVM.
    const pending = deps.decode(request);
    entries.set(key, pending);
    let result: DecodeResult;
    try {
      result = await pending;
    } catch (error: unknown) {
      entries.delete(key);
      throw error;
    }
    // A failed decode is not cached: the reason is usually outside the file (a missing engine, a
    // codepage the build cannot handle) and retrying is what the user will do next.
    if (result.error !== "") {
      entries.delete(key);
      return result;
    }
    while (entries.size > capacity) {
      const oldest = entries.keys().next();
      if (oldest.done === true) {
        break;
      }
      entries.delete(oldest.value);
    }
    return result;
  };

  cached.clear = (): void => entries.clear();
  return cached;
}
