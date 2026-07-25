import { contextBridge, ipcRenderer } from "electron";
import {
  ENGINE_CHANNELS,
  type CallgraphRequest,
  type CobolInsightApi,
  type FixApplyRequest,
  type FixPreviewRequest,
  type FixResultRequest,
  type LintRequest,
  type ReportRequest,
  type ScanRequest,
  type SourceTextRequest,
  type SqlAdviseRequest,
  type TranspileArtifactsRequest,
  type TranspileRequest,
} from "../shared/engine-api";

/**
 * preload。contextIsolation・sandbox 下で、renderer→main の型付き API を contextBridge で公開する。
 * renderer から Node・child_process・fs へ直接触れさせず、engine CLI の起動と成果物読取はすべて
 * ipcRenderer.invoke で main へ委ねる。
 */
const api: CobolInsightApi = {
  runScan: (request: ScanRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runScan, request),
  runCallgraph: (request: CallgraphRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runCallgraph, request),
  runLint: (request: LintRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runLint, request),
  runSqlAdvise: (request: SqlAdviseRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runSqlAdvise, request),
  runReport: (request: ReportRequest) => ipcRenderer.invoke(ENGINE_CHANNELS.runReport, request),
  runTranspile: (request: TranspileRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runTranspile, request),
  runFixPreview: (request: FixPreviewRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runFixPreview, request),
  runFixApply: (request: FixApplyRequest) =>
    ipcRenderer.invoke(ENGINE_CHANNELS.runFixApply, request),
  selectInputFolder: () => ipcRenderer.invoke(ENGINE_CHANNELS.selectInputFolder),
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
  versions: {
    chrome: process.versions.chrome,
    node: process.versions.node,
    electron: process.versions.electron,
  },
};

contextBridge.exposeInMainWorld("cobolInsight", api);
