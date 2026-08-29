import { mkdirSync, rmdirSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

/** What resolving the portable storage location needs from the runtime. */
export interface PortableUserDataInput {
  /** app.isPackaged: separates a distribution from a development run. */
  isPackaged: boolean;
  /** app.getPath("exe"): in a distribution this sits directly in the extracted folder. */
  exePath: string;
  /** Creates the directory and reports whether it can be written to. */
  ensureWritable: (dir: string) => boolean;
}

/**
 * Points userData (settings, cache, local storage) at `data/` beside the extracted distribution.
 * Moving or deleting the extracted folder then leaves nothing behind, and neither an installation
 * step nor a write into the user profile is needed.
 *
 * Started read-only from a shared folder, `data/` cannot be created; returning null leaves Electron's
 * default location (%APPDATA% on Windows) in charge.
 */
export function resolvePortableUserData(input: PortableUserDataInput): string | null {
  if (!input.isPackaged) {
    return null;
  }
  const dataDir = join(dirname(input.exePath), "data");
  return input.ensureWritable(dataDir) ? dataDir : null;
}

/**
 * Creates the directory and checks it is writable by writing a probe file. fs.access does not
 * evaluate Windows ACLs and reports success even where writing is denied.
 *
 * When the check fails, any directory this function created is removed again (it does not walk up
 * to parents).
 */
export function ensureWritable(dir: string): boolean {
  // Name the probe per process so two instances started from the same folder do not collide.
  const probe = join(dir, `.write-probe-${process.pid}`);
  let created = false;
  try {
    created = mkdirSync(dir, { recursive: true }) !== undefined;
    writeFileSync(probe, "");
  } catch (error) {
    console.warn("[main] the storage location is not writable; falling back to the default", dir, error);
    if (created) {
      try {
        rmdirSync(dir);
      } catch {
        // Whether the cleanup succeeded does not change the verdict.
      }
    }
    return false;
  }
  try {
    unlinkSync(probe);
  } catch {
    // Failing to remove the probe does not make the directory unwritable.
  }
  return true;
}
