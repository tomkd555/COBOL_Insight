/**
 * Cutting a column range out of text copied from a terminal emulator.
 *
 * A terminal screen puts the line-number and command areas to the left of the source, so pasting
 * the copy as it stands shifts every column. The user names the columns to keep and the text is cut
 * to them. Columns are counted in display cells, not characters: a full-width character occupies two
 * cells on the terminal, and counting characters would move the fixed-format areas out of place.
 *
 * The file is written by main (fs:import-source); this module only prepares the lines and shows
 * where they will land.
 */

/** Code point ranges that occupy two display cells. */
const WIDE_RANGES: readonly (readonly [number, number])[] = [
  [0x1100, 0x115f],
  [0x2e80, 0x303e],
  [0x3041, 0x33ff],
  [0x3400, 0x4dbf],
  [0x4e00, 0x9fff],
  [0xa000, 0xa4cf],
  [0xac00, 0xd7a3],
  [0xf900, 0xfaff],
  [0xfe30, 0xfe6f],
  // Full-width forms. Half-width katakana (FF61-FF9F) is one cell and is deliberately outside this.
  [0xff00, 0xff60],
  [0xffe0, 0xffe6],
  [0x20000, 0x2fffd],
  [0x30000, 0x3fffd],
];

function charWidth(char: string): number {
  const code = char.codePointAt(0) ?? 0;
  return WIDE_RANGES.some(([low, high]) => code >= low && code <= high) ? 2 : 1;
}

/** How far a tab advances, as both the terminal and the CSS default have it. */
const TAB_WIDTH = 8;

/**
 * Expands tabs to the next stop. A terminal copy carries no tab, but a paste that went through a
 * text editor on the way can; cutting without expanding would put the columns out of step with the
 * ruler the user read them from.
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

/** How many display cells the text occupies. */
export function displayWidth(text: string): number {
  let width = 0;
  for (const char of expandTabs(text)) {
    width += charWidth(char);
  }
  return width;
}

/**
 * Cuts display columns `from` to `to` (1-based, both ends included) out of one line. A full-width
 * character straddling either boundary becomes the spaces it covered inside the range, so the
 * columns after it stay where they were.
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

/** Splits on CRLF, CR or LF, dropping the empty element a trailing newline leaves behind. */
function splitLines(text: string): string[] {
  const lines = text.split(/\r\n|\n|\r/);
  if (lines.length > 1 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/**
 * Cuts the whole text to the column range. Trailing spaces are padding to the edge of the screen
 * and are dropped, as are the blank lines a terminal leaves below the source.
 */
export function clipColumns(text: string, from: number, to: number): string[] {
  const lines = splitLines(text).map((line) => clipLine(line, from, to).trimEnd());
  while (lines.length > 0 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

/** The two rows of the column ruler: tens above, units below. */
export interface ColumnRuler {
  readonly tens: string;
  readonly ones: string;
}

export function columnRuler(width: number): ColumnRuler {
  let tens = "";
  let ones = "";
  for (let column = 1; column <= width; column++) {
    tens += column % 10 === 0 ? String((column / 10) % 10) : " ";
    ones += String(column % 10);
  }
  return { tens, ones };
}

/** The extension a copybook is given, because the engine's COPY search looks for it. */
const COPYBOOK_EXTENSION = ".cpy";

/** Characters a file name cannot hold: the path separators and what Windows reserves. */
const FORBIDDEN_IN_NAME = /[\\/:*?"<>|]/;

/**
 * Where the import will land, relative to the asset folder, or null when the name cannot be used.
 * This is the same rule main applies before writing; it is repeated here only to show the
 * destination and to keep the button from offering a save that would be refused.
 */
export function importRelPath(
  kind: string,
  destDir: string,
  fileName: string,
): string | null {
  const name = fileName.trim();
  if (
    name === "" ||
    FORBIDDEN_IN_NAME.test(name) ||
    name.startsWith(".") ||
    /[. ]$/.test(name) ||
    Array.from(name).some((char) => (char.codePointAt(0) ?? 0) < 0x20)
  ) {
    return null;
  }
  const segments = destDir
    .split(/[\\/]/)
    .map((segment) => segment.trim())
    .filter((segment) => segment !== "");
  if (segments.some((segment) => segment === "." || segment === "..")) {
    return null;
  }
  const withExtension =
    kind === "copybook" && !name.toLowerCase().endsWith(COPYBOOK_EXTENSION)
      ? `${name}${COPYBOOK_EXTENSION}`
      : name;
  return [...segments, withExtension].join("/");
}
