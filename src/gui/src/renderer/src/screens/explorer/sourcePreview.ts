/**
 * デコードプレビューの表示状態。本文の供給源は main の readSourceText(表示専用の復号)であり、
 * GUI は復号も構文解析も行わない。復号非対応(EBCDIC・コードページ不明)と読取失敗を、
 * 「本文が空」と区別して保持する。
 */

import type { SourceTextResult } from "../../../../shared/engine-api";

export type SourcePreview =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly lines: readonly string[]; readonly codepage: string }
  | { readonly status: "unsupported"; readonly codepage: string }
  | { readonly status: "error"; readonly message: string };

/** readSourceText の結果をプレビュー状態へ写す。本文は行へ分け、末尾の空行は落とす。 */
export function toSourcePreview(result: SourceTextResult): SourcePreview {
  if (result.unsupported) {
    return { status: "unsupported", codepage: result.codepage };
  }
  const lines = result.text === "" ? [] : result.text.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return { status: "ready", lines, codepage: result.codepage };
}
