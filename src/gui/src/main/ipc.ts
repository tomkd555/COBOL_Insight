import {
  BrowserWindow,
  app,
  dialog,
  ipcMain,
  type OpenDialogOptions,
  type SaveDialogOptions,
} from "electron";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFile, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import {
  CHANNELS,
  COPY_EXPANSION_FILE_NAME,
  type DecodeSourceRequest,
  type EngineInvocation,
  type EngineOutputPaths,
  type EngineResult,
  type FixDiffRequest,
  type ImportSourceRequest,
  type ReportArtifactRequest,
  type RulesRequest,
  type SaveAsRequest,
  type SaveSourceRequest,
  type SourceStamp,
  type StatRequest,
  type TranspileArtifacts,
  type TranspileRequest,
} from "../shared/ipc";
import type { AppSettings } from "../shared/settings";
import type { RulesFile } from "../shared/rulesFile";
import { runEngine, type EngineProcess, type EngineSpawn, type RunEngineDeps } from "./engine/run";
import { resolveEngineLaunch, type EngineLaunch } from "./engine/launch";
import { decodeSource, stampOf } from "./engine/decode";
import { createCachedDecode } from "./engine/decodeCache";
import { saveSource } from "./engine/save";
import { createEngineTurns } from "./engine/turns";
import { listRules, validateRules, type RulesDeps } from "./engine/rules";
import { readInventory } from "./artifacts/inventory";
import { parseSarif } from "./artifacts/sarif";
import { readGraph } from "./artifacts/graph";
import { readProgramFlow } from "./artifacts/programFlow";
import { parseCopyExpansion } from "./artifacts/copyExpansion";
import { buildFixDiff } from "./artifacts/fixDiff";
import { readGeneratedFiles, readLineMap } from "./artifacts/transpile";
import { resolveSourceFile } from "./fs/pathGuard";
import { importSource } from "./fs/importSource";
import { dirExists, selectFolder } from "./fs/selectFolder";
import { SETTINGS_FILE_NAME, readSettings, writeSettings } from "./fs/settings";
import {
  RULES_FILE_NAME,
  ownRulesPath,
  readRulesFile,
  withOwnRulesFile,
  writeRulesFile,
} from "./fs/rulesFile";
import {
  decodeFileSystem,
  directoryStat,
  generatedFileSystem,
  importFileSystem,
  rulesFileSystem,
  saveFileSystem,
  userDataFileSystem,
  withDatabase,
} from "./nodeFs";

/**
 * Adapts child_process.spawn to EngineSpawn (stdio defaults to pipe). No shell is involved, so
 * spaces and punctuation in paths and option values are never reinterpreted.
 */
const engineSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, { env: options.env, cwd: options.cwd }) as unknown as EngineProcess;

/**
 * Where the engine writes its artefacts. userData already points at the portable `data/` directory
 * (or Electron's default when that is not writable), so this is one writable directory in both a
 * distribution and a development run.
 */
function outputPaths(): EngineOutputPaths {
  const dir = app.getPath("userData");
  return {
    db: join(dir, "cobol-insight.db"),
    sarif: join(dir, "cobol-insight.sarif"),
    // A separate name from `sarif`: lint writes both files in one run and must not have one overwrite
    // the other.
    sqlSarif: join(dir, "cobol-insight-sql.sarif"),
    // A scoped lint writes here, so the pair above keeps holding the last whole-folder run — which
    // is what the report is generated from.
    scopedSarif: join(dir, "cobol-insight-scope.sarif"),
    scopedSqlSarif: join(dir, "cobol-insight-scope-sql.sarif"),
    copyExpansion: join(dir, COPY_EXPANSION_FILE_NAME),
    // A file the GUI writes; it lives here so its path is decided once.
    rules: join(dir, RULES_FILE_NAME),
  };
}

/** A scratch file under userData. A fresh name per call keeps concurrent operations apart. */
function tempPath(prefix: string): string {
  return join(app.getPath("userData"), `cobol-insight-${prefix}-${randomUUID()}.tmp`);
}

/** Resolves the engine launch target from the current runtime. */
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
 * The engine launch a cancel stops: the analysis stage, or a view's own run. Cancellation and
 * shutdown both need it. Write-back, decode, and the listing and validation of rules launch through
 * invokeUninterrupted and stay outside this slot.
 */
let runningEngine: EngineProcess | null = null;

/**
 * Dependencies shared by every launch. Output paths are given as absolute arguments, but the working
 * directory also points at the writable storage in case any default path survives inside the engine.
 */
function engineDeps(): RunEngineDeps {
  return { spawn: engineSpawn, env: process.env, cwd: app.getPath("userData") };
}

function invoke(invocation: EngineInvocation): Promise<EngineResult> {
  let started: EngineProcess | null = null;
  const deps: RunEngineDeps = {
    ...engineDeps(),
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
 * A launch that stays outside the cancel registration. Cancelling means stopping an analysis; killing
 * the child midway through a write-back would leave half a file in the original. Decode and the rules
 * launches use it too, so a cancel always reaches the analysis stage it was meant for.
 */
function invokeUninterrupted(invocation: EngineInvocation): Promise<EngineResult> {
  return runEngine(engineDeps(), currentLaunch(), invocation);
}

/**
 * Orders the scans, the saves and the reads of the project file, which touch the asset folder and the
 * project file (engine/turns.ts): a save waits for every scan asked for before it, a scan waits for
 * the saves, the scans and the reads asked for before it, and a read waits for the scans asked for
 * before it. Saves run one at a time, because the engine's save reads the original and writes it back
 * and two overlapping saves would lose one edit. A translation writes the project file too, so it
 * takes a scan's turn; an import writes into the asset folder, so it takes a save's; the report and
 * the COPY expansion table are read in a read's turn. A cancel releases the scans still waiting; one
 * already started keeps everything asked for after it waiting until it ends.
 */
const turns = createEngineTurns();

/**
 * What a scan that a cancel released from its wait resolves to: exit code -1 and no summary, as
 * runEngine reports for a killed process, with empty output and empty `outputs`, since nothing was
 * launched.
 */
function killedResult(invocation: EngineInvocation): EngineResult {
  return {
    subcommand: invocation.subcommand,
    exitCode: -1,
    summary: null,
    stdout: "",
    stderr: "",
    outputs: {},
  };
}

/**
 * Stops the running engine. Called both by the cancel action and on shutdown: left alone, the
 * analysis would keep running after the screen has left its waiting state and would collide with
 * the next launch.
 */
export function stopRunningEngine(): void {
  runningEngine?.kill();
  runningEngine = null;
}

/**
 * The real path and stamp of a file the renderer named, or null when it is absent or outside the
 * allowed base directory. Both the stat channel and the decode cache identify a file through this.
 */
async function locateSourceFile(
  request: StatRequest,
): Promise<{ realPath: string; stamp: SourceStamp } | null> {
  try {
    const realPath = await resolveSourceFile(decodeFileSystem, request.baseDir, request.path);
    return { realPath, stamp: stampOf(await stat(realPath)) };
  } catch {
    // An absent or out-of-bounds file is simply "no stamp"; no caller needs a finer distinction.
    return null;
  }
}

/**
 * The cached decode. One cache serves the whole session: reopening an unchanged file costs a map
 * lookup. A changed file, another codepage or another project file is a new key, and a scan empties
 * the cache.
 */
const cachedDecode = createCachedDecode({
  locate: (request) => locateSourceFile(request),
  decode: (request) =>
    decodeSource(
      {
        fs: decodeFileSystem,
        tempFile: () => tempPath("decode"),
        run: (decodeRequest) => invokeUninterrupted({ subcommand: "decode", request: decodeRequest }),
      },
      request,
    ),
});

export function settingsPath(): string {
  return join(app.getPath("userData"), SETTINGS_FILE_NAME);
}

/**
 * The rule file the handlers read and write. Throws unless the renderer named the file main lists
 * in the output paths, so the rule channels reach that one file.
 */
function ownRules(path: string): string {
  return ownRulesPath(path, outputPaths().rules);
}

/** An engine request held to main's own rule file (fs/rulesFile.ts). */
function withOwnRules<T>(request: T): T {
  return withOwnRulesFile(request, outputPaths().rules);
}

/** What the rules calls need: a scratch file for candidate text, and the engine subcommand. */
function rulesDeps(): RulesDeps {
  return {
    fs: rulesFileSystem,
    tempFile: () => tempPath("rules"),
    // Outside the cancel registration: a rules listing or validation run during an analysis would
    // take the analysis's place in `runningEngine`, and a cancel would then stop it and leave the
    // analysis running.
    run: (request) => invokeUninterrupted({ subcommand: "rules", request }),
  };
}

/**
 * Registers the renderer-to-main IPC handlers. Call once, after app.whenReady.
 *
 * The paths the artefact handlers receive are values the renderer carried over from a previous run's
 * EngineResult.outputs. Decoding, the write-back, stat and the import instead receive paths the user
 * chose on screen, and each of those goes through the pathGuard boundary check. The rule-file read
 * and write, and every request carrying `rulesFile`, are held to main's own rule file through
 * ownRulesPath.
 */
export function registerIpc(): void {
  ipcMain.handle(CHANNELS.engineRun, async (_event, requested: EngineInvocation) => {
    // The write-back and the decode have channels of their own, which hold their paths to the asset
    // folder; through this one they would run on whatever paths the request names.
    if (requested.subcommand === "save" || requested.subcommand === "decode") {
      throw new Error(`「${requested.subcommand}」はこの経路では実行できません。`);
    }
    const invocation = {
      ...requested,
      request: withOwnRules(requested.request),
    } as EngineInvocation;
    // The report reads the project file, so it takes a read's turn.
    if (invocation.subcommand === "report") {
      return turns.read(() => invoke(invocation));
    }
    if (invocation.subcommand !== "scan" && invocation.subcommand !== "translate") {
      return invoke(invocation);
    }
    const result = await turns.scan(
      () => invoke(invocation),
      () => killedResult(invocation),
    );
    // A scan records each file's codepage afresh. The cache key cannot tell that apart — the bytes
    // and the stamp are unchanged and the renderer sends no codepage — so the entries are dropped.
    if (invocation.subcommand === "scan") {
      cachedDecode.clear();
    }
    return result;
  });

  ipcMain.handle(CHANNELS.engineCancel, () => {
    turns.cancel();
    stopRunningEngine();
  });

  ipcMain.handle(CHANNELS.engineDecode, (_event, request: DecodeSourceRequest) =>
    cachedDecode(request),
  );

  ipcMain.handle(CHANNELS.engineSave, (_event, request: SaveSourceRequest) =>
    turns.save(() =>
      saveSource(
        {
          fs: saveFileSystem,
          tempFile: () => tempPath("edited"),
          dbPath: outputPaths().db,
          stat: async (statRequest) => (await locateSourceFile(statRequest))?.stamp ?? null,
          run: (saveRequest) => invokeUninterrupted({ subcommand: "save", request: saveRequest }),
        },
        request,
      ),
    ),
  );

  ipcMain.handle(CHANNELS.engineRules, (_event, request: RulesRequest) =>
    listRules(rulesDeps(), withOwnRules(request)),
  );
  ipcMain.handle(CHANNELS.engineValidateRules, (_event, raw: string) =>
    validateRules(rulesDeps(), raw),
  );

  ipcMain.handle(CHANNELS.artifactInventory, (_event, dbPath: string, root: string) =>
    turns.read(() => withDatabase(dbPath, (db) => readInventory(db, root))),
  );
  ipcMain.handle(CHANNELS.artifactSarif, async (_event, path: string) =>
    parseSarif(await readFile(path, "utf-8")),
  );
  ipcMain.handle(CHANNELS.artifactGraph, (_event, dbPath: string, root: string) =>
    turns.read(() => withDatabase(dbPath, (db) => readGraph(db, root))),
  );
  ipcMain.handle(
    CHANNELS.artifactProgramFlow,
    (_event, dbPath: string, root: string, relPath: string) =>
      turns.read(() => withDatabase(dbPath, (db) => readProgramFlow(db, root, relPath))),
  );
  // A scan rewrites the COPY expansion table, so its read takes a read's turn.
  ipcMain.handle(CHANNELS.artifactCopyExpansion, (_event, path: string) =>
    turns.read(async () => parseCopyExpansion(await readFile(path, "utf-8"))),
  );
  ipcMain.handle(CHANNELS.artifactFixDiff, async (_event, request: FixDiffRequest) => {
    const [original, fixed] = await Promise.all([
      readFile(request.originalPath, "utf-8"),
      readFile(request.fixedPath, "utf-8"),
    ]);
    return buildFixDiff(request.relPath, original, fixed);
  });
  ipcMain.handle(
    CHANNELS.artifactTranspile,
    async (_event, request: TranspileRequest): Promise<TranspileArtifacts> => {
      const lineMap = await turns.read(() =>
        withDatabase(request.dbPath, (db) => readLineMap(db, request.cobolRelPath)),
      );
      const files = await readGeneratedFiles(generatedFileSystem, request.outDir, lineMap);
      return { files, lineMap };
    },
  );
  // Both report forms are UTF-8 text; the kind only tells the renderer how to present it.
  ipcMain.handle(CHANNELS.artifactReport, (_event, request: ReportArtifactRequest) =>
    readFile(request.path, "utf-8"),
  );

  ipcMain.handle(CHANNELS.fsOutputPaths, () => outputPaths());
  // The dialogs are parented to the window that asked, so they stay in front of it and block it.
  ipcMain.handle(CHANNELS.fsSelectFolder, (event) =>
    selectFolder(() => {
      const owner = BrowserWindow.fromWebContents(event.sender);
      const options: OpenDialogOptions = { properties: ["openDirectory"] };
      return owner === null ? dialog.showOpenDialog(options) : dialog.showOpenDialog(owner, options);
    }),
  );
  ipcMain.handle(CHANNELS.fsDirExists, (_event, path: string) => dirExists(directoryStat, path));
  ipcMain.handle(
    CHANNELS.fsStat,
    async (_event, request: StatRequest): Promise<SourceStamp | null> =>
      (await locateSourceFile(request))?.stamp ?? null,
  );
  // An import writes into the asset folder, so it takes a save's turn and never lands while a scan
  // lists the folder.
  ipcMain.handle(CHANNELS.fsImportSource, (_event, request: ImportSourceRequest) =>
    turns.save(() => importSource(importFileSystem, request)),
  );
  // A copy of a generated report. The original artefact stays where the engine wrote it.
  ipcMain.handle(
    CHANNELS.fsSaveAs,
    async (event, request: SaveAsRequest): Promise<string | null> => {
      const owner = BrowserWindow.fromWebContents(event.sender);
      const options: SaveDialogOptions = { defaultPath: request.fileName };
      const chosen = await (owner === null
        ? dialog.showSaveDialog(options)
        : dialog.showSaveDialog(owner, options));
      if (chosen.canceled || chosen.filePath === undefined || chosen.filePath === "") {
        return null;
      }
      await writeFile(chosen.filePath, request.text, "utf-8");
      return chosen.filePath;
    },
  );

  ipcMain.handle(CHANNELS.settingsRead, () => readSettings(userDataFileSystem, settingsPath()));
  ipcMain.handle(CHANNELS.settingsWrite, async (_event, settings: Partial<AppSettings>) => {
    await writeSettings(userDataFileSystem, settingsPath(), settings);
  });
  ipcMain.handle(CHANNELS.rulesRead, (_event, path: string) =>
    readRulesFile(userDataFileSystem, ownRules(path)),
  );
  ipcMain.handle(CHANNELS.rulesWrite, async (_event, path: string, file: RulesFile) => {
    await writeRulesFile(userDataFileSystem, ownRules(path), file);
  });
}
