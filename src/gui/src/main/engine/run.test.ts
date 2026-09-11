import { describe, expect, it, vi } from "vitest";
import type { EngineProcess, EngineSpawn } from "./run";
import { runEngine } from "./run";

/** A fake child that replays canned stdout/stderr chunks and then closes with the given code. */
function fakeChild(script: {
  stdout?: (Buffer | string)[];
  stderr?: (Buffer | string)[];
  code?: number | null;
  error?: Error;
}): EngineProcess {
  const listeners: Record<string, ((value: never) => void)[]> = {};
  const stream = (chunks: (Buffer | string)[]) => ({
    on(_event: "data", listener: (chunk: Buffer | string) => void) {
      queueMicrotask(() => chunks.forEach(listener));
      return this;
    },
  });
  const child: EngineProcess = {
    stdout: stream(script.stdout ?? []),
    stderr: stream(script.stderr ?? []),
    on(event: "close" | "error", listener: (value: never) => void) {
      (listeners[event] ??= []).push(listener);
      return this;
    },
    kill: () => true,
  };
  // Fire terminal events after the data listeners have been attached and drained.
  queueMicrotask(() => {
    queueMicrotask(() => {
      if (script.error !== undefined) {
        listeners["error"]?.forEach((listener) => listener(script.error as never));
      } else {
        // `code: null` means "killed by a signal", so it must not collapse into 0.
        const code = "code" in script ? script.code : 0;
        listeners["close"]?.forEach((listener) => listener(code as never));
      }
    });
  });
  return child;
}

describe("runEngine", () => {
  it("passes the launch prefix in front of the subcommand arguments", async () => {
    const spawn = vi.fn<EngineSpawn>(() => fakeChild({ code: 0 }));
    await runEngine(
      { spawn },
      { command: "java", prefixArgs: ["-classpath", "lib/*", "Main"] },
      { subcommand: "rules", request: {} },
    );
    expect(spawn).toHaveBeenCalledWith(
      "java",
      ["-classpath", "lib/*", "Main", "rules"],
      expect.anything(),
    );
  });

  it("merges the requested outputs with the ones the summary reports", async () => {
    const spawn: EngineSpawn = () =>
      fakeChild({ stdout: ['{"sarifFile":"C:/actual.sarif"}\n'], code: 1 });
    const result = await runEngine({ spawn }, { command: "e", prefixArgs: [] }, {
      subcommand: "lint",
      request: { inputDir: "C:/a", sarifFile: "C:/requested.sarif" },
    });
    // The summary wins where both name the same artefact: it says where the file actually landed.
    expect(result.outputs.sarif).toBe("C:/actual.sarif");
    expect(result.summary).toEqual({ sarifFile: "C:/actual.sarif" });
  });

  it("resolves rather than rejects on a non-zero exit code, which carries severity", async () => {
    const spawn: EngineSpawn = () => fakeChild({ code: 2 });
    const result = await runEngine({ spawn }, { command: "e", prefixArgs: [] }, {
      subcommand: "lint",
      request: { inputDir: "C:/a" },
    });
    expect(result.exitCode).toBe(2);
  });

  it("reports -1 when the process was killed and gave no exit code", async () => {
    const spawn: EngineSpawn = () => fakeChild({ code: null });
    const result = await runEngine({ spawn }, { command: "e", prefixArgs: [] }, {
      subcommand: "scan",
      request: { inputDir: "C:/a" },
    });
    expect(result.exitCode).toBe(-1);
  });

  it("rejects when the process cannot be started at all", async () => {
    const spawn: EngineSpawn = () => fakeChild({ error: new Error("ENOENT") });
    await expect(
      runEngine({ spawn }, { command: "missing", prefixArgs: [] }, {
        subcommand: "rules",
        request: {},
      }),
    ).rejects.toThrow("ENOENT");
  });

  it("decodes a multi-byte character split across two chunks", async () => {
    const text = Buffer.from("受注データ\n", "utf8");
    const spawn: EngineSpawn = () =>
      fakeChild({ stdout: [text.subarray(0, 4), text.subarray(4)], code: 0 });
    const result = await runEngine({ spawn }, { command: "e", prefixArgs: [] }, {
      subcommand: "scan",
      request: { inputDir: "C:/a" },
    });
    expect(result.stdout).toBe("受注データ\n");
  });

  it("hands the started child to onStart so the caller can cancel it", async () => {
    const started: EngineProcess[] = [];
    const spawn: EngineSpawn = () => fakeChild({ code: 0 });
    await runEngine(
      { spawn, onStart: (child) => started.push(child) },
      { command: "e", prefixArgs: [] },
      { subcommand: "rules", request: {} },
    );
    expect(started).toHaveLength(1);
  });
});
