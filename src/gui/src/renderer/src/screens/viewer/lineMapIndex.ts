/**
 * 逐語対訳の行対応索引。engine の LINE_MAP(transpile が SQLite へ書く対応表)から、
 * 「COBOL 行 → 生成行範囲」と「生成行 → COBOL 行範囲」の双方向の引きを作る純ロジックである。
 * 索引は生成ファイル1件を単位とする。生成ファイル名は PROGRAM-ID とレコード名から決まり
 * COBOL のファイル名と一致しないため、どの生成物がどの COBOL 行に対応するかは LINE_MAP の
 * gen_file だけが示す。
 *
 * 対応は多対一・一対多を含み、範囲が重なることもある。したがって1つの行が複数の対応に属し、
 * 引いた結果は複数範囲の和になる。対応の無い行は空を返し、画面はその旨を示す。
 */

import type { LineMapEntry } from "../../../../shared/engine-api";

/** 1 起点で両端を含む行範囲。 */
export interface LineRange {
  readonly start: number;
  readonly end: number;
}

/** 索引が持つ1件の対応。 */
export interface LineMapLink {
  readonly id: number;
  readonly cobol: LineRange;
  readonly generated: LineRange;
  /** 対応の多重度(engine の LINE_MAP.kind。"1:1" / "1:N" / "N:1")。 */
  readonly kind: string;
  /** 直訳できなかった箇所の注記。空文字は注記なし。 */
  readonly note: string;
}

/** 生成ファイル1件分の行対応索引。 */
export interface LineMapIndex {
  readonly genFile: string;
  /** 生成開始行・COBOL 開始行・id の順に並べた対応。 */
  readonly links: readonly LineMapLink[];
}

/** ある行を起点に引いた、両ペインで強調する行の集合。 */
export interface LinkedLines {
  readonly cobolLines: readonly number[];
  readonly generatedLines: readonly number[];
  /** 引きに一致した対応。注記の提示に使う。 */
  readonly links: readonly LineMapLink[];
}

/** 直訳できなかった箇所の注記1件。 */
export interface TranslationNote {
  readonly note: string;
  readonly cobol: LineRange;
  readonly generated: LineRange;
  readonly kind: string;
}

/** 対応が無いときの結果。参照を固定して useMemo の依存を安定させる。 */
export const NO_LINKED_LINES: LinkedLines = { cobolLines: [], generatedLines: [], links: [] };

/**
 * LINE_MAP の1行を対応へ写す。行番号が 1 未満の対応は行として指せないため落とす。
 * 終端が開始より小さい対応は、範囲を失わせないため単一行として扱う。
 */
function toLink(entry: LineMapEntry): LineMapLink | null {
  if (entry.cobolLineStart < 1 || entry.genLineStart < 1) {
    return null;
  }
  return {
    id: entry.id,
    cobol: { start: entry.cobolLineStart, end: Math.max(entry.cobolLineStart, entry.cobolLineEnd) },
    generated: { start: entry.genLineStart, end: Math.max(entry.genLineStart, entry.genLineEnd) },
    kind: entry.kind,
    note: entry.note,
  };
}

/** 指定した生成ファイルの対応だけを集めた索引を作る。 */
export function buildLineMapIndex(entries: readonly LineMapEntry[], genFile: string): LineMapIndex {
  const links: LineMapLink[] = [];
  for (const entry of entries) {
    if (entry.genFile !== genFile) {
      continue;
    }
    const link = toLink(entry);
    if (link !== null) {
      links.push(link);
    }
  }
  links.sort((a, b) => {
    if (a.generated.start !== b.generated.start) return a.generated.start - b.generated.start;
    if (a.cobol.start !== b.cobol.start) return a.cobol.start - b.cobol.start;
    return a.id - b.id;
  });
  return { genFile, links };
}

/** 行範囲を含む行が範囲内にあるか。 */
function contains(range: LineRange, line: number): boolean {
  return line >= range.start && line <= range.end;
}

/** 複数の行範囲を、昇順で重複のない行番号の並びへ広げる。 */
function spread(ranges: readonly LineRange[]): number[] {
  const lines = new Set<number>();
  for (const range of ranges) {
    for (let line = range.start; line <= range.end; line += 1) {
      lines.add(line);
    }
  }
  return [...lines].sort((a, b) => a - b);
}

/** 一致した対応から、両ペインで強調する行を組む。 */
function linkedFrom(links: readonly LineMapLink[]): LinkedLines {
  if (links.length === 0) {
    return NO_LINKED_LINES;
  }
  return {
    cobolLines: spread(links.map((link) => link.cobol)),
    generatedLines: spread(links.map((link) => link.generated)),
    links,
  };
}

/**
 * COBOL 行から対応する生成行を引く。対応が複数あるときは、COBOL 側の強調も一致した
 * 対応の範囲全体へ広げる(1行だけを光らせて範囲の広がりを隠さない)。
 */
export function linkFromCobolLine(index: LineMapIndex, line: number): LinkedLines {
  return linkedFrom(index.links.filter((link) => contains(link.cobol, line)));
}

/** 生成行から対応する COBOL 行を引く。 */
export function linkFromGeneratedLine(index: LineMapIndex, line: number): LinkedLines {
  return linkedFrom(index.links.filter((link) => contains(link.generated, line)));
}

/** 直訳できなかった箇所の注記を、生成行順に返す。 */
export function translationNotes(index: LineMapIndex): TranslationNote[] {
  return index.links
    .filter((link) => link.note !== "")
    .map((link) => ({ note: link.note, cobol: link.cobol, generated: link.generated, kind: link.kind }));
}

/** 注記を持つ対応の COBOL 側の行(昇順・重複なし)。 */
export function notedCobolLines(index: LineMapIndex): number[] {
  return spread(index.links.filter((link) => link.note !== "").map((link) => link.cobol));
}

/** 注記を持つ対応の生成側の行(昇順・重複なし)。 */
export function notedGeneratedLines(index: LineMapIndex): number[] {
  return spread(index.links.filter((link) => link.note !== "").map((link) => link.generated));
}

/** 対応の多重度の日本語表示名。engine の綴り以外はそのまま返す。 */
export function mappingKindLabel(kind: string): string {
  if (kind === "1:1") return "1対1";
  if (kind === "1:N") return "1対多";
  if (kind === "N:1") return "多対1";
  return kind;
}
