/**
 * One engine at a time.
 *
 * The analysis (scan, lint) is started by the user or by a save, and three views start an engine run
 * of their own: the fix proposal, the translation and the report. Main keeps a single running
 * process for the cancel button to stop, so two runs side by side would leave that button pointing
 * at whichever started last. The views' runs are therefore queued here, one after another, and a
 * stage of the analysis waits for the queue to empty before it launches.
 *
 * It is a module, outside React: a run in flight has to be seen by whichever component asks next,
 * also after the component that started it has been unmounted.
 */

let viewRuns: Promise<void> = Promise.resolve();

/** Starts a view's engine run once the runs asked for before it have ended. */
export function viewRun<T>(launch: () => Promise<T>): Promise<T> {
  const run = viewRuns.then(launch);
  viewRuns = run.then(
    () => undefined,
    () => undefined,
  );
  return run;
}

/** Resolves once every view's run asked for so far has ended. */
export function viewRunsSettled(): Promise<void> {
  return viewRuns;
}
