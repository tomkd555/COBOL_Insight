import type { EngineInvocation, EngineResult } from "../../shared/engine-api";
import { buildEngineArgs, collectRequestedOutputs } from "./args";
import { extractSummaryJson, summaryOutputs } from "./summary";
import type { EngineLaunch } from "./launch";

/** spawn した子プロセスの、runEngine が要する最小の形。child_process.ChildProcess が満たす。 */
export interface EngineProcessStream {
  on(event: "data", listener: (chunk: Buffer | string) => void): unknown;
}

export interface EngineProcess {
  stdout: EngineProcessStream | null;
  stderr: EngineProcessStream | null;
  on(event: "close", listener: (code: number | null) => void): unknown;
  on(event: "error", listener: (error: Error) => void): unknown;
}

/** 子プロセス起動関数。テストではモックを注入し、本番は child_process.spawn を薄く包む。 */
export type EngineSpawn = (
  command: string,
  args: string[],
  options: { env?: NodeJS.ProcessEnv; cwd?: string },
) => EngineProcess;

export interface RunEngineDeps {
  spawn: EngineSpawn;
  /** 子プロセスへ渡す環境変数(開発時は JAVA_HOME を含める)。 */
  env?: NodeJS.ProcessEnv;
  /** 子プロセスの作業ディレクトリ。相対の出力先パスの基準になる。 */
  cwd?: string;
}

/**
 * engine CLI を1回起動し、stdout 末尾のサマリ JSON・終了コード・成果物パスを {@link EngineResult}
 * として返す。ソケット・HTTP は用いず、child_process.spawn とファイル出力のみで完結する。
 * 出力先は「リクエストで明示指定したパス」と「サマリ JSON が報告したパス」を併合して確定する。
 */
export function runEngine(
  deps: RunEngineDeps,
  launch: EngineLaunch,
  invocation: EngineInvocation,
): Promise<EngineResult> {
  const args = [...launch.prefixArgs, ...buildEngineArgs(invocation)];
  return new Promise<EngineResult>((resolve, reject) => {
    const child = deps.spawn(launch.command, args, { env: deps.env, cwd: deps.cwd });
    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr?.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", (error) => {
      reject(error);
    });
    child.on("close", (code) => {
      const summary = extractSummaryJson(stdout);
      resolve({
        subcommand: invocation.subcommand,
        exitCode: code ?? -1,
        summary,
        stdout,
        stderr,
        outputs: { ...collectRequestedOutputs(invocation), ...summaryOutputs(summary) },
      });
    });
  });
}
