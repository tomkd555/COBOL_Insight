import type { EngineInvocation, EngineResult } from "../../shared/ipc";
import { buildEngineArgs, collectRequestedOutputs } from "./args";
import { extractSummaryJson, summaryOutputs } from "./summary";
import type { EngineLaunch } from "./launch";

/** The minimum of a spawned child's stream that runEngine uses; ChildProcess satisfies it. */
export interface EngineProcessStream {
  on(event: "data", listener: (chunk: Buffer | string) => void): unknown;
}

export interface EngineProcess {
  stdout: EngineProcessStream | null;
  stderr: EngineProcessStream | null;
  on(event: "close", listener: (code: number | null) => void): unknown;
  on(event: "error", listener: (error: Error) => void): unknown;
  /** Terminates the process; returns false when it had already exited. */
  kill(): boolean;
}

/** The spawn function. Tests inject a fake; production wraps child_process.spawn. */
export type EngineSpawn = (
  command: string,
  args: string[],
  options: { env?: NodeJS.ProcessEnv; cwd?: string },
) => EngineProcess;

export interface RunEngineDeps {
  spawn: EngineSpawn;
  /** Environment for the child (JAVA_HOME in development). */
  env?: NodeJS.ProcessEnv;
  /** Working directory of the child; relative output paths resolve against it. */
  cwd?: string;
  /** Called right after spawn so the caller can hold the child for cancellation and cleanup. */
  onStart?: (child: EngineProcess) => void;
}

/** Progress callback signature. Nothing calls it yet; it reserves the shape for streamed progress. */
export type EngineProgress = (line: string) => void;

/**
 * Concatenates every chunk before decoding once as UTF-8. Decoding chunk by chunk would turn any
 * multi-byte character split across a chunk boundary into a replacement character. The engine writes
 * both stdout and stderr as UTF-8.
 */
function decodeChunks(chunks: (Buffer | string)[]): string {
  if (chunks.every((chunk) => typeof chunk === "string")) {
    return chunks.join("");
  }
  return Buffer.concat(
    chunks.map((chunk) => (typeof chunk === "string" ? Buffer.from(chunk, "utf8") : chunk)),
  ).toString("utf8");
}

/**
 * Runs the engine CLI once and returns its summary JSON, exit code and artefact paths. Only
 * child_process.spawn and file output are involved; there is no socket and no HTTP.
 *
 * The engine puts finding severity in its exit code (0 = clean, 1 = warnings, 2 = errors), so a
 * non-zero code is not a failure and does not reject. Only a failure to start the process at all
 * (a missing executable, say) rejects.
 */
export function runEngine(
  deps: RunEngineDeps,
  launch: EngineLaunch,
  invocation: EngineInvocation,
  onProgress?: EngineProgress,
): Promise<EngineResult> {
  void onProgress;
  const args = [...launch.prefixArgs, ...buildEngineArgs(invocation)];
  return new Promise<EngineResult>((resolve, reject) => {
    const child = deps.spawn(launch.command, args, { env: deps.env, cwd: deps.cwd });
    deps.onStart?.(child);
    const outChunks: (Buffer | string)[] = [];
    const errChunks: (Buffer | string)[] = [];
    child.stdout?.on("data", (chunk) => {
      outChunks.push(chunk);
    });
    child.stderr?.on("data", (chunk) => {
      errChunks.push(chunk);
    });
    child.on("error", (error) => {
      reject(error);
    });
    child.on("close", (code) => {
      const stdout = decodeChunks(outChunks);
      const stderr = decodeChunks(errChunks);
      const summary = extractSummaryJson(stdout);
      resolve({
        subcommand: invocation.subcommand,
        // A process killed by a signal reports null. The engine never returns a negative code, so
        // -1 marks "no exit code was available".
        exitCode: code ?? -1,
        summary,
        stdout,
        stderr,
        outputs: { ...collectRequestedOutputs(invocation), ...summaryOutputs(summary) },
      });
    });
  });
}
