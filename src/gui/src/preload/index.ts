import { contextBridge, ipcRenderer } from "electron";
import type { AppSettings } from "../shared/appSettings";
import {
  ENGINE_CHANNELS,
  type CallgraphRequest,
  type CobolInsightApi,
  type FixApplyRequest,
  type FixPreviewRequest,
  type FixResultRequest,
  type ImportSourceRequest,
  type LintRequest,
  type ReportRequest,
  type RulesRequest,
  type ScanRequest,
  type SourceTextRequest,
  type SqlAdviseRequest,
  type TranspileArtifactsRequest,
  type TranspileRequest,
  type UserRulesFile,
} from "../shared/engine-api";

/**
 * preload。contextIsolation・sandbox 下で、renderer→main の型付き API を contextBridge で公開する。
 * renderer から Node・child_process・fs へ直接触れさせず、engine CLI の起動と成果物読取はすべて
 * ipcRenderer.invoke で main へ委ねる。
 *
 * 公開するのはここに並べた関数だけであり、ipcRenderer 自身は渡さない。チャネル名は
 * ENGINE_CHANNELS の固定値をこの層で与えるため、renderer が任意のチャネルへ invoke することはない。
 * IPC を渡る引数と戻り値は構造化複製できる値に限る(関数・クラスのインスタンスは渡せない)。
 */
const api: CobolInsightApi = {
  runScan: (request: ScanRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runScan, request),
  runCallgraph: (request: CallgraphRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runCallgraph, request),
  runLint: (request: LintRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runLint, request),
  runSqlLint: (request: SqlAdviseRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runSqlLint, request),
  runReport: (request: ReportRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runReport, request),
  runTranspile: (request: TranspileRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runTranspile, request),
  runFixPreview: (request: FixPreviewRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runFixPreview, request),
  runFixApply: (request: FixApplyRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runFixApply, request),
  cancelRun: () => ipcRenderer.invoke(ENGINE_CHANNELS.cancelRun),
  selectInputFolder: () => ipcRenderer.invoke(ENGINE_CHANNELS.selectInputFolder),
  checkDirectoryExists: (path: string) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.checkDirectoryExists, path),
  getOutputPaths: () => ipcRenderer.invoke(ENGINE_CHANNELS.getOutputPaths),
  readSarif: (path: string) => ipcRenderer.invoke(ENGINE_CHANNELS.readSarif, path),
  readCallgraphJson: (path: string) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.readCallgraphJson, path),
  readFixResult: (request: FixResultRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.readFixResult, request),
  readReportHtml: (path: string) => ipcRenderer.invoke(ENGINE_CHANNELS.readReportHtml, path),
  readReportText: (path: string) => ipcRenderer.invoke(ENGINE_CHANNELS.readReportText, path),
  readAssetInventory: (dbPath: string) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.readAssetInventory, dbPath),
  readSourceText: (request: SourceTextRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.readSourceText, request),
  readTranspileArtifacts: (request: TranspileArtifactsRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.readTranspileArtifacts, request),
  readCopyExpansion: (path: string) => ipcRenderer.invoke(ENGINE_CHANNELS.readCopyExpansion, path),
  importSource: (request: ImportSourceRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.importSource, request),
  listRules: (request: RulesRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.listRules, request),
  readUserRules: (path: string) => ipcRenderer.invoke(ENGINE_CHANNELS.readUserRules, path),
  writeUserRules: (path: string, file: UserRulesFile) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.writeUserRules, path, file),
  readSettings: () => ipcRenderer.invoke(ENGINE_CHANNELS.readSettings),
  writeSettings: (settings: AppSettings) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.writeSettings, settings),
  // renderer には process が無いため、版数は preload の時点で読んだ値を複製して渡す。
  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);
