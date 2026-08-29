import { contextBridge, ipcRenderer } from "electron";
import {
  CHANNELS,
  type CobolInsightApi,
  type DecodeSourceRequest,
  type EngineInvocation,
  type FixDiffRequest,
  type ImportSourceRequest,
  type ReportArtifactRequest,
  type RulesRequest,
  type SaveSourceRequest,
  type StatRequest,
  type TranspileRequest,
} from "../shared/ipc";
import type { AppSettings } from "../shared/settings";
import type { RulesFile } from "../shared/rulesFile";

/**
 * The preload. Under contextIsolation and sandbox it publishes the typed renderer-to-main API
 * through contextBridge. The renderer never touches Node, child_process or fs: every engine launch
 * and artefact read goes to main through ipcRenderer.invoke.
 *
 * Only the functions listed here are exposed; ipcRenderer itself is not. Channel names are supplied
 * at this layer from the CHANNELS constants, so the renderer can never invoke an arbitrary channel.
 * Arguments and return values must be structured-cloneable (no functions, no class instances).
 */
const api: CobolInsightApi = {
  run: (invocation: EngineInvocation) => ipcRenderer.invoke(CHANNELS.engineRun, invocation),
  cancel: () => ipcRenderer.invoke(CHANNELS.engineCancel),
  decode: (request: DecodeSourceRequest) => ipcRenderer.invoke(CHANNELS.engineDecode, request),
  save: (request: SaveSourceRequest) => ipcRenderer.invoke(CHANNELS.engineSave, request),
  rules: (request: RulesRequest) => ipcRenderer.invoke(CHANNELS.engineRules, request),
  validateRules: (raw: string) => ipcRenderer.invoke(CHANNELS.engineValidateRules, raw),

  readInventory: (dbPath: string) => ipcRenderer.invoke(CHANNELS.artifactInventory, dbPath),
  readSarif: (path: string) => ipcRenderer.invoke(CHANNELS.artifactSarif, path),
  readGraph: (dbPath: string) => ipcRenderer.invoke(CHANNELS.artifactGraph, dbPath),
  readCopyExpansion: (path: string) => ipcRenderer.invoke(CHANNELS.artifactCopyExpansion, path),
  readFixDiff: (request: FixDiffRequest) => ipcRenderer.invoke(CHANNELS.artifactFixDiff, request),
  readTranspile: (request: TranspileRequest) => ipcRenderer.invoke(CHANNELS.artifactTranspile, request),
  readReport: (request: ReportArtifactRequest) => ipcRenderer.invoke(CHANNELS.artifactReport, request),

  outputPaths: () => ipcRenderer.invoke(CHANNELS.fsOutputPaths),
  selectFolder: () => ipcRenderer.invoke(CHANNELS.fsSelectFolder),
  dirExists: (path: string) => ipcRenderer.invoke(CHANNELS.fsDirExists, path),
  stat: (request: StatRequest) => ipcRenderer.invoke(CHANNELS.fsStat, request),
  importSource: (request: ImportSourceRequest) => ipcRenderer.invoke(CHANNELS.fsImportSource, request),

  readSettings: () => ipcRenderer.invoke(CHANNELS.settingsRead),
  writeSettings: (settings: AppSettings) => ipcRenderer.invoke(CHANNELS.settingsWrite, settings),
  readRules: (path: string) => ipcRenderer.invoke(CHANNELS.rulesRead, path),
  writeRules: (path: string, file: RulesFile) => ipcRenderer.invoke(CHANNELS.rulesWrite, path, file),

  // The renderer has no `process`, so the versions are copied out here at preload time.
  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);
