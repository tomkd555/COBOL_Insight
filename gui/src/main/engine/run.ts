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
  /** 実行中のプロセスを終了させる。終了済みなら false を返す。 */
  kill(): boolean;
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
  /** spawn 直後に呼ぶ。呼び手はここで受けた子プロセスを控え、キャンセルと終了時の後始末に使う。 */
  onStart?: (child: EngineProcess) => void;
}

/**
 * engine CLI を1回起動し、stdout 末尾のサマリ JSON・終了コード・成果物パスを {@link EngineResult}
 * として返す。ソケット・HTTP は用いず、child_process.spawn とファイル出力のみで完結する。
 * 出力先は「リクエストで明示指定したパス」と「サマリ JSON が報告したパス」を併合して確定する。
 *
 * engine は検出結果の重大度を終了コードへ載せる(0=指摘なし、1=警告あり、2=エラーあり)。
 * 非ゼロは実行の失敗を意味しないため reject せず結果として返し、起動そのものに失敗したとき
 * (実行ファイルが無い等の spawn の error)だけ reject する。
 */
/**
 * 受け取った全チャンクを連結してから一度だけ UTF-8 として復号する。チャンクごとに復号すると、
 * 多バイト文字がチャンク境界で分割された場合にその文字が置換文字へ落ちる。engine は標準出力・
 * 標準エラーを UTF-8 で書く。
 */
function decodeChunks(chunks: (Buffer | string)[]): string {
  if (chunks.every((chunk) => typeof chunk === "string")) {
    return chunks.join("");
  }
  return Buffer.concat(
    chunks.map((chunk) => (typeof chunk === "string" ? Buffer.from(chunk, "utf8") : chunk)),
  ).toString("utf8");
}

export function runEngine(
  deps: RunEngineDeps,
  launch: EngineLaunch,
  invocation: EngineInvocation,
): Promise<EngineResult> {
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
        // シグナルで終了した場合 code は null になる。engine 自身は負の値を返さないため、
        // -1 を「終了コードを得られなかった」の印として使う。
        exitCode: code ?? -1,
        summary,
        stdout,
        stderr,
        outputs: { ...collectRequestedOutputs(invocation), ...summaryOutputs(summary) },
      });
    });
  });
}
