import { describe, it, expect } from "vitest";
import { EventEmitter } from "node:events";
import { runEngine, type EngineSpawn } from "./run";
import type { EngineInvocation } from "../../shared/engine-api";

/** stdout/stderr を1回発火して close するモック spawn を作る。起動時の command/args を記録する。 */
function fakeSpawn(script: {
  stdout?: string;
  stderr?: string;
  code?: number;
  error?: Error;
}): { spawn: EngineSpawn; calls: { command: string; args: string[] }[] } {
  const calls: { command: string; args: string[] }[] = [];
  const spawn: EngineSpawn = (command, args) => {
    calls.push({ command, args });
    const proc = new EventEmitter() as unknown as EventEmitter & {
      stdout: EventEmitter;
      stderr: EventEmitter;
    };
    proc.stdout = new EventEmitter();
    proc.stderr = new EventEmitter();
    setImmediate(() => {
      if (script.error !== undefined) {
        proc.emit("error", script.error);
        return;
      }
      if (script.stdout !== undefined) proc.stdout.emit("data", script.stdout);
      if (script.stderr !== undefined) proc.stderr.emit("data", script.stderr);
      proc.emit("close", script.code ?? 0);
    });
    return proc as unknown as ReturnType<EngineSpawn>;
  };
  return { spawn, calls };
}

const launch = { command: "java", prefixArgs: ["-cp", "lib/*", "Main"] };

describe("runEngine", () => {
  it("prefixArgs にサブコマンド引数を続けて起動する", async () => {
    const { spawn, calls } = fakeSpawn({ stdout: '{"exitCode":0}' });
    const inv: EngineInvocation = {
      subcommand: "scan",
      request: { inputDir: "assets", db: "p.db" },
    };
    await runEngine({ spawn }, launch, inv);
    expect(calls).toHaveLength(1);
    expect(calls[0].command).toBe("java");
    expect(calls[0].args).toEqual(["-cp", "lib/*", "Main", "scan", "assets", "--db", "p.db"]);
  });

  it("stdout 末尾のサマリ JSON をパースし終了コードを返す", async () => {
    const { spawn } = fakeSpawn({
      stdout:
        "logback noise line\n" +
        '{"analyzed":["cobol/A.cbl"],"skipped":[],"removed":[],"findingCount":0,"exitCode":0}',
      code: 0,
    });
    const result = await runEngine(
      { spawn },
      launch,
      { subcommand: "scan", request: { inputDir: "assets" } },
    );
    expect(result.exitCode).toBe(0);
    expect(result.summary?.["analyzed"]).toEqual(["cobol/A.cbl"]);
    expect(result.subcommand).toBe("scan");
  });

  it("明示指定の出力先とサマリ報告の出力先を併合する(lint)", async () => {
    const { spawn } = fakeSpawn({
      stdout: '{"findingCount":3,"sarifFile":"out/lint.sarif","exitCode":1}',
      code: 1,
    });
    const result = await runEngine(
      { spawn },
      launch,
      { subcommand: "lint", request: { inputDir: "assets", sarifFile: "out/lint.sarif" } },
    );
    expect(result.exitCode).toBe(1);
    expect(result.outputs.sarif).toBe("out/lint.sarif");
  });

  it("非ゼロ終了でも解決し、exitCode を保つ", async () => {
    const { spawn } = fakeSpawn({ stdout: '{"exitCode":2}', code: 2 });
    const result = await runEngine(
      { spawn },
      launch,
      { subcommand: "lint", request: { inputDir: "assets" } },
    );
    expect(result.exitCode).toBe(2);
  });

  it("サマリ JSON が無い(callgraph ファイル出力)なら summary は null", async () => {
    const { spawn } = fakeSpawn({ stdout: "logback only, no json", code: 0 });
    const result = await runEngine(
      { spawn },
      launch,
      { subcommand: "callgraph", request: { inputDir: "assets", jsonFile: "out/cg.json" } },
    );
    expect(result.summary).toBeNull();
    expect(result.outputs.json).toBe("out/cg.json");
  });

  it("spawn の error を reject する", async () => {
    const { spawn } = fakeSpawn({ error: new Error("ENOENT java") });
    await expect(
      runEngine({ spawn }, launch, { subcommand: "scan", request: { inputDir: "assets" } }),
    ).rejects.toThrow("ENOENT java");
  });
});
