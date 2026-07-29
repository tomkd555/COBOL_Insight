/**
 * 端末エミュレータの画面から複写した本文を、固定形式の桁位置で切り出す。端末の表示では全角文字が
 * 2桁を占めるため、桁は文字数ではなく表示幅で数える。ここでの処理は表示と保存内容の組立だけで、
 * 構文解析・判定は engine CLI が担う。
 */

/** 表示幅が2桁になる符号位置の範囲(両端を含む)。東アジアの全角・広の文字を対象にする。 */
const WIDE_RANGES: ReadonlyArray<readonly [number, number]> = [
  [0x1100, 0x115f],
  [0x2e80, 0x303e],
  [0x3041, 0x33ff],
  [0x3400, 0x4dbf],
  [0x4e00, 0x9fff],
  [0xa000, 0xa4cf],
  [0xac00, 0xd7a3],
  [0xf900, 0xfaff],
  [0xfe30, 0xfe6f],
  // 全角形。半角カタカナ(FF61〜FF9F)は1桁のため範囲へ含めない。
  [0xff00, 0xff60],
  [0xffe0, 0xffe6],
  [0x20000, 0x2fffd],
  [0x30000, 0x3fffd],
];

/** 1文字の表示幅(1桁または2桁)。 */
function charWidth(char: string): number {
  const code = char.codePointAt(0) ?? 0;
  return WIDE_RANGES.some(([lo, hi]) => code >= lo && code <= hi) ? 2 : 1;
}

/** タブが送る桁の間隔。端末と CSS の tab-size の既定にそろえる。 */
const TAB_WIDTH = 8;

/**
 * タブを次の桁位置まで空白へ展開する。端末の画面から複写した本文にタブは現れないが、PC 側の
 * エディタを経由して貼り付けるときは混じる。展開しないまま切り出すと、桁定規が示す位置と
 * 保存内容の桁が食い違う。
 */
function expandTabs(line: string): string {
  if (!line.includes("\t")) {
    return line;
  }
  let expanded = "";
  let column = 0;
  for (const char of line) {
    if (char === "\t") {
      const stop = column + TAB_WIDTH - (column % TAB_WIDTH);
      expanded += " ".repeat(stop - column);
      column = stop;
      continue;
    }
    expanded += char;
    column += charWidth(char);
  }
  return expanded;
}

/** 文字列が端末で占める表示桁数。 */
export function displayWidth(text: string): number {
  let width = 0;
  for (const char of expandTabs(text)) {
    width += charWidth(char);
  }
  return width;
}

/**
 * 1行から表示桁 from〜to(1起点・両端を含む)を切り出す。範囲の境界にまたがる全角文字は、
 * 桁位置がずれないよう、範囲に入る桁数分の空白へ置き換える。
 */
export function clipLine(line: string, from: number, to: number): string {
  const parts: string[] = [];
  let column = 1;
  for (const char of expandTabs(line)) {
    const start = column;
    const end = column + charWidth(char) - 1;
    column = end + 1;
    if (end < from || start > to) {
      continue;
    }
    if (start < from || end > to) {
      parts.push(" ".repeat(Math.min(end, to) - Math.max(start, from) + 1));
      continue;
    }
    parts.push(char);
  }
  return parts.join("");
}

/** 改行(CRLF/CR/LF)で分割する。末尾の改行が生む空要素は落とす。 */
function splitLines(text: string): string[] {
  const lines = text.split(/\r\n|\n|\r/);
  if (lines.length > 1 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/**
 * 本文を行へ分け、各行を桁で切り出す。行末の空白は桁を埋めるだけの余白なので落とし、末尾の
 * 空行も落とす(端末の画面には本文の後に余白の行が並ぶ)。
 */
export function clipColumns(text: string, from: number, to: number): string[] {
  const lines = splitLines(text).map((line) => clipLine(line, from, to).trimEnd());
  while (lines.length > 0 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/** 桁定規の2行。tens は10桁ごとの位取り、ones は1桁ごとの目盛である。 */
export interface ColumnRuler {
  readonly tens: string;
  readonly ones: string;
}

/** 1桁目から width 桁までの桁定規を組み立てる。 */
export function columnRuler(width: number): ColumnRuler {
  let tens = "";
  let ones = "";
  for (let column = 1; column <= width; column++) {
    tens += column % 10 === 0 ? String((column / 10) % 10) : " ";
    ones += String(column % 10);
  }
  return { tens, ones };
}
