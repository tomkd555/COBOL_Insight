import { app, dialog, ipcMain } from "electron";
import { spawn } from "node:child_process";
import { readdir, readFile, realpath } from "node:fs/promises";
import { createRequire } from "node:module";
import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
import {
  ENGINE_CHANNELS,
  type CallgraphRequest,
  type EngineInvocation,
  type EngineResult,
  type FixApplyRequest,
  type FixPreviewRequest,
  type FixResultRequest,
  type LintRequest,
  type ReportRequest,
  type ScanRequest,
  type SourceTextRequest,
  type SqlAdviseRequest,
  type TranspileArtifacts,
  type TranspileArtifactsRequest,
  type TranspileRequest,
} from "../shared/engine-api";
import { runEngine, type EngineProcess, type EngineSpawn } from "./engine/run";
import { resolveEngineLaunch, type EngineLaunch } from "./engine/launch";
import { parseSarif } from "./artifacts/sarif";
import { parseCallgraph } from "./artifacts/callgraph";
import { buildFixDiff } from "./artifacts/fix";
import { readInventory } from "./artifacts/inventory";
import { readSourceText, type SourceFileSystem } from "./artifacts/sourceText";
import { readGeneratedFiles, readLineMap, type GeneratedFileSystem } from "./artifacts/transpile";
import { selectInputFolder } from "./dialog/selectFolder";

const nodeRequire = createRequire(import.meta.url);

/** child_process.spawn を EngineSpawn へ薄く適合させる(stdio は既定の pipe)。 */
const engineSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, {
    env: options.env,
    cwd: options.cwd,
  }) as unknown as EngineProcess;

/** engine 起動対象を現在の実行環境から解決する。 */
function currentLaunch(): EngineLaunch {
  return resolveEngineLaunch({
    isPackaged: app.isPackaged,
    platform: process.platform,
    resourcesPath: process.resourcesPath,
    appRoot: app.getAppPath(),
    javaHome: process.env["JAVA_HOME"],
  });
}

function invoke(invocation: EngineInvocation): Promise<EngineResult> {
  return runEngine({ spawn: engineSpawn, env: process.env }, currentLaunch(), invocation);
}

let sqlJs: SqlJsStatic | null = null;

/** sql.js を1度だけ初期化する。wasm はローカル同梱物から読み、外部取得しない。 */
async function loadSqlJs(): Promise<SqlJsStatic> {
  if (sqlJs === null) {
    const wasmPath = nodeRequire.resolve("sql.js/dist/sql-wasm.wasm");
    sqlJs = await initSqlJs({ locateFile: () => wasmPath });
  }
  return sqlJs;
}

/** SQLite ファイルを読み取り専用に開いて読取関数へ渡し、必ず閉じる。 */
async function withDatabase<T>(dbPath: string, read: (db: Database) => T): Promise<T> {
  const SQL = await loadSqlJs();
  const database = new SQL.Database(await readFile(dbPath));
  try {
    return read(database);
  } finally {
    database.close();
  }
}

/** transpile 生成物の読取に使う fs 束ね。出力先直下の平坦なファイルだけを対象にする。 */
const generatedFileSystem: GeneratedFileSystem = {
  list: (dir) => readdir(dir),
  readText: (absPath) => readFile(absPath, "utf-8"),
};

/** ソース本文の読取に使う fs 束ね。境界判定のため実体パスの解決も渡す。 */
const sourceFileSystem: SourceFileSystem = {
  readBytes: (absPath) => readFile(absPath),
  realPath: (absPath) => realpath(absPath),
};

/**
 * renderer→main の IPC ハンドラを登録する。各 run ハンドラは engine CLI を spawn し、read ハンドラは
 * 成果物ファイルを読んでパースする。ソケット・HTTP は用いない。app.whenReady 後に1度だけ呼ぶ。
 */
export function registerEngineIpc(): void {
  ipcMain.handle(ENGINE_CHANNELS.runScan, (_event, request: ScanRequest) =>
    invoke({ subcommand: "scan", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runCallgraph, (_event, request: CallgraphRequest) =>
    invoke({ subcommand: "callgraph", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runLint, (_event, request: LintRequest) =>
    invoke({ subcommand: "lint", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runSqlAdvise, (_event, request: SqlAdviseRequest) =>
    invoke({ subcommand: "sql-advise", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runReport, (_event, request: ReportRequest) =>
    invoke({ subcommand: "report", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runTranspile, (_event, request: TranspileRequest) =>
    invoke({ subcommand: "transpile", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runFixPreview, (_event, request: FixPreviewRequest) =>
    invoke({ subcommand: "fix-preview", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runFixApply, (_event, request: FixApplyRequest) =>
    invoke({ subcommand: "fix-apply", request }),
  );

  ipcMain.handle(ENGINE_CHANNELS.selectInputFolder, () =>
    selectInputFolder(() => dialog.showOpenDialog({ properties: ["openDirectory"] })),
  );

  ipcMain.handle(ENGINE_CHANNELS.readSarif, async (_event, path: string) =>
    parseSarif(await readFile(path, "utf-8")),
  );
  ipcMain.handle(ENGINE_CHANNELS.readCallgraphJson, async (_event, path: string) =>
    parseCallgraph(await readFile(path, "utf-8")),
  );
  ipcMain.handle(ENGINE_CHANNELS.readFixResult, async (_event, request: FixResultRequest) => {
    const [original, fixed] = await Promise.all([
      readFile(request.originalPath, "utf-8"),
      readFile(request.fixedPath, "utf-8"),
    ]);
    return buildFixDiff(request.relPath, original, fixed);
  });
  ipcMain.handle(ENGINE_CHANNELS.readReportHtml, (_event, path: string) =>
    readFile(path, "utf-8"),
  );
  ipcMain.handle(ENGINE_CHANNELS.readReportText, (_event, path: string) =>
    readFile(path, "utf-8"),
  );
  ipcMain.handle(ENGINE_CHANNELS.readAssetInventory, (_event, dbPath: string) =>
    withDatabase(dbPath, readInventory),
  );

  ipcMain.handle(ENGINE_CHANNELS.readSourceText, (_event, request: SourceTextRequest) =>
    readSourceText(sourceFileSystem, request),
  );

  ipcMain.handle(
    ENGINE_CHANNELS.readTranspileArtifacts,
    async (_event, request: TranspileArtifactsRequest): Promise<TranspileArtifacts> => {
      const lineMap = await withDatabase(request.dbPath, (db) =>
        readLineMap(db, request.cobolRelPath),
      );
      const files = await readGeneratedFiles(generatedFileSystem, request.outDir, lineMap);
      return { files, lineMap };
    },
  );
}
