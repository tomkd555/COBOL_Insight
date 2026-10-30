import { describe, expect, it } from "vitest";
import { createEngineTurns } from "./turns";

/** A task whose end the test decides, logging its start and its end. */
function task(log: string[], name: string): { run: () => Promise<string>; finish: () => void } {
  let finish = (): void => undefined;
  const ended = new Promise<void>((resolve) => {
    finish = resolve;
  });
  return {
    run: async () => {
      log.push(`${name} starts`);
      await ended;
      log.push(`${name} ends`);
      return name;
    },
    finish,
  };
}

/** Lets every promise callback that is already due run. */
async function settle(): Promise<void> {
  for (let turn = 0; turn < 10; turn++) {
    await Promise.resolve();
  }
}

const skipped = (): string => "skipped";

describe("createEngineTurns", () => {
  it("holds a save back until the scan that was running when it was asked for has ended", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const scan = task(log, "scan");
    const save = task(log, "save");

    const scanned = turns.scan(scan.run, skipped);
    const saved = turns.save(save.run);
    await settle();
    expect(log).toEqual(["scan starts"]);

    scan.finish();
    await scanned;
    await settle();
    expect(log).toEqual(["scan starts", "scan ends", "save starts"]);
    save.finish();
    expect(await saved).toBe("save");
  });

  it("holds a scan back until the saves asked for before it are done", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const first = task(log, "save 1");
    const second = task(log, "save 2");
    const scan = task(log, "scan");

    void turns.save(first.run);
    void turns.save(second.run);
    const scanned = turns.scan(scan.run, skipped);
    await settle();
    expect(log).toEqual(["save 1 starts"]);

    first.finish();
    await settle();
    second.finish();
    await settle();
    expect(log).toEqual(["save 1 starts", "save 1 ends", "save 2 starts", "save 2 ends", "scan starts"]);
    scan.finish();
    expect(await scanned).toBe("scan");
  });

  it("holds a scan back until the scan asked for before it has ended", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const first = task(log, "scan 1");
    const second = task(log, "scan 2");

    void turns.scan(first.run, skipped);
    const scanned = turns.scan(second.run, skipped);
    await settle();
    expect(log).toEqual(["scan 1 starts"]);

    first.finish();
    await settle();
    expect(log).toEqual(["scan 1 starts", "scan 1 ends", "scan 2 starts"]);
    second.finish();
    expect(await scanned).toBe("scan 2");
  });

  it("ends the wait of a scan at a cancel, and launches nothing for it", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const save = task(log, "save");
    const scan = task(log, "scan");

    const saved = turns.save(save.run);
    const scanned = turns.scan(scan.run, skipped);
    await settle();
    turns.cancel();

    expect(await scanned).toBe("skipped");
    save.finish();
    await saved;
    await settle();
    expect(log).toEqual(["save starts", "save ends"]);
  });

  it("runs requests that alternate in the order they were asked for", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const tasks = ["scan 1", "save 1", "scan 2", "save 2"].map((name) => ({ name, ...task(log, name) }));

    const results = tasks.map((each) =>
      each.name.startsWith("scan") ? turns.scan(each.run, skipped) : turns.save(each.run),
    );
    for (const each of tasks) {
      await settle();
      each.finish();
    }

    expect(await Promise.all(results)).toEqual(["scan 1", "save 1", "scan 2", "save 2"]);
    expect(log).toEqual(tasks.flatMap((each) => [`${each.name} starts`, `${each.name} ends`]));
  });

  it("lets the next save run after one that failed, and hands the failure to its caller", async () => {
    const turns = createEngineTurns();

    const failed = turns.save(() => Promise.reject(new Error("the write failed")));
    const next = turns.save(async () => "written");

    await expect(failed).rejects.toThrow("the write failed");
    expect(await next).toBe("written");
  });

  it("holds a scan asked for after a cancel until the scan that had started has ended", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const first = task(log, "scan A");
    const waiting = task(log, "scan B");
    const later = task(log, "scan C");

    void turns.scan(first.run, skipped);
    const released = turns.scan(waiting.run, skipped);
    await settle();
    turns.cancel();
    expect(await released).toBe("skipped");
    const scanned = turns.scan(later.run, skipped);
    await settle();
    expect(log).toEqual(["scan A starts"]);

    first.finish();
    await settle();
    expect(log).toEqual(["scan A starts", "scan A ends", "scan C starts"]);
    later.finish();
    expect(await scanned).toBe("scan C");
  });

  it("holds a save asked for after a cancel until the scan that had started has ended", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const first = task(log, "scan A");
    const waiting = task(log, "scan B");
    const save = task(log, "save");

    void turns.scan(first.run, skipped);
    void turns.scan(waiting.run, skipped);
    await settle();
    turns.cancel();
    const saved = turns.save(save.run);
    await settle();
    expect(log).toEqual(["scan A starts"]);

    first.finish();
    await settle();
    expect(log).toEqual(["scan A starts", "scan A ends", "save starts"]);
    save.finish();
    expect(await saved).toBe("save");
  });

  it("holds a read of the project file until the scan asked for before it has ended", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const scan = task(log, "scan");
    const read = task(log, "read");

    void turns.scan(scan.run, skipped);
    const done = turns.read(read.run);
    await settle();
    expect(log).toEqual(["scan starts"]);

    scan.finish();
    await settle();
    expect(log).toEqual(["scan starts", "scan ends", "read starts"]);
    read.finish();
    expect(await done).toBe("read");
  });

  it("holds a scan until the reads in flight when it was asked for are done", async () => {
    const log: string[] = [];
    const turns = createEngineTurns();
    const first = task(log, "read 1");
    const second = task(log, "read 2");
    const scan = task(log, "scan");

    void turns.read(first.run);
    void turns.read(second.run);
    const scanned = turns.scan(scan.run, skipped);
    await settle();
    expect(log).toEqual(["read 1 starts", "read 2 starts"]);

    first.finish();
    await settle();
    expect(log).toEqual(["read 1 starts", "read 2 starts", "read 1 ends"]);
    second.finish();
    await settle();
    expect(log).toEqual(["read 1 starts", "read 2 starts", "read 1 ends", "read 2 ends", "scan starts"]);
    scan.finish();
    expect(await scanned).toBe("scan");
  });

  it("lets a scan run after a read that failed", async () => {
    const turns = createEngineTurns();

    const failed = turns.read(() => Promise.reject(new Error("the read failed")));
    const scanned = turns.scan(async () => "scanned", skipped);

    await expect(failed).rejects.toThrow("the read failed");
    expect(await scanned).toBe("scanned");
  });

  it("starts a scan at once when nothing is ahead of it, cancel or no cancel before", async () => {
    const turns = createEngineTurns();
    turns.cancel();

    expect(await turns.scan(async () => "scanned", skipped)).toBe("scanned");
  });
});
