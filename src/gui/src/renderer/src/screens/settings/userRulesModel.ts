/**
 * 利用者定義ルールの作成画面の純ロジック。入力した正規表現を試験用の本文へ当て、
 * どの行が指摘になるかを保存前に示す。
 *
 * 走査の範囲(注記行の除外・8〜72桁への限定)は engine の RegexUserRule と同じ規則を写している。
 * 写しであるため、engine 側の規則を変えるときはここも併せて直す。正規表現の構文そのものは
 * engine が Java、ここが JavaScript で解釈するため、後方参照や先読みの一部で結果が異なりうる。
 */

import type { UserRuleDefinition } from "../../../../shared/engine-api";

/** 試験で一致した1行。 */
export interface PatternTestMatch {
  /** 試験本文の行番号(1起点)。 */
  readonly line: number;
  /** 一致の開始桁(1起点)。 */
  readonly column: number;
  /** その行の本文。 */
  readonly text: string;
  /** 一致した文字列。 */
  readonly matched: string;
  /** ${match} を置き換えたあとの指摘メッセージ。 */
  readonly message: string;
}

export interface PatternTestResult {
  readonly matches: readonly PatternTestMatch[];
  /** 走査した行数(注記行として飛ばした行を除く)。 */
  readonly scannedLines: number;
  /** 正規表現を解釈できなかった場合の説明。解釈できたときは null。 */
  readonly error: string | null;
}

/** メッセージの中でこの並びを書くと、一致した文字列へ置き換わる(engine と同じ)。 */
const MATCH_PLACEHOLDER = "${match}";

/** 固定形式で本体が始まる桁(1起点で8桁目)。 */
const PROGRAM_AREA_START = 7;

/** 固定形式で本体が終わる桁(1起点で72桁目)。 */
const PROGRAM_AREA_END = 72;

/**
 * 試験本文へ定義を当てる。走査の対象種別は、定義が選んだ種別のうち先頭のものを用いる
 * (BMS だけは桁の割り当てを持たないため行全体を見る)。
 */
export function testUserRulePattern(
  draft: UserRuleDefinition,
  sampleText: string,
): PatternTestResult {
  if (draft.pattern.trim() === "") {
    return { matches: [], scannedLines: 0, error: null };
  }
  let pattern: RegExp;
  let exclude: RegExp | null;
  try {
    const flags = draft.ignoreCase ? "i" : "";
    pattern = new RegExp(draft.pattern, flags);
    exclude =
      draft.excludePattern.trim() === "" ? null : new RegExp(draft.excludePattern, flags);
  } catch (error) {
    return {
      matches: [],
      scannedLines: 0,
      error: error instanceof Error ? error.message : String(error),
    };
  }
  // 空の本文を split すると空行1件になる。走査していないことを 0 行として示す。
  if (sampleText.trim() === "") {
    return { matches: [], scannedLines: 0, error: null };
  }
  const target = draft.targets[0] ?? "COBOL";
  const matches: PatternTestMatch[] = [];
  let scannedLines = 0;
  const lines = sampleText.split("\n");
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index].replace(/\r/g, "");
    const scannable = scannableTextOf(raw, target, draft.wholeLine);
    if (scannable === null) {
      continue;
    }
    scannedLines++;
    const found = pattern.exec(scannable);
    if (found === null || (exclude !== null && exclude.test(scannable))) {
      continue;
    }
    matches.push({
      line: index + 1,
      column: found.index + 1,
      text: raw,
      matched: found[0],
      message: draft.message.split(MATCH_PLACEHOLDER).join(found[0]),
    });
  }
  return { matches, scannedLines, error: null };
}

/**
 * 1行のうち走査する範囲を返す。注記行には null を返す。COBOL 本体とコピー句では一連番号欄と
 * 標識欄を空白で置き換え、識別領域を切り落とす。空白で置き換えるのは、示す桁位置を原本の桁と
 * 一致させるためである。
 */
function scannableTextOf(raw: string, target: string, wholeLine: boolean): string | null {
  if (wholeLine || target === "BMS") {
    return raw;
  }
  if (raw.length > 6) {
    const indicator = raw.charAt(6);
    if (indicator === "*" || indicator === "/") {
      return null;
    }
  }
  const line = raw.length > PROGRAM_AREA_END ? raw.slice(0, PROGRAM_AREA_END) : raw;
  if (line.length <= PROGRAM_AREA_START) {
    return line;
  }
  return " ".repeat(PROGRAM_AREA_START) + line.slice(PROGRAM_AREA_START);
}

/** 試験結果の要約。件数と、走査した行数を示す。 */
export function testResultLabel(result: PatternTestResult): string {
  if (result.error !== null) {
    return `正規表現を解釈できない: ${result.error}`;
  }
  if (result.scannedLines === 0) {
    return "試験する本文を入力すると、どの行が指摘になるかを示す。";
  }
  return `${result.scannedLines} 行を走査し、${result.matches.length} 件が一致した。`;
}

/** 対象種別の表示名。engine の語彙(COBOL/COPYBOOK/BMS)を画面の呼び名へ写す。 */
export const TARGET_LABELS: Readonly<Record<string, string>> = {
  COBOL: "COBOL",
  COPYBOOK: "コピー句",
  BMS: "BMS",
};

/** 重大度の表示名。engine の語彙(HIGH/MEDIUM/LOW/ADVISORY)を画面の呼び名へ写す。 */
export const SEVERITY_LABELS_BY_ENGINE_NAME: Readonly<Record<string, string>> = {
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
  ADVISORY: "推奨",
};

/** 一覧に出す対象種別の表示名。 */
export function targetsLabel(targets: readonly string[]): string {
  return targets.map((target) => TARGET_LABELS[target] ?? target).join("・");
}
