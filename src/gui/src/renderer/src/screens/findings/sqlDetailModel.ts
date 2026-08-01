/**
 * SQL指摘の詳細ペインのビューモデル導出(React 非依存の純関数)。詳細ペインが示す「SQL 文の本文」と
 * 「最適化の指摘の一覧」を、sql-lint の SARIF と原本ソースから組む。本文の供給源は main の
 * readSourceText(表示専用の復号)であり、GUI は SQL の構文解析を行わない。SQL 文の総数は SARIF から
 * 厳密に導けないため扱わない。
 */

import type { SarifFinding } from "../../../../shared/engine-api";
import type { Severity } from "../../components/severity";
import { ruleOf } from "../../data/ruleCatalog";

/** 本文として切り出す最大行数。EXEC SQL … END-EXEC が長い場合はここで打ち切る。 */
export const SQL_BODY_MAX_LINES = 30;

/** 詳細ペインへ出す SQL 本文の取得状態。読取失敗・復号非対応を本文が空の状態と区別する。 */
export type SqlBodyState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly lines: readonly string[]; readonly truncated: boolean }
  | { readonly status: "unsupported"; readonly codepage: string }
  | { readonly status: "error"; readonly message: string };

/** 詳細ペインの指摘カード1件。 */
export interface SqlAdviceEntry {
  readonly finding: SarifFinding;
  readonly ruleName: string;
  readonly severity: Severity;
}

/** 切り出した SQL 本文。truncated は上限行までに END-EXEC が現れなかったことを表す。 */
export interface SqlStatementText {
  readonly lines: string[];
  readonly truncated: boolean;
}

/**
 * 指摘の開始行から EXEC SQL 文の本文を切り出す。開始行から END-EXEC を含む行までを返し、
 * 上限行までに END-EXEC が現れない場合は上限で打ち切る。開始行がファイルの範囲外なら空を返す。
 */
export function extractSqlStatement(
  text: string,
  startLine: number,
  maxLines: number = SQL_BODY_MAX_LINES,
): SqlStatementText {
  const lines = text.split(/\r\n|\n|\r/);
  const first = startLine - 1;
  if (first < 0 || first >= lines.length) {
    return { lines: [], truncated: false };
  }
  const limit = Math.min(lines.length, first + maxLines);
  for (let index = first; index < limit; index += 1) {
    if (/END-EXEC/i.test(lines[index])) {
      return { lines: lines.slice(first, index + 1), truncated: false };
    }
  }
  return { lines: lines.slice(first, limit), truncated: limit < lines.length };
}

/**
 * 同一ファイル・同一開始行の指摘を集める。1 つの SQL 文へ複数の指摘(S001/S004/S006 等)が
 * 付くため、詳細ペインは選択した 1 件ではなくその位置の全件を示す。
 */
export function adviceAt(
  findings: readonly SarifFinding[],
  file: string,
  startLine: number,
): SqlAdviceEntry[] {
  return findings
    .filter((finding) => finding.file === file && finding.startLine === startLine)
    .map((finding) => {
      const rule = ruleOf(finding.ruleId);
      return { finding, ruleName: rule.name, severity: rule.severity };
    });
}
