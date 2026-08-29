import { app, dialog, ipcMain } from "electron";
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
import { decodeSource } from "./engine/decode";
import { saveSource } from "./engine/save";
import { listRules, validateRules, type RulesDeps } from "./engine/rules";
import { readInventory } from "./artifacts/inventory";
import { parseSarif } from "./artifacts/sarif";
import { readGraph } from "./artifacts/graph";
import { parseCopyExpansion } from "./artifacts/copyExpansion";
import { buildFixDiff } from "./artifacts/fixDiff";
import { readGeneratedFiles, readLineMap } from "./artifacts/transpile";
import { resolveSourceFile } from "./fs/pathGuard";
import { importSource } from "./fs/importSource";
import { dirExists, selectFolder } from "./fs/selectFolder";
import { SETTINGS_FILE_NAME, readSettings, writeSettings } from "./fs/settings";
import { RULES_FILE_NAME, readRulesFile, writeRulesFile } from "./fs/rulesFile";
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
    // A separate name from `sarif`: sharing one would make sql-lint overwrite the lint results.
    sqlSarif: join(dir, "cobol-insight-sql.sarif"),
    copyExpansion: join(dir, COPY_EXPANSION_FILE_NAME),
    // Not an engine artefact but a file the GUI writes; it lives here so its path is decided once.
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
 * The engine currently running. Only one runs at a time; cancellation and shutdown both need it.
 * Write-back and decode are not registered here and are therefore not cancellable.
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
 * A launch that is not registered for cancellation. Cancelling means stopping an analysis; killing
 * the child midway through a write-back would leave half a file in the original.
 */
function invokeUninterrupted(invocation: EngineInvocation): Promise<EngineResult> {
  return runEngine(engineDeps(), currentLaunch(), invocation);
}

/**
 * Runs saves one at a time. The engine's save reads the original and writes it back, so overlapping
 * saves would have the later run read a state the earlier one had not yet written, losing one edit.
 */
let savePending: Promise<unknown> = Promise.resolve();

function queueSave<T>(task: () => Promise<T>): Promise<T> {
  // A failed save still lets the queue advance; only its own caller sees that failure.
  const next = savePending.then(task, task);
  savePending = next.then(
    () => undefined,
    () => undefined,
  );
  return next;
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

function settingsPath(): string {
  return join(app.getPath("userData"), SETTINGS_FILE_NAME);
}

/** What the rules calls need: a scratch file for candidate text, and the engine subcommand. */
function rulesDeps(): RulesDeps {
  return {
    fs: rulesFileSystem,
    tempFile: () => tempPath("rules"),
    run: (request) => invoke({ subcommand: "rules", request }),
  };
}

/**
 * Registers the renderer-to-main IPC handlers. Call once, after app.whenReady.
 *
 * The paths the artefact handlers receive are values the renderer carried over from a previous run's
 * EngineResult.outputs. Decoding, the write-back, stat and the import instead receive paths the user
 * chose on screen, and each of those goes through the pathGuard boundary check.
 */
export function registerIpc(): void {
  ipcMain.handle(CHANNELS.engineRun, (_event, invocation: EngineInvocation) => invoke(invocation));

  ipcMain.handle(CHANNELS.engineCancel, () => {
    stopRunningEngine();
  });

  ipcMain.handle(CHANNELS.engineDecode, (_event, request: DecodeSourceRequest) =>
    decodeSource(
      {
        fs: decodeFileSystem,
        tempFile: () => tempPath("decode"),
        run: (decodeRequest) => invokeUninterrupted({ subcommand: "decode", request: decodeRequest }),
      },
      request,
    ),
  );

  ipcMain.handle(CHANNELS.engineSave, (_event, request: SaveSourceRequest) =>
    queueSave(() =>
      saveSource(
        {
          fs: saveFileSystem,
          tempFile: () => tempPath("edited"),
          dbPath: outputPaths().db,
          run: (saveRequest) => invokeUninterrupted({ subcommand: "save", request: saveRequest }),
        },
        request,
      ),
    ),
  );

  ipcMain.handle(CHANNELS.engineRules, (_event, request: RulesRequest) =>
    listRules(rulesDeps(), request),
  );
  ipcMain.handle(CHANNELS.engineValidateRules, (_event, raw: string) =>
    validateRules(rulesDeps(), raw),
  );

  ipcMain.handle(CHANNELS.artifactInventory, (_event, dbPath: string) =>
    withDatabase(dbPath, readInventory),
  );
  ipcMain.handle(CHANNELS.artifactSarif, async (_event, path: string) =>
    parseSarif(await readFile(path, "utf-8")),
  );
  ipcMain.handle(CHANNELS.artifactGraph, (_event, dbPath: string) => withDatabase(dbPath, readGraph));
  ipcMain.handle(CHANNELS.artifactCopyExpansion, async (_event, path: string) =>
    parseCopyExpansion(await readFile(path, "utf-8")),
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
      const lineMap = await withDatabase(request.dbPath, (db) =>
        readLineMap(db, request.cobolRelPath),
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
  ipcMain.handle(CHANNELS.fsSelectFolder, () =>
    selectFolder(() => dialog.showOpenDialog({ properties: ["openDirectory"] })),
  );
  ipcMain.handle(CHANNELS.fsDirExists, (_event, path: string) => dirExists(directoryStat, path));
  ipcMain.handle(CHANNELS.fsStat, async (_event, request: StatRequest): Promise<SourceStamp | null> => {
    try {
      const target = await resolveSourceFile(decodeFileSystem, request.baseDir, request.path);
      const stats = await stat(target);
      return { mtimeMs: stats.mtimeMs, byteSize: stats.size };
    } catch {
      // An absent or out-of-bounds file is simply "no stamp"; the caller needs no finer distinction.
      return null;
    }
  });
  ipcMain.handle(CHANNELS.fsImportSource, (_event, request: ImportSourceRequest) =>
    importSource(importFileSystem, request),
  );
  // A copy of a generated report. The original artefact stays where the engine wrote it.
  ipcMain.handle(
    CHANNELS.fsSaveAs,
    async (_event, request: SaveAsRequest): Promise<string | null> => {
      const chosen = await dialog.showSaveDialog({ defaultPath: request.fileName });
      if (chosen.canceled || chosen.filePath === undefined || chosen.filePath === "") {
        return null;
      }
      await writeFile(chosen.filePath, request.text, "utf-8");
      return chosen.filePath;
    },
  );

  ipcMain.handle(CHANNELS.settingsRead, () => readSettings(userDataFileSystem, settingsPath()));
  ipcMain.handle(CHANNELS.settingsWrite, async (_event, settings: AppSettings) => {
    await writeSettings(userDataFileSystem, settingsPath(), settings);
  });
  ipcMain.handle(CHANNELS.rulesRead, (_event, path: string) =>
    readRulesFile(userDataFileSystem, path),
  );
  ipcMain.handle(CHANNELS.rulesWrite, async (_event, path: string, file: RulesFile) => {
    await writeRulesFile(userDataFileSystem, path, file);
  });
}
