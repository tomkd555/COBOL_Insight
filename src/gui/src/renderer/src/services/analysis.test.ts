import { describe, it, expect, vi, beforeEach } from "vitest";
import type { CobolInsightApi, EngineResult } from "../../../shared/engine-api";
import { runAnalysis, type AnalysisHandlers, type AnalysisStage } from "./analysis";

const OUTPUT_PATHS = {
  db: "C:\\data\\cobol-insight.db",
  lintSarif: "C:\\data\\cobol-insight.sarif",
  sqlAdviseSarif: "C:\\data\\cobol-insight-sql.sarif",
  copyExpansion: "C:\\data\\cobol-insight-copy-expansion.json",
  userRules: "C:\\data\\user-rules.json",
  ruleConfig: "C:\\data\\rules-config.json",
};

const REQUEST = {
  inputDir: "C:\\資産\\SYK",
  copybookPaths: ["C:\\資産\\copybook"],
  codepageOverrides: { "cobol/SYK001.cbl": "Shift_JIS" },
};

function engineResult(overrides: Partial<EngineResult>): EngineResult {
  return {
    subcommand: "scan",
    exitCode: 0,
    summary: {},
    stdout: "",
    stderr: "",
    outputs: {},
    ...overrides,
  };
}

/** 呼ばれた順に段と結果を記録する呼び手。 */
function recorder(): AnalysisHandlers & { stages: AnalysisStage[]; log: string[] } {
  const stages: AnalysisStage[] = [];
  const log: string[] = [];
  return {
    stages,
    log,
    onStage: (stage) => {
      stages.push(stage);
      log.push(`stage:${stage}`);
    },
    onInventory: (result) => log.push(`inventory:${result.status}`),
    onFindings: (result) => log.push(`findings:${result.status}`),
    onSqlAdvice: (result) => log.push(`sqlAdvice:${result.status}`),
  };
}

let runScan: ReturnType<typeof vi.fn>;
let runLint: ReturnType<typeof vi.fn>;
let runSqlLint: ReturnType<typeof vi.fn>;
let readAssetInventory: ReturnType<typeof vi.fn>;
let readSarif: ReturnType<typeof vi.fn>;

beforeEach(() => {
  runScan = vi.fn().mockResolvedValue(
    engineResult({ outputs: { db: "proj.db" }, summary: { assets: 3 } }),
  );
  runLint = vi
    .fn()
    .mockResolvedValue(engineResult({ subcommand: "lint", outputs: { sarif: "lint.sarif" } }));
  runSqlLint = vi
    .fn()
    .mockResolvedValue(engineResult({ subcommand: "sql-lint", outputs: { sarif: "sql.sarif" } }));
  readAssetInventory = vi.fn().mockResolvedValue([]);
  readSarif = vi.fn().mockResolvedValue([]);
  window.cobolInsight = {
    getOutputPaths: vi.fn().mockResolvedValue(OUTPUT_PATHS),
    runScan,
    runLint,
    runSqlLint,
    readAssetInventory,
    readSarif,
  } as unknown as CobolInsightApi;
});

describe("runAnalysis", () => {
  it("走査・指摘の検出・SQL指摘の検出を順に走らせ、段ごとの結果を済んだ端から渡す", async () => {
    const handlers = recorder();
    const outcome = await runAnalysis(REQUEST, handlers);

    expect(handlers.log).toEqual([
      "stage:scan",
      "inventory:ready",
      "stage:lint",
      "findings:ready",
      "stage:sqlLint",
      "sqlAdvice:ready",
    ]);
    expect(outcome).toBe("completed");
  });

  it("資産フォルダ・コピー句探索パス・出力先を engine の引数へ渡す", async () => {
    await runAnalysis(REQUEST, recorder());

    expect(runScan.mock.calls[0][0]).toEqual({
      inputDir: REQUEST.inputDir,
      db: OUTPUT_PATHS.db,
      copyExpansion: OUTPUT_PATHS.copyExpansion,
      copybookPaths: REQUEST.copybookPaths,
      codepageOverrides: REQUEST.codepageOverrides,
    });
    expect(readAssetInventory).toHaveBeenCalledWith("proj.db");
    expect(readSarif.mock.calls.map((call) => call[0])).toEqual(["lint.sarif", "sql.sarif"]);
  });

  /** ルールの有効・無効は設定ファイルだけが決める。ID を1件ずつ渡す経路は残さない。 */
  it("ルールの設定ファイルを検出の両段へ渡す", async () => {
    await runAnalysis(REQUEST, recorder());

    expect(runLint.mock.calls[0][0].ruleConfigFile).toBe(OUTPUT_PATHS.ruleConfig);
    expect(runSqlLint.mock.calls[0][0].ruleConfigFile).toBe(OUTPUT_PATHS.ruleConfig);
    expect(runLint.mock.calls[0][0]).not.toHaveProperty("disabledRules");
  });

  /** 利用者定義ルールは lint が読む。渡し忘れると U 始まりのルールだけ検出されない。 */
  it("利用者定義ルールの定義ファイルを指摘の検出へ渡す", async () => {
    await runAnalysis(REQUEST, recorder());

    expect(runLint.mock.calls[0][0].userRulesFile).toBe(OUTPUT_PATHS.userRules);
  });

  it("走査に失敗しても後の段を走らせ、失敗として返す", async () => {
    runScan.mockRejectedValue(new Error("解析エンジンを起動できない"));
    const handlers = recorder();

    const outcome = await runAnalysis(REQUEST, handlers);

    expect(handlers.log).toEqual([
      "stage:scan",
      "inventory:error",
      "stage:lint",
      "findings:ready",
      "stage:sqlLint",
      "sqlAdvice:ready",
    ]);
    expect(outcome).toBe("failed");
  });

  /** 保存先が分からなければ資産一覧を読めない。0 件として黙って通さない。 */
  it("走査の保存先が返らなければ失敗として扱う", async () => {
    runScan.mockResolvedValue(engineResult({ outputs: {} }));
    const handlers = recorder();

    const outcome = await runAnalysis(REQUEST, handlers);

    expect(handlers.log).toContain("inventory:error");
    expect(readAssetInventory).not.toHaveBeenCalled();
    expect(outcome).toBe("failed");
  });

  /** 非ゼロ終了は構文解析に失敗した資産がある状態であり、一覧そのものは使える。 */
  it("走査の非ゼロ終了では一覧を渡したうえで失敗として返す", async () => {
    runScan.mockResolvedValue(engineResult({ exitCode: 2, outputs: { db: "proj.db" } }));
    const handlers = recorder();

    const outcome = await runAnalysis(REQUEST, handlers);

    expect(handlers.log).toContain("inventory:ready");
    expect(outcome).toBe("failed");
  });

  it("指摘の検出に失敗しても SQL指摘の検出を走らせる", async () => {
    runLint.mockRejectedValue(new Error("SARIF を書けない"));
    const handlers = recorder();

    const outcome = await runAnalysis(REQUEST, handlers);

    expect(handlers.log).toEqual([
      "stage:scan",
      "inventory:ready",
      "stage:lint",
      "findings:error",
      "stage:sqlLint",
      "sqlAdvice:ready",
    ]);
    expect(outcome).toBe("failed");
  });
});

describe("runAnalysis の取り消し", () => {
  /** 止めた後に engine を起こし直すと、取り消したはずの解析が続く。 */
  it("第1段の後に取り消すと、第2段を起こさず、済んだ分だけを残す", async () => {
    const handlers = recorder();
    let cancelled = false;
    runScan.mockImplementation(() => {
      cancelled = true;
      return Promise.resolve(engineResult({ outputs: { db: "proj.db" } }));
    });

    const outcome = await runAnalysis(REQUEST, handlers, () => cancelled);

    expect(outcome).toBe("cancelled");
    expect(runLint).not.toHaveBeenCalled();
    expect(runSqlLint).not.toHaveBeenCalled();
    // 殺した子プロセスの成果物を新しい結果として読まない。
    expect(readAssetInventory).not.toHaveBeenCalled();
    expect(handlers.log).toEqual(["stage:scan"]);
  });

  /** 子プロセスを殺された起動は終了コードが負になる。engine 自身は負を返さない。 */
  it("殺された子プロセスは失敗ではなく取り消しとして扱う", async () => {
    const handlers = recorder();
    runScan.mockResolvedValue(engineResult({ exitCode: -1, outputs: { db: "proj.db" } }));

    const outcome = await runAnalysis(REQUEST, handlers, () => false);

    expect(outcome).toBe("cancelled");
    expect(handlers.log).toEqual(["stage:scan"]);
    expect(runLint).not.toHaveBeenCalled();
  });

  it("第2段の後に取り消すと、SQL指摘の検出を起こさない", async () => {
    const handlers = recorder();
    let cancelled = false;
    runLint.mockImplementation(() => {
      cancelled = true;
      return Promise.resolve(
        engineResult({ subcommand: "lint", outputs: { sarif: "lint.sarif" } }),
      );
    });

    const outcome = await runAnalysis(REQUEST, handlers, () => cancelled);

    expect(outcome).toBe("cancelled");
    expect(runSqlLint).not.toHaveBeenCalled();
    expect(handlers.log).toEqual(["stage:scan", "inventory:ready", "stage:lint"]);
  });
});
