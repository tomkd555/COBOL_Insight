import type { FixDiff } from "../../shared/engine-api";

export { parseFixSummary } from "../../shared/fixSummary";

/**
 * 原本テキストと修正後テキストを Monaco DiffEditor が取る対へ組む純関数。IPC 側で原本(資産
 * フォルダ)と修正後(apply 出力先)の各ファイルを読み、この関数へ渡す。
 */
export function buildFixDiff(relPath: string, originalText: string, fixedText: string): FixDiff {
  return { relPath, originalText, fixedText };
}
