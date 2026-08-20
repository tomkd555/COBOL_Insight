import { describe, it, expect, vi } from "vitest";
import { resolve } from "node:path";
import type { EngineResult, SaveRequest } from "../../shared/engine-api";
import { parseSaveSummary, saveSource, type SaveDeps } from "./saveSource";

const INPUT_DIR = resolve("C:/資産/SYK");
const TEMP_FILE = resolve("C:/data/cobol-insight-edited.tmp");
const DB_PATH = resolve("C:/data/cobol-insight.db");

/** engine が書く要約 JSON(書き戻し済み・再パースの問題なし)。 */
function engineResult(summary: Record<string, unknown> | null, exitCode = 0): EngineResult {
  return { subcommand: "save", exitCode, summary, stdout: "", stderr: "", outputs: {} };
}

function fakeDeps(
  run: (request: SaveRequest) => Promise<EngineResult>,
  tempFile: () => string = () => TEMP_FILE,
): SaveDeps & { written: Record<string, string>; removed: string[] } {
  const written: Record<string, string> = {};
  const removed: string[] = [];
  return {
    written,
    removed,
    tempFile,
    dbPath: DB_PATH,
    fs: {
      // symlink の無い素の実体パスとして扱う。
      realPath: (absPath) => Promise.resolve(absPath),
      writeText: (absPath, text) => {
        written[absPath] = text;
        return Promise.resolve();
      },
      remove: (absPath) => {
        removed.push(absPath);
        return Promise.resolve();
      },
    },
    run,
  };
}

describe("saveSource", () => {
  it("編集後の全文を一時ファイルへ落とし、原本の絶対パスとともに save を起動する", async () => {
    const run = vi.fn().mockResolvedValue(
      engineResult({
        written: true,
        path: "C:/資産/SYK/cobol/SYK001.cbl",
        changedLineFrom: 12,
        changedLineTo: 14,
        reparseErrors: [],
        error: "",
        exitCode: 0,
      }),
    );
    const deps = fakeDeps(run);

    const result = await saveSource(deps, {
      inputDir: INPUT_DIR,
      path: "cobol/SYK001.cbl",
      editedText: "001000 IDENTIFICATION DIVISION.\n",
      codepage: "Shift_JIS",
      copybookPaths: ["C:\\資産\\copybook"],
    });

    expect(deps.written[TEMP_FILE]).toBe("001000 IDENTIFICATION DIVISION.\n");
    expect(run.mock.calls[0][0]).toEqual({
      file: resolve(INPUT_DIR, "cobol/SYK001.cbl"),
      editedFile: TEMP_FILE,
      codepage: "Shift_JIS",
      copybookPaths: ["C:\\資産\\copybook"],
      db: DB_PATH,
    });
    expect(result).toEqual({
      written: true,
      path: "C:/資産/SYK/cobol/SYK001.cbl",
      changedLineFrom: 12,
      changedLineTo: 14,
      reparseErrors: [],
      error: "",
      exitCode: 0,
    });
  });

  /** 書き先は利用者が画面で開いたパスである。読取と同じ関門で資産フォルダの外を拒む。 */
  it("資産フォルダの外を指すパスは engine を起動する前に拒む", async () => {
    const run = vi.fn();
    const deps = fakeDeps(run);

    await expect(
      saveSource(deps, {
        inputDir: INPUT_DIR,
        path: "..\\他人の資産\\SECRET.cbl",
        editedText: "書き換え",
      }),
    ).rejects.toThrow("資産フォルダの外");

    expect(run).not.toHaveBeenCalled();
    expect(deps.written).toEqual({});
  });

  it("再パースの誤りを行番号付きで返す", async () => {
    const deps = fakeDeps(() =>
      Promise.resolve(
        engineResult(
          {
            written: true,
            path: "C:/資産/SYK/copybook/SYKCPY1.cpy",
            changedLineFrom: 3,
            changedLineTo: 2,
            reparseErrors: [{ line: 3, message: "構文解析に失敗した" }],
            error: "",
            exitCode: 1,
          },
          1,
        ),
      ),
    );

    const result = await saveSource(deps, {
      inputDir: INPUT_DIR,
      path: "copybook/SYKCPY1.cpy",
      editedText: "01 REC.\n",
    });

    expect(result.written).toBe(true);
    expect(result.exitCode).toBe(1);
    expect(result.reparseErrors).toEqual([{ line: 3, message: "構文解析に失敗した" }]);
    // 挿入だけの編集では、変更した最終行が開始行の1つ前になる。
    expect(result.changedLineTo).toBe(2);
  });

  /** 名前を固定すると、保存が重なったとき後の保存が前の本文を上書きする。 */
  it("保存ごとに別の一時ファイルへ落とし、その1件だけを消す", async () => {
    let count = 0;
    const deps = fakeDeps(
      () =>
        Promise.resolve(
          engineResult({ written: true, path: "p", changedLineFrom: 1, changedLineTo: 1,
            reparseErrors: [], error: "", exitCode: 0 }),
        ),
      () => `${TEMP_FILE}.${(count += 1)}`,
    );

    await saveSource(deps, { inputDir: INPUT_DIR, path: "cobol/SYK001.cbl", editedText: "1件目" });
    await saveSource(deps, { inputDir: INPUT_DIR, path: "cobol/SYK002.cbl", editedText: "2件目" });

    expect(deps.written).toEqual({
      [`${TEMP_FILE}.1`]: "1件目",
      [`${TEMP_FILE}.2`]: "2件目",
    });
    expect(deps.removed).toEqual([`${TEMP_FILE}.1`, `${TEMP_FILE}.2`]);
  });

  it("起動が終われば一時ファイルを消す", async () => {
    const deps = fakeDeps(() =>
      Promise.resolve(
        engineResult({ written: true, path: "p", changedLineFrom: 1, changedLineTo: 1,
          reparseErrors: [], error: "", exitCode: 0 }),
      ),
    );
    await saveSource(deps, { inputDir: INPUT_DIR, path: "cobol/SYK001.cbl", editedText: "x" });
    expect(deps.removed).toEqual([TEMP_FILE]);
  });

  it("起動に失敗しても一時ファイルを残さない", async () => {
    const deps = fakeDeps(() => Promise.reject(new Error("解析エンジンを起動できない")));
    await expect(
      saveSource(deps, { inputDir: INPUT_DIR, path: "cobol/SYK001.cbl", editedText: "x" }),
    ).rejects.toThrow("解析エンジンを起動できない");
    expect(deps.removed).toEqual([TEMP_FILE]);
  });
});

describe("parseSaveSummary", () => {
  it("要約が無ければ、書き戻せたかを判断できないため例外にする", () => {
    expect(() => parseSaveSummary(null, 2)).toThrow("保存の結果");
  });

  it("欠けた欄と型の合わない欄を既定値へ落とす", () => {
    expect(parseSaveSummary({ written: false, error: "読み取れない" }, 2)).toEqual({
      written: false,
      path: "",
      changedLineFrom: 0,
      changedLineTo: 0,
      reparseErrors: [],
      error: "読み取れない",
      exitCode: 2,
    });
  });
});
