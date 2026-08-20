import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ENGINE_CHANNELS, type EngineInvocation, type EngineResult } from "../shared/engine-api";
import type { EngineProcess, RunEngineDeps } from "./engine/run";
import { registerEngineIpc } from "./ipc";

/**
 * IPC ハンドラの取り回し。engine の起動は差し替え、書き戻しの経路(一時ファイル・保存の直列化・
 * キャンセルの対象外)を確かめる。原本の境界検査は実際のファイルを通すため、本物の一時ディレクトリを
 * 資産フォルダとして使う。
 */

/** engine 起動1回分。テストが finish を呼ぶまで終わらない。 */
interface PendingRun {
  invocation: EngineInvocation;
  child: { killed: boolean; kill(): boolean };
  finish(summary: Record<string, unknown> | null): void;
}

const harness = vi.hoisted(() => ({
  userData: "",
  handlers: new Map<string, (event: unknown, ...args: never[]) => unknown>(),
  runs: [] as PendingRun[],
}));

vi.mock("electron", () => ({
  app: {
    getPath: () => harness.userData,
    getAppPath: () => harness.userData,
    isPackaged: false,
  },
  ipcMain: {
    handle: (channel: string, handler: (event: unknown, ...args: never[]) => unknown) => {
      harness.handlers.set(channel, handler);
    },
  },
  dialog: {},
}));

vi.mock("./engine/run", () => ({
  runEngine: (deps: RunEngineDeps, _launch: unknown, invocation: EngineInvocation) =>
    new Promise<EngineResult>((resolve) => {
      const child = {
        killed: false,
        kill(): boolean {
          child.killed = true;
          return true;
        },
      };
      deps.onStart?.(child as unknown as EngineProcess);
      harness.runs.push({
        invocation,
        child,
        finish: (summary) =>
          resolve({
            subcommand: invocation.subcommand,
            exitCode: 0,
            summary,
            stdout: "",
            stderr: "",
            outputs: {},
          }),
      });
    }),
}));

/** save が返す要約。 */
const SAVE_SUMMARY = {
  written: true,
  path: "p",
  changedLineFrom: 1,
  changedLineTo: 1,
  reparseErrors: [],
  error: "",
  exitCode: 0,
};

let inputDir = "";

function call(channel: string, ...args: never[]): Promise<unknown> {
  const handler = harness.handlers.get(channel);
  if (handler === undefined) {
    throw new Error(`未登録のチャネル: ${channel}`);
  }
  return Promise.resolve(handler(null, ...args));
}

function save(path: string, editedText: string): Promise<unknown> {
  return call(ENGINE_CHANNELS.saveSource, { inputDir, path, editedText } as never);
}

/** 起動が n 件たまるまで待つ。 */
function runsReach(count: number): Promise<void> {
  return vi.waitFor(() => expect(harness.runs.length).toBe(count));
}

beforeEach(async () => {
  harness.handlers.clear();
  harness.runs = [];
  harness.userData = await mkdtemp(join(tmpdir(), "ci-userdata-"));
  inputDir = await mkdtemp(join(tmpdir(), "ci-assets-"));
  await mkdir(join(inputDir, "cobol"));
  await writeFile(join(inputDir, "cobol", "SYK001.cbl"), "       DISPLAY 1.\n", "utf-8");
  await writeFile(join(inputDir, "cobol", "SYK002.cbl"), "       DISPLAY 2.\n", "utf-8");
  registerEngineIpc();
});

afterEach(async () => {
  await rm(harness.userData, { recursive: true, force: true });
  await rm(inputDir, { recursive: true, force: true });
});

describe("書き戻しの直列化", () => {
  it("重なった保存を順に走らせ、別々の一時ファイルへ落とす", async () => {
    const first = save("cobol/SYK001.cbl", "1件目\n");
    const second = save("cobol/SYK002.cbl", "2件目\n");

    // 1件目が終わるまで2件目の engine は起きない。
    await runsReach(1);
    harness.runs[0].finish(SAVE_SUMMARY);
    await first;

    await runsReach(2);
    harness.runs[1].finish(SAVE_SUMMARY);
    await second;

    const [one, two] = harness.runs.map((run) =>
      run.invocation.subcommand === "save" ? run.invocation.request.editedFile : "",
    );
    expect(one).not.toBe(two);
  });

  it("先の保存が失敗しても次の保存を走らせる", async () => {
    const first = save("cobol/SYK001.cbl", "1件目\n");
    const second = save("cobol/SYK002.cbl", "2件目\n");

    await runsReach(1);
    // 要約を返さない = 書き戻せたかを確かめられない失敗。
    harness.runs[0].finish(null);
    await expect(first).rejects.toThrow();

    await runsReach(2);
    harness.runs[1].finish(SAVE_SUMMARY);
    await expect(second).resolves.toMatchObject({ written: true });
  });
});

describe("キャンセルの対象", () => {
  /** 保存を殺すと、原本へ半端な本文が残る。キャンセルは解析だけを止める。 */
  it("保存の途中でキャンセルしても、保存の子プロセスは殺さない", async () => {
    const saving = save("cobol/SYK001.cbl", "編集後\n");
    await runsReach(1);

    await call(ENGINE_CHANNELS.cancelRun);

    expect(harness.runs[0].child.killed).toBe(false);
    harness.runs[0].finish(SAVE_SUMMARY);
    await expect(saving).resolves.toMatchObject({ written: true });
  });

  it("解析の途中でキャンセルすると、解析の子プロセスを殺す", async () => {
    const scanning = call(ENGINE_CHANNELS.runScan, { inputDir } as never);
    await runsReach(1);

    await call(ENGINE_CHANNELS.cancelRun);

    expect(harness.runs[0].child.killed).toBe(true);
    harness.runs[0].finish({});
    await scanning;
  });
});
