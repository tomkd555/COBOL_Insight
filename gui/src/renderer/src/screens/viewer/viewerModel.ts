/**
 * ソースビューアのビューモデル導出(React 非依存の純ロジック)。design gvViewer(design:1349-1408)の
 * ファイル選択・言語切替・相互ハイライトの提示を移植し、供給源を実データへ置き換える。
 *
 * 本文は main の readSourceText(表示専用の復号)、対訳は transpile の生成物と LINE_MAP が供給源で
 * あり、GUI は解析も復号も行わない。固定形式の欄割りのうち識別欄(73〜80桁)は Monarch では
 * 桁位置を条件にできないため、ここで範囲を求めて装飾として示す。
 */

import type {
  AssetInventoryItem,
  LineMapEntry,
  SourceTextResult,
  TranspileGeneratedFile,
  TranspileLanguage,
} from "../../../../shared/engine-api";
import { SCREENS, type ScreenId } from "../../shell/screens";
import type { SourceLang } from "../../state/appState";
import { charIndexAfterBytes, type SourceCodepage } from "./columns";

/** 本体(8〜72桁)の終端桁。識別欄はこの次の桁から始まる。 */
export const BODY_LAST_COLUMN = 72;

/** 桁ルーラの位置。一連番号欄の終端・標識欄の終端・A領域とB領域の境界・本体の終端・行の終端。 */
export const COBOL_RULERS: readonly number[] = [6, 7, 11, BODY_LAST_COLUMN, 80];

/** ソース本文の取得状態。復号非対応と読取失敗を、本文が空であることと区別して保つ。 */
export type DocumentState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly text: string; readonly codepage: string; readonly truncated: boolean }
  | { readonly status: "unsupported"; readonly codepage: string }
  | { readonly status: "error"; readonly message: string };

/** 逐語対訳の取得状態。生成物と行対応表を1つの成果物として扱う。 */
export type TranspileState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly files: readonly TranspileGeneratedFile[];
      readonly lineMap: readonly LineMapEntry[];
    }
  | { readonly status: "error"; readonly message: string };

/** readSourceText の結果を取得状態へ写す。本文は Monaco へ載せるため原文のまま保つ。 */
export function toDocument(result: SourceTextResult): DocumentState {
  if (result.unsupported) {
    return { status: "unsupported", codepage: result.codepage };
  }
  return { status: "ready", text: result.text, codepage: result.codepage, truncated: result.truncated };
}

/** transpile の出力先。解析で使う SQLite と同じフォルダの transpile へそろえる。 */
export function transpileOutDir(dbPath: string): string {
  const separator = Math.max(dbPath.lastIndexOf("\\"), dbPath.lastIndexOf("/"));
  const dir = separator < 0 ? "" : dbPath.slice(0, separator + 1);
  return `${dir}transpile`;
}

/** 画面の言語切替(design lang)を engine の生成言語へ写す。 */
export function generatedLanguageOf(lang: SourceLang): TranspileLanguage {
  return lang === "py" ? "python" : "java";
}

/** 言語切替ボタンの並びと表示名。 */
export interface LanguageTab {
  readonly lang: SourceLang;
  readonly label: string;
}

export const LANGUAGE_TABS: readonly LanguageTab[] = [
  { lang: "py", label: "Python" },
  { lang: "java", label: "Java" },
];

/** 指定した言語の生成物を名前順に返す。1つの COBOL 本体から複数の生成物が出ることがある。 */
export function generatedFilesFor(
  files: readonly TranspileGeneratedFile[],
  language: TranspileLanguage,
): TranspileGeneratedFile[] {
  return files
    .filter((file) => file.language === language)
    .slice()
    .sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));
}

/**
 * 表示する生成物を決める。指定した名前がその言語の生成物にあればそれを、無ければ先頭を選ぶ。
 * 言語を切り替えた直後は前の言語の名前が残るため、後者の経路で先頭へ戻す。
 */
export function selectGeneratedFile(
  files: readonly TranspileGeneratedFile[],
  language: TranspileLanguage,
  preferredName: string | null,
): TranspileGeneratedFile | null {
  const candidates = generatedFilesFor(files, language);
  if (candidates.length === 0) {
    return null;
  }
  return candidates.find((file) => file.name === preferredName) ?? candidates[0];
}

/** 生成された言語を Python → Java の順で列挙する。 */
export function availableGeneratedLanguages(
  files: readonly TranspileGeneratedFile[],
): TranspileLanguage[] {
  const order: readonly TranspileLanguage[] = ["python", "java"];
  return order.filter((language) => files.some((file) => file.language === language));
}

/** 行内の桁範囲(Monaco の桁は 1 起点で、終端は最後の文字の次の桁を指す)。 */
export interface LineColumnRange {
  readonly line: number;
  readonly startColumn: number;
  readonly endColumn: number;
}

/**
 * 識別欄(73〜80桁)の範囲。72桁を超えて書かれた部分は原始プログラムの本体ではないため、
 * 本体と区別して示す。Monarch は桁位置を条件にできないため、装飾としてここで求める。
 * 桁はバイトで数えるため、DBCS を含む行では文字数と桁が一致しない(columns.ts)。
 */
export function identificationRanges(text: string, codepage: SourceCodepage): LineColumnRange[] {
  if (text === "") {
    return [];
  }
  const ranges: LineColumnRange[] = [];
  text.split(/\r\n|\n|\r/).forEach((line, index) => {
    const bodyEnd = charIndexAfterBytes(line, BODY_LAST_COLUMN, codepage);
    if (bodyEnd < line.length) {
      ranges.push({ line: index + 1, startColumn: bodyEnd + 1, endColumn: line.length + 1 });
    }
  });
  return ranges;
}

/** Monaco へ渡す装飾に使う CSS クラス。monaco が自身の行要素へ付けるため、単独で意味を持たせる。 */
export const LINE_CLASS = {
  linked: "ci-code__line--linked",
  focus: "ci-code__line--focus",
  noted: "ci-code__line--noted",
  identification: "ci-code__identification",
} as const;

/** Monaco の装飾1件(IModelDeltaDecoration の部分集合)。 */
export interface EditorDecoration {
  range: { startLineNumber: number; startColumn: number; endLineNumber: number; endColumn: number };
  options: { isWholeLine?: boolean; className?: string; inlineClassName?: string };
}

/** 装飾の入力。強調行・注記行・ジャンプ先の行・識別欄の範囲を渡す。 */
export interface DecorationInput {
  readonly linkedLines: readonly number[];
  readonly notedLines: readonly number[];
  readonly focusLine: number | null;
  readonly identification: readonly LineColumnRange[];
}

/** 行単位の装飾を組む。 */
function wholeLine(line: number, className: string): EditorDecoration {
  return {
    range: { startLineNumber: line, startColumn: 1, endLineNumber: line, endColumn: 1 },
    options: { isWholeLine: true, className },
  };
}

/**
 * ペインへ渡す装飾を組む。並びは注記行 → 対応行 → ジャンプ先の行 → 識別欄とし、
 * 後に置いた装飾を上へ重ねる(ジャンプ先の行を対応行より強く示す)。
 */
export function buildLineDecorations(input: DecorationInput): EditorDecoration[] {
  const decorations: EditorDecoration[] = [];
  for (const line of input.notedLines) {
    decorations.push(wholeLine(line, LINE_CLASS.noted));
  }
  for (const line of input.linkedLines) {
    decorations.push(wholeLine(line, LINE_CLASS.linked));
  }
  if (input.focusLine !== null) {
    decorations.push(wholeLine(input.focusLine, LINE_CLASS.focus));
  }
  for (const range of input.identification) {
    decorations.push({
      range: {
        startLineNumber: range.line,
        startColumn: range.startColumn,
        endLineNumber: range.line,
        endColumn: range.endColumn,
      },
      options: { inlineClassName: LINE_CLASS.identification },
    });
  }
  return decorations;
}

/** ファイル選択の選択肢。 */
export interface ViewerFileOption {
  readonly value: string;
  readonly label: string;
}

/** 資産一覧をファイル選択の選択肢へ写す。並びは資産一覧(相対パス昇順)をそのまま保つ。 */
export function viewerFileOptions(inventory: readonly AssetInventoryItem[]): ViewerFileOption[] {
  return inventory.map((item) => ({ value: item.path, label: item.path }));
}

/** 逐語対訳の対象か。対訳は COBOL 本体(NODE.type=PROGRAM)に対してのみ生成される。 */
export function isTranspileTarget(item: AssetInventoryItem | null): boolean {
  return item !== null && item.type === "PROGRAM";
}

/**
 * ジャンプ元の画面。JUMP が組んだ文言(「〈画面名〉 から …」)の先頭にある画面名から引く。
 * 戻り導線のためだけに用い、該当が無ければ戻り先を出さない。
 */
export function originScreen(sourceFrom: string | null): ScreenId | null {
  if (sourceFrom === null) {
    return null;
  }
  return SCREENS.find((screen) => sourceFrom.startsWith(screen.label))?.id ?? null;
}

/**
 * 相互ハイライトの状態を1行で示す。強調する行が片方も無い場合は、対応表に無い行である旨を示す
 * (対応が空であることを、対応の取得前と同じ文言で示す)。
 */
export function linkSummary(
  cobolLines: readonly number[],
  generatedLines: readonly number[],
): string {
  if (cobolLines.length === 0 && generatedLines.length === 0) {
    return "カーソル行に対応する行はない（逐語対訳の対応表に無い行）";
  }
  return `対応行を強調中 ― COBOL ${cobolLines.length} 行 ↔ 生成 ${generatedLines.length} 行`;
}
