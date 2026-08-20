/**
 * レポート出力(report)画面の純ロジック。engine の `report` サブコマンドが書いた HTML とテキストを
 * そのまま表示するための、出力先パスの導出・サマリ JSON の読取・表示状態の導出を担う。
 *
 * レポートの本文は GUI で組み立てない。`report` は1回の実行で HTML(--html)とテキスト
 * (--text)の両方を書き、GUI は readReportHtml / readReportText で読んだものを表示するだけである。
 * 章の取捨は engine のオプションに無いため、GUI は章を選ばせない。レポートには呼出関係サマリ・
 * 指摘一覧・SQL指摘が常に含まれる。
 */

import type { AnalysisMode } from "../../state/projectStore";

/** レポートの出力形式。 */
export type ReportFormat = "HTML" | "テキスト";

/** --db 未指定時に engine が使う既定のプロジェクトファイル名。 */
const DEFAULT_DB_FILE = "cobol-insight.db";

/** report の --html / --text の既定ファイル名(ReportCommand の defaultValue と同じ)。 */
const HTML_FILE_NAME = "cobol-insight-report.html";
const TEXT_FILE_NAME = "cobol-insight-report.txt";

/**
 * HTML レポートを表示する iframe の sandbox 値。空文字はすべての権限を落とし、スクリプト実行・
 * フォーム送信・上位フレームへの遷移のいずれも許さない。allow-same-origin を加えないため、
 * レポート HTML は一意なオリジンに置かれ、renderer の DOM・ストレージへ触れる経路を持たない。
 */
export const REPORT_SANDBOX = "";

/** レポートの書出先。engine の既定ファイル名にそろえる。 */
export interface ReportPaths {
  /** scan が書いた SQLite プロジェクトファイル(--db)。 */
  readonly db: string;
  /** 出力先フォルダ(末尾に区切り文字を含む)。 */
  readonly dir: string;
  readonly html: string;
  readonly text: string;
}

/** `report` のサマリ JSON(ReportRunner.Result.summaryJson)。 */
export interface ReportSummary {
  readonly assets: number;
  /** scan 由来の finding(復号・構文解析の失敗)。 */
  readonly scanFindings: number;
  /** lint 由来の指摘(R001〜R031)。 */
  readonly lintFindings: number;
  /** SQL 最適化の指摘(S001〜S006)。 */
  readonly sqlAdvice: number;
  readonly callGraphNodes: number;
  readonly callGraphEdges: number;
  /** 0=成功 / 1=警告あり / 2=エラーあり。 */
  readonly exitCode: number;
}

/** レポートの生成と読取の状態。 */
export type ReportState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly summary: ReportSummary;
      readonly html: string;
      readonly text: string;
      readonly paths: ReportPaths;
    }
  | { readonly status: "error"; readonly message: string };

/** レポート画面の表示状態。4状態に、資産フォルダ未選択と未生成を加えて導く。 */
export type ReportView =
  | { readonly kind: "empty" }
  | { readonly kind: "no-project" }
  | { readonly kind: "running" }
  | { readonly kind: "error"; readonly message: string }
  /** 解析済みだが、まだレポートを生成していない(書き出しの操作を待つ)。 */
  | { readonly kind: "not-generated" }
  | { readonly kind: "results" };

/** プレビューに出す指標1件。 */
export interface ReportMetric {
  readonly label: string;
  readonly value: string;
}

/** パスの末尾へ区切り文字を1つだけ付ける。 */
export function withTrailingSeparator(dir: string): string {
  if (dir === "") {
    return "";
  }
  if (dir.endsWith("\\") || dir.endsWith("/")) {
    return dir;
  }
  return `${dir}${dir.includes("\\") ? "\\" : "/"}`;
}

/**
 * レポートの書出先を導く。出力先フォルダを指定しないときは、プロジェクトファイルと同じ場所へ書く
 * (scan が書けた場所であり、確実に存在するため)。
 */
export function reportArtifactPaths(dbPath: string | null, outDir?: string): ReportPaths {
  const db = dbPath ?? DEFAULT_DB_FILE;
  const separator = Math.max(db.lastIndexOf("\\"), db.lastIndexOf("/"));
  const dbDir = separator < 0 ? "" : db.slice(0, separator + 1);
  const dir = withTrailingSeparator(outDir === undefined || outDir === "" ? dbDir : outDir);
  return { db, dir, html: `${dir}${HTML_FILE_NAME}`, text: `${dir}${TEXT_FILE_NAME}` };
}

/** サマリ JSON から件数を取り出す。欠けている項目は 0 として扱う。 */
export function readReportSummary(summary: Record<string, unknown> | null): ReportSummary {
  return {
    assets: numberOf(prop(summary, "assets")),
    scanFindings: numberOf(prop(summary, "scanFindings")),
    lintFindings: numberOf(prop(summary, "lintFindings")),
    sqlAdvice: numberOf(prop(summary, "sqlAdvice")),
    callGraphNodes: numberOf(prop(summary, "callGraphNodes")),
    callGraphEdges: numberOf(prop(summary, "callGraphEdges")),
    exitCode: numberOf(prop(summary, "exitCode")),
  };
}

/** プレビュー上部に出す指標。engine のサマリが返した実件数だけを出す。 */
export function reportMetrics(summary: ReportSummary): ReportMetric[] {
  return [
    { label: "資産", value: String(summary.assets) },
    { label: "指摘", value: String(summary.lintFindings) },
    { label: "SQL指摘", value: String(summary.sqlAdvice) },
    {
      label: "呼出関係",
      value: `ノード ${summary.callGraphNodes} ・ エッジ ${summary.callGraphEdges}`,
    },
    { label: "解析エラー", value: String(summary.scanFindings) },
  ];
}

/** 選んだ形式で表示するファイルのパス。 */
export function previewPath(format: ReportFormat, paths: ReportPaths): string {
  return format === "HTML" ? paths.html : paths.text;
}

/** レポートの終了コードから出す警告文。警告・エラーが無ければ null。 */
export function exitCodeWarning(exitCode: number): string | null {
  if (exitCode === 0) {
    return null;
  }
  if (exitCode === 1) {
    return "警告のある指摘を含みます。レポート本体は書き出しています。";
  }
  return "エラーのある指摘、または解析の失敗を含みます。レポート本体は書き出していますが、対象資産の一部を解析できていない可能性があります。";
}

/** 書き出し完了の通知文。engine が書いた2つのファイルのパスを示す。 */
export function writeNotice(paths: ReportPaths): string {
  return `レポートを書き出しました: ${paths.html} ／ ${paths.text}`;
}

/** レポート画面の4状態を、解析ライフサイクルとレポートの取得状態から導く。 */
export function deriveReportView(
  mode: AnalysisMode,
  inputDir: string | null,
  report: ReportState,
): ReportView {
  if (mode === "empty") {
    return { kind: "empty" };
  }
  if (mode === "running") {
    return { kind: "running" };
  }
  if (inputDir === null) {
    return { kind: "no-project" };
  }
  if (report.status === "loading") {
    return { kind: "running" };
  }
  if (report.status === "error") {
    return { kind: "error", message: report.message };
  }
  if (report.status === "ready") {
    return { kind: "results" };
  }
  return { kind: "not-generated" };
}

function prop(value: unknown, key: string): unknown {
  return value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

function numberOf(value: unknown): number {
  return typeof value === "number" ? value : 0;
}
