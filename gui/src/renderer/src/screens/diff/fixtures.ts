/**
 * diff(fix)画面のテスト用 fixture。engine の `fix preview` / `fix apply` が標準出力へ書くサマリ JSON
 * (FixPreviewCommand・FixApplyCommand の summaryJson)と、unified diff 本体の形を模す。
 */

import type { EngineResult } from "../../../../shared/engine-api";

/** `fix preview` のサマリ JSON。修正案は COBOL 本体2件とコピー句1件。 */
export const SAMPLE_PREVIEW_SUMMARY: Record<string, unknown> = {
  fixedFiles: ["cobol/SYK007.cbl", "cobol/SYK006.cbl", "copybook/ORDREC.cpy"],
  copybookFixes: [
    { copybook: "copybook/ORDREC.cpy", importers: ["SYK001", "SYK002"] },
  ],
  fixCount: 4,
  analysisErrors: 0,
};

/** `fix apply` のサマリ JSON。コピー句は書き出さず、再パース検証の失敗は 0 件。 */
export const SAMPLE_APPLY_SUMMARY: Record<string, unknown> = {
  outputDir: "C:/proj/fix",
  writtenFiles: ["cobol/SYK007.cbl", "cobol/SYK006.cbl"],
  copybookFixes: [
    { copybook: "copybook/ORDREC.cpy", importers: ["SYK001", "SYK002"] },
  ],
  fixCount: 4,
  analysisErrors: 0,
  reparseFailures: 0,
  exitCode: 0,
};

/** `fix preview` の標準出力。unified diff 2ファイル分とコピー句の注記、末尾にサマリ JSON。 */
export const SAMPLE_PREVIEW_STDOUT = [
  "--- a/cobol/SYK007.cbl",
  "+++ b/cobol/SYK007.cbl",
  "@@ -77,4 +77,5 @@",
  "            COMPUTE WS-合計金額 = WS-単価 * WS-数量",
  "-               END-COMPUTE",
  "+               ON SIZE ERROR",
  "+                   MOVE 'E' TO WS-状態",
  "+               END-COMPUTE",
  "--- a/copybook/ORDREC.cpy",
  "+++ b/copybook/ORDREC.cpy",
  "@@ -10,2 +10,3 @@",
  "        05 ORD-金額  PIC S9(9)V99 COMP-3.",
  "+       05 ORD-状態  PIC X(1).",
  "# コピー句 copybook/ORDREC.cpy は原本を書き換えず提示のみ。取り込みプログラム: SYK001, SYK002",
  '{"fixedFiles":["cobol/SYK007.cbl"],"fixCount":4,"analysisErrors":0}',
  "",
].join("\n");

/** preview 起動の戻り値。 */
export function previewResult(
  summary: Record<string, unknown> = SAMPLE_PREVIEW_SUMMARY,
  stdout: string = SAMPLE_PREVIEW_STDOUT,
): EngineResult {
  return {
    subcommand: "fix-preview",
    exitCode: 0,
    summary,
    stdout,
    stderr: "",
    outputs: {},
  };
}

/** apply 起動の戻り値。 */
export function applyResult(
  summary: Record<string, unknown> = SAMPLE_APPLY_SUMMARY,
  outDir = "C:\\proj\\fix",
): EngineResult {
  return {
    subcommand: "fix-apply",
    exitCode: 0,
    summary,
    stdout: "",
    stderr: "",
    outputs: { outDir },
  };
}

/** 原本テキスト(固定形式 80 桁の一部)。 */
export const SAMPLE_ORIGINAL_TEXT = [
  "000770            COMPUTE WS-合計金額 = WS-単価 * WS-数量",
  "000780            END-COMPUTE.",
].join("\n");

/** 修正後テキスト(ON SIZE ERROR を付与したもの)。 */
export const SAMPLE_FIXED_TEXT = [
  "000770            COMPUTE WS-合計金額 = WS-単価 * WS-数量",
  "000775                ON SIZE ERROR",
  "000776                    MOVE 'E' TO WS-状態",
  "000780            END-COMPUTE.",
].join("\n");
