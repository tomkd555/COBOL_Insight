/**
 * レポート出力(report)画面のテスト用 fixture。engine の `report` が標準出力へ書くサマリ JSON
 * (ReportRunner.Result.summaryJson)と、書き出した HTML・テキストの形を模す。
 */

import type { EngineResult } from "../../../../shared/engine-api";

/** `report` のサマリ JSON。 */
export const SAMPLE_REPORT_SUMMARY: Record<string, unknown> = {
  assets: 19,
  scanFindings: 0,
  lintFindings: 24,
  sqlAdvice: 6,
  callGraphNodes: 33,
  callGraphEdges: 41,
  htmlFile: "C:/proj/cobol-insight-report.html",
  textFile: "C:/proj/cobol-insight-report.txt",
  exitCode: 0,
};

/** report 起動の戻り値。 */
export function reportResult(
  summary: Record<string, unknown> = SAMPLE_REPORT_SUMMARY,
  outputs: { html?: string; text?: string } = {
    html: "C:\\proj\\cobol-insight-report.html",
    text: "C:\\proj\\cobol-insight-report.txt",
  },
): EngineResult {
  return {
    subcommand: "report",
    exitCode: 0,
    summary,
    stdout: JSON.stringify(summary),
    stderr: "",
    outputs,
  };
}

/**
 * engine が書いた HTML レポート。スクリプトを含む形にして、iframe の sandbox がそれを実行させない
 * ことを確かめられるようにする。
 */
export const SAMPLE_REPORT_HTML = [
  "<!DOCTYPE html>",
  '<html lang="ja"><head><meta charset="UTF-8"><title>COBOL Insight 解析レポート</title></head>',
  "<body><h1>COBOL Insight 解析レポート</h1>",
  "<h2>1. 呼出関係サマリ</h2><p>ジョブ 3 / プログラム 9</p>",
  "<h2>2. 指摘一覧</h2><p>全 24 件</p>",
  "<h2>3. SQL 助言</h2><p>全 6 件</p>",
  '<script>window.parent.document.title = "乗っ取り";</script>',
  "</body></html>",
].join("\n");

/** engine が書いたテキストレポート。 */
export const SAMPLE_REPORT_TEXT = [
  "==============================================================",
  "  COBOL Insight 解析レポート",
  "==============================================================",
  "",
  "1. 呼出関係サマリ",
  "   ジョブ 3 / ステップ 6 / プログラム 9",
  "",
  "2. 指摘一覧 (全 24 件)",
  "   [高] R017 cobol/SYK001.cbl:85  ファイル状態(FILE STATUS)未検査",
].join("\n");
