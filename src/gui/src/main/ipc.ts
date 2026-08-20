import { app, dialog, ipcMain } from "electron";
import { spawn } from "node:child_process";
import { access, mkdir, readdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { join } from "node:path";
import initSqlJs, { type Database, type SqlJsStatic } from "sql.js";
import {
  COPY_EXPANSION_FILE_NAME,
  ENGINE_CHANNELS,
  type CallgraphRequest,
  type EngineInvocation,
  type EngineOutputPaths,
  type EngineResult,
  type FixApplyRequest,
  type FixPreviewRequest,
  type FixResultRequest,
  type ImportSourceRequest,
  type LintRequest,
  type ReportRequest,
  type RuleConfigFile,
  type RulesRequest,
  type SaveSourceRequest,
  type ScanRequest,
  type SourceTextRequest,
  type SqlAdviseRequest,
  type TranspileArtifacts,
  type TranspileArtifactsRequest,
  type TranspileRequest,
  type UserRulesFile,
} from "../shared/engine-api";
import { runEngine, type EngineProcess, type EngineSpawn } from "./engine/run";
import { resolveEngineLaunch, type EngineLaunch } from "./engine/launch";
import { parseSarif } from "./artifacts/sarif";
import { parseCopyExpansion } from "./artifacts/copyExpansion";
import { buildFixDiff } from "./artifacts/fix";
import { readInventory } from "./artifacts/inventory";
import { readGraph } from "./artifacts/graph";
import { readSourceText, type SourceFileSystem } from "./artifacts/sourceText";
import { saveSource, type SaveFileSystem } from "./artifacts/saveSource";
import {
  RULE_CONFIG_FILE_NAME,
  migrateDisabledRules,
  readRuleConfig,
  writeRuleConfig,
  type RuleConfigFileSystem,
} from "./artifacts/ruleConfig";
import { importSource, type ImportFileSystem } from "./artifacts/importSource";
import { readGeneratedFiles, readLineMap, type GeneratedFileSystem } from "./artifacts/transpile";
import { parseRuleCatalog } from "../shared/ruleCatalog";
import {
  readUserRules,
  writeUserRules,
  type UserRuleFileSystem,
} from "./artifacts/userRules";
import {
  SETTINGS_FILE_NAME,
  readSettings,
  writeSettings,
  type SettingsFileSystem,
} from "./artifacts/settings";
import type { AppSettings } from "../shared/appSettings";
import { selectInputFolder } from "./dialog/selectFolder";
import { checkDirectoryExists, type DirectoryStat } from "./pathCheck";

const nodeRequire = createRequire(import.meta.url);

/**
 * child_process.spawn を EngineSpawn へ薄く適合させる(stdio は既定の pipe)。shell を介さず
 * 引数配列のまま渡すため、パスやオプション値に空白・記号が含まれてもシェルは解釈しない。
 */
const engineSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, {
    env: options.env,
    cwd: options.cwd,
  }) as unknown as EngineProcess;

/**
 * engine の成果物を書く位置。userData は起動時に展開先直下の data/ へ向けてあり(書込不可なら
 * Electron 既定へ委ねる)、配布・開発のどちらでも書込可能な1つのディレクトリに定まる。
 */
function outputPaths(): EngineOutputPaths {
  const dir = app.getPath("userData");
  return {
    db: join(dir, "cobol-insight.db"),
    lintSarif: join(dir, "cobol-insight.sarif"),
    // lint と別名にする。同名にすると後段の sql-lint が lint の結果を上書きする。
    sqlAdviseSarif: join(dir, "cobol-insight-sql.sarif"),
    copyExpansion: join(dir, COPY_EXPANSION_FILE_NAME),
    // 解析成果物ではなく利用者が作る設定であるが、engine へ渡す位置を1か所に定めるため併せて持つ。
    userRules: join(dir, "user-rules.json"),
    ruleConfig: join(dir, RULE_CONFIG_FILE_NAME),
  };
}

/** 編集後の本文を engine の save へ渡すための一時ファイル。保存のたびに書き、終われば消す。 */
function editedTempPath(): string {
  return join(app.getPath("userData"), "cobol-insight-edited.tmp");
}

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

/**
 * 実行中の engine。同時に走るのは1つであり、キャンセルとアプリ終了時の後始末で参照する。
 * 起動が終われば null へ戻す。
 */
let runningEngine: EngineProcess | null = null;

function invoke(invocation: EngineInvocation): Promise<EngineResult> {
  let started: EngineProcess | null = null;
  const deps = {
    spawn: engineSpawn,
    env: process.env,
    // 出力先は引数で絶対指定するが、engine 側に既定値の経路が残った場合に備え、作業ディレクトリも
    // 書込可能な保存先へ向けておく。
    cwd: app.getPath("userData"),
    onStart: (child: EngineProcess): void => {
      started = child;
      runningEngine = child;
    },
  };
  return runEngine(deps, currentLaunch(), invocation).finally(() => {
    if (runningEngine === started) {
      runningEngine = null;
    }
  });
}

/**
 * 実行中の engine を止める。キャンセル操作とアプリ終了の双方から呼ぶ。止めないと、画面が
 * 待機状態を解いた後も解析が走り続け、次の起動と二重に動く。
 */
export function stopRunningEngine(): void {
  runningEngine?.kill();
  runningEngine = null;
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

/**
 * SQLite ファイルをバイト列として読み、メモリ上の DB として開いて読取関数へ渡し、必ず閉じる。
 * sql.js は読んだ複製を扱うため、engine が書くプロジェクトファイルへ書き戻すことはない。
 */
async function withDatabase<T>(dbPath: string, read: (db: Database) => T): Promise<T> {
  const SQL = await loadSqlJs();
  const database = new SQL.Database(await readFile(dbPath));
  try {
    return read(database);
  } finally {
    database.close();
  }
}

/** translate 生成物の読取に使う fs 束ね。出力先直下の平坦なファイルだけを対象にする。 */
const generatedFileSystem: GeneratedFileSystem = {
  list: (dir) => readdir(dir),
  readText: (absPath) => readFile(absPath, "utf-8"),
};

/** ソース本文の読取に使う fs 束ね。境界判定のため実体パスの解決も渡す。 */
const sourceFileSystem: SourceFileSystem = {
  readBytes: (absPath) => readFile(absPath),
  realPath: (absPath) => realpath(absPath),
};

/** 書き戻しに使う fs 束ね。境界判定のため実体パスの解決も渡す。 */
const saveFileSystem: SaveFileSystem = {
  realPath: (absPath) => realpath(absPath),
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

/**
 * 利用者定義ルールの定義ファイル・ルールの有効無効の設定・画面の設定の読み書きに使う fs 束ね。
 * 書き先はいずれも userData 配下であり、パスは main が決める(renderer から任意のパスは受けない)。
 */
const userDataFileSystem: UserRuleFileSystem & SettingsFileSystem & RuleConfigFileSystem = {
  readText: (path) => readFile(path, "utf-8"),
  writeText: (path, text) => writeFile(path, text, "utf-8"),
  exists: (path) => access(path).then(() => true, () => false),
};

/** 画面の設定の保存先。userData 直下に置き、engine は触らない。 */
function settingsPath(): string {
  return join(app.getPath("userData"), SETTINGS_FILE_NAME);
}

/**
 * 旧版が settings.json へ書いていた無効ルールを rules-config.json へ移す。起動のたびに呼ぶが、
 * 設定ファイルが既にあれば何もしない。失敗しても起動を止めない(移せなければ、利用者が
 * ルール一覧で無効にし直せる)。
 */
export async function migrateRuleConfig(): Promise<void> {
  await migrateDisabledRules(userDataFileSystem, settingsPath(), outputPaths().ruleConfig);
}

/** コピー句探索パスの実在確認に使う fs 束ね。 */
const directoryStat: DirectoryStat = {
  stat: (path) => stat(path),
};

/** 取込の書出に使う fs 束ね。書き先は資産フォルダ配下に限る(境界判定は importSource が行う)。 */
const importFileSystem: ImportFileSystem = {
  makeDir: async (absPath) => {
    await mkdir(absPath, { recursive: true });
  },
  exists: (absPath) => access(absPath).then(() => true, () => false),
  realPath: (absPath) => realpath(absPath),
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
};

/**
 * renderer→main の IPC ハンドラを登録する。各 run ハンドラは engine CLI を spawn し、read ハンドラは
 * 成果物ファイルを読んでパースする。ソケット・HTTP は用いない。app.whenReady 後に1度だけ呼ぶ。
 *
 * read ハンドラが受け取るパスは、直前の起動が返した成果物パス(EngineResult.outputs)を renderer が
 * 持ち回った値である。これに対しソース本文の読取だけは利用者が画面で選ぶ任意のパスを受けるため、
 * readSourceText 側で資産フォルダ配下に限る境界検査を行う。取込の書出も同じく利用者の入力を
 * 受けるため、importSource 側で資産フォルダ配下に限る境界検査を行う。
 */
export function registerEngineIpc(): void {
  ipcMain.handle(ENGINE_CHANNELS.runScan, (_event, request: ScanRequest) =>
    invoke({ subcommand: "scan", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runCallgraph, (_event, request: CallgraphRequest) =>
    invoke({ subcommand: "call-graph", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runLint, (_event, request: LintRequest) =>
    invoke({ subcommand: "lint", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runSqlLint, (_event, request: SqlAdviseRequest) =>
    invoke({ subcommand: "sql-lint", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runReport, (_event, request: ReportRequest) =>
    invoke({ subcommand: "report", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runTranspile, (_event, request: TranspileRequest) =>
    invoke({ subcommand: "translate", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runFixPreview, (_event, request: FixPreviewRequest) =>
    invoke({ subcommand: "fix-preview", request }),
  );
  ipcMain.handle(ENGINE_CHANNELS.runFixApply, (_event, request: FixApplyRequest) =>
    invoke({ subcommand: "fix-apply", request }),
  );

  ipcMain.handle(ENGINE_CHANNELS.cancelRun, () => {
    stopRunningEngine();
  });

  ipcMain.handle(ENGINE_CHANNELS.selectInputFolder, () =>
    selectInputFolder(() => dialog.showOpenDialog({ properties: ["openDirectory"] })),
  );

  ipcMain.handle(ENGINE_CHANNELS.checkDirectoryExists, (_event, path: string) =>
    checkDirectoryExists(directoryStat, path),
  );

  ipcMain.handle(ENGINE_CHANNELS.getOutputPaths, () => outputPaths());

  ipcMain.handle(ENGINE_CHANNELS.readSarif, async (_event, path: string) =>
    parseSarif(await readFile(path, "utf-8")),
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

  ipcMain.handle(ENGINE_CHANNELS.saveSource, (_event, request: SaveSourceRequest) =>
    saveSource(
      {
        fs: saveFileSystem,
        tempFile: editedTempPath(),
        dbPath: outputPaths().db,
        run: (saveRequest) => invoke({ subcommand: "save", request: saveRequest }),
      },
      request,
    ),
  );

  ipcMain.handle(ENGINE_CHANNELS.readGraph, (_event, dbPath: string) =>
    withDatabase(dbPath, readGraph),
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

  ipcMain.handle(ENGINE_CHANNELS.readCopyExpansion, async (_event, path: string) =>
    parseCopyExpansion(await readFile(path, "utf-8")),
  );

  ipcMain.handle(ENGINE_CHANNELS.importSource, (_event, request: ImportSourceRequest) =>
    importSource(importFileSystem, request),
  );

  ipcMain.handle(ENGINE_CHANNELS.listRules, async (_event, request: RulesRequest) => {
    const result = await invoke({ subcommand: "rules", request });
    return parseRuleCatalog(result.summary);
  });

  ipcMain.handle(ENGINE_CHANNELS.readUserRules, (_event, path: string) =>
    readUserRules(userDataFileSystem, path),
  );

  ipcMain.handle(
    ENGINE_CHANNELS.writeUserRules,
    async (_event, path: string, file: UserRulesFile) => {
      await writeUserRules(userDataFileSystem, path, file);
    },
  );

  ipcMain.handle(ENGINE_CHANNELS.readRuleConfig, (_event, path: string) =>
    readRuleConfig(userDataFileSystem, path),
  );

  ipcMain.handle(
    ENGINE_CHANNELS.writeRuleConfig,
    async (_event, path: string, file: RuleConfigFile) => {
      await writeRuleConfig(userDataFileSystem, path, file);
    },
  );

  ipcMain.handle(ENGINE_CHANNELS.readSettings, () =>
    readSettings(userDataFileSystem, settingsPath()),
  );

  ipcMain.handle(ENGINE_CHANNELS.writeSettings, async (_event, settings: AppSettings) => {
    await writeSettings(userDataFileSystem, settingsPath(), settings);
  });
}
