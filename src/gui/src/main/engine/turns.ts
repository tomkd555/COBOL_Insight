/**
 * The turns the scans, the saves and the reads of one session take at the asset folder and the
 * project file.
 *
 * A scan rewrites the project file a save reads the codepage from, and lists the folder a save
 * writes into, so the two never run side by side: a scan starts once the saves, the scans and the
 * reads asked for before it are done, and a save starts once the saves before it and every scan
 * asked for before it are done. A read of the project file starts once every scan asked for before
 * it is done, so it never sees a file half-written. Each waits only for what was asked for earlier,
 * so two of them can never wait for each other.
 *
 * A cancel releases the scans still waiting for their turn; it leaves the order of the rest as it
 * was. A scan that had already started keeps its place: what is asked for after the cancel still
 * waits for it to end.
 *
 * Scans are kept apart from each other because each one rewrites the project file; a translation,
 * which writes the project file too, takes a scan's turn for the same reason. Saves are kept apart
 * from each other as well: the engine's save reads the original and writes it back, so overlapping
 * saves would have the later one read a state the earlier one had not yet written, losing one edit.
 * Reads run side by side with each other and with the saves, which leave the project file as it is.
 */

export interface EngineTurns {
  /**
   * Runs a scan, or another run that writes the project file, when its turn comes. A cancel that
   * arrives while it waits ends the wait at once: nothing is launched and `skipped` supplies the
   * result.
   */
  scan<T>(run: () => Promise<T>, skipped: () => T): Promise<T>;
  /** Runs a save when its turn comes. A failed save still lets the ones after it run. */
  save<T>(task: () => Promise<T>): Promise<T>;
  /** Runs a read of the project file when its turn comes. A failed read holds nothing up. */
  read<T>(task: () => Promise<T>): Promise<T>;
  /** Releases every scan that is waiting for its turn. A scan that has started is the caller's to stop. */
  cancel(): void;
}

export function createEngineTurns(): EngineTurns {
  const settled = (): void => undefined;
  let saves: Promise<void> = Promise.resolve();
  /** Every scan asked for so far, the ones that were started before a cancel included. */
  let scans: Promise<void> = Promise.resolve();
  let reads: Promise<void> = Promise.resolve();
  const waiting = new Set<() => void>();

  return {
    scan<T>(run: () => Promise<T>, skipped: () => T): Promise<T> {
      const earlier = scans;
      const ahead = Promise.all([saves, earlier, reads]);
      let release = settled;
      const cancelled = new Promise<"cancelled">((resolve) => {
        release = () => resolve("cancelled");
      });
      waiting.add(release);
      const turn = Promise.race([ahead.then(() => "ready" as const), cancelled]).then((state) => {
        waiting.delete(release);
        return state === "ready" ? run() : skipped();
      });
      // A turn a cancel skipped ends at once, while the scan it waited for may still run; the chain
      // holds both, so what comes next waits for that scan all the same.
      scans = Promise.all([earlier, turn.then(settled, settled)]).then(settled);
      return turn;
    },

    save<T>(task: () => Promise<T>): Promise<T> {
      const scan = scans;
      const next = saves.then(() => scan).then(task);
      saves = next.then(settled, settled);
      return next;
    },

    read<T>(task: () => Promise<T>): Promise<T> {
      const next = scans.then(task);
      reads = Promise.all([reads, next.then(settled, settled)]).then(settled);
      return next;
    },

    cancel(): void {
      for (const release of waiting) {
        release();
      }
      waiting.clear();
    },
  };
}
