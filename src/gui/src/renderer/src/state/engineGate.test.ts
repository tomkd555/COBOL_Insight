import { describe, expect, it } from "vitest";
import { viewRun, viewRunsSettled } from "./engineGate";

describe("viewRun", () => {
  it("runs the requests one after another, in the order they were asked for", async () => {
    const order: string[] = [];
    let releaseFirst = (): void => undefined;
    const first = viewRun(
      () =>
        new Promise<void>((resolve) => {
          order.push("first starts");
          releaseFirst = () => {
            order.push("first ends");
            resolve();
          };
        }),
    );
    const second = viewRun(async () => {
      order.push("second starts");
      return 2;
    });

    await Promise.resolve();
    expect(order).toEqual(["first starts"]);
    releaseFirst();
    await first;
    expect(await second).toBe(2);
    expect(order).toEqual(["first starts", "first ends", "second starts"]);
  });

  it("keeps the queue going after a run that failed, handing the failure to its caller", async () => {
    const failed = viewRun(() => Promise.reject(new Error("crashed")));
    const next = viewRun(async () => "ran");

    await expect(failed).rejects.toThrow("crashed");
    expect(await next).toBe("ran");
  });
});

describe("viewRunsSettled", () => {
  it("resolves once the queue has drained after a run that failed", async () => {
    const failed = viewRun(() => Promise.reject(new Error("crashed")));
    const next = viewRun(async () => "ran");

    await expect(failed).rejects.toThrow("crashed");
    expect(await next).toBe("ran");
    await expect(viewRunsSettled()).resolves.toBeUndefined();
  });
});
