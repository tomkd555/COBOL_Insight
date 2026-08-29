/**
 * Fixed-format byte columns (pure, independent of React and of Monaco).
 *
 * COBOL's areas are byte columns, so a line holding double-byte characters has no fixed relation
 * between its character count and its column positions. The engine's `decode` reports the boundaries
 * of every line and is authoritative both when a file is opened and when it is saved; this module
 * recomputes them locally while the text is being edited, where asking the engine after every
 * keystroke is not an option.
 *
 * The codepage vocabulary itself lives in shared/codepage; this module only maps a name onto the
 * byte-width rule it implies.
 */

import { normalizeCodepage } from "../../../shared/codepage";

/** The codepages whose byte widths this module can reproduce. */
export type EditorCodepage = "UTF-8" | "Shift_JIS" | "IBM930" | "IBM939";

/** The byte columns `DecodedLine.boundaries` reports, in order: indicator, area A, area B, identification. */
export const AREA_COLUMNS: readonly [number, number, number, number] = [7, 8, 12, 73];

/** The first byte column past the identification area. Nothing beyond it belongs to the record. */
export const OVERFLOW_COLUMN = 81;

/** The value `decode` uses for a column the line is too short to reach. */
export const UNREACHABLE = -1;

const ENCODER = new TextEncoder();

/** Half-width katakana (JIS X 0201). One byte in Shift_JIS and in the EBCDIC katakana half. */
const HALFWIDTH_KATAKANA_FIRST = 0xff61;
const HALFWIDTH_KATAKANA_LAST = 0xff9f;

/**
 * Maps a codepage name onto the width rule to use. An unknown name falls back to UTF-8, which is
 * what an editor holds when nothing else is known.
 */
export function editorCodepageOf(codepage: string | null | undefined): EditorCodepage {
  const normalized = normalizeCodepage(codepage);
  return normalized === null ? "UTF-8" : (normalized as EditorCodepage);
}

/** Whether the codepage carries shift-out/shift-in bytes around its double-byte runs. */
function usesShifts(codepage: EditorCodepage): boolean {
  return codepage === "IBM930" || codepage === "IBM939";
}

/**
 * Whether the character needs the double-byte half of a Japanese codepage.
 *
 * ponytail: for CP930/CP939 this is an estimate — the true single-byte half differs between the two
 * (CP930 is katakana, CP939 is Latin) and neither covers every character an editor can hold. The
 * estimate treats ASCII and half-width katakana as single-byte and everything else as double-byte,
 * which is exact for the fixed-format sources this tool reads. The engine's `decode` and `save` are
 * authoritative; upgrade to a real CP930/CP939 table only if a boundary is ever seen to disagree.
 */
function isWide(char: string, codepage: EditorCodepage): boolean {
  const code = char.codePointAt(0) ?? 0;
  if (code <= 0x7f) {
    return false;
  }
  if (codepage === "IBM939") {
    // The Latin half holds no katakana, so half-width katakana is double-byte there.
    return true;
  }
  return code < HALFWIDTH_KATAKANA_FIRST || code > HALFWIDTH_KATAKANA_LAST;
}

/** The byte width of one character (one code point), shift bytes excluded. */
function charBytes(char: string, codepage: EditorCodepage): number {
  if (codepage === "UTF-8") {
    return ENCODER.encode(char).length;
  }
  return isWide(char, codepage) ? 2 : 1;
}

/**
 * Walks the line, calling `visit` with the one-based byte column each character starts at and the
 * character's UTF-16 offset. Returns the line's total byte length.
 *
 * EBCDIC's shift-out and shift-in bytes are not part of any character: one is spent entering a
 * double-byte run and one leaving it, so they are added between characters rather than to them.
 */
function walk(
  line: string,
  codepage: EditorCodepage,
  visit: (byteColumn: number, offset: number, width: number) => void,
): number {
  const shifts = usesShifts(codepage);
  let byteColumn = 1;
  let offset = 0;
  let inDbcs = false;
  for (const char of line) {
    const wide = isWide(char, codepage);
    if (shifts && wide !== inDbcs) {
      byteColumn += 1;
      inDbcs = wide;
    }
    const width = charBytes(char, codepage);
    visit(byteColumn, offset, width);
    byteColumn += width;
    offset += char.length;
  }
  if (shifts && inDbcs) {
    byteColumn += 1;
  }
  return byteColumn - 1;
}

/**
 * The line's length in bytes, its line break excluded. `decode` counts the line break in its own
 * `byteLength`; what the editor needs is the width of the record itself.
 */
export function byteLengthOf(line: string, codepage: EditorCodepage): number {
  return walk(line, codepage, () => undefined);
}

/**
 * The UTF-16 character offset at which the given one-based byte column begins, or {@link UNREACHABLE}
 * when the line is too short to reach it.
 *
 * A column that falls inside a double-byte character does not split it: the boundary moves on to the
 * next character, so an area never starts half way through one. Where every character starts before
 * the column, the answer is the position one past the last character — which is what makes an
 * exactly 72-byte line report the start of its empty identification area rather than nothing at all.
 * That is only reachable up to the column the line break itself occupies, since `decode` counts the
 * break in a line's byte length; past that the column does not exist.
 */
export function charOffsetAtByteColumn(
  line: string,
  byteColumn: number,
  codepage: EditorCodepage,
): number {
  let found = UNREACHABLE;
  const total = walk(line, codepage, (column, offset) => {
    if (found === UNREACHABLE && column >= byteColumn) {
      found = offset;
    }
  });
  if (found !== UNREACHABLE) {
    return found;
  }
  return byteColumn <= total + 1 ? line.length : UNREACHABLE;
}

/** The four area boundaries of one line, in the same shape and order `decode` reports them. */
export function boundariesOf(
  line: string,
  codepage: EditorCodepage,
): [number, number, number, number] {
  return [
    charOffsetAtByteColumn(line, AREA_COLUMNS[0], codepage),
    charOffsetAtByteColumn(line, AREA_COLUMNS[1], codepage),
    charOffsetAtByteColumn(line, AREA_COLUMNS[2], codepage),
    charOffsetAtByteColumn(line, AREA_COLUMNS[3], codepage),
  ];
}

/** One area of one line, in Monaco's one-based line and column numbering. */
export interface AreaRange {
  readonly line: number;
  readonly startColumn: number;
  readonly endColumn: number;
  /** The CSS class the decoration carries. */
  readonly className: string;
}

/** The class names the areas are drawn with. */
export const AREA_CLASS = {
  sequence: "ci-code__sequence",
  indicator: "ci-code__indicator",
  identification: "ci-code__identification",
  overflow: "ci-code__overflow",
} as const;

/** The indicator characters that get their own class, so a comment line reads differently. */
const INDICATOR_CLASS: Readonly<Record<string, string>> = {
  "*": "ci-code__indicator--comment",
  "/": "ci-code__indicator--comment",
  D: "ci-code__indicator--debug",
  d: "ci-code__indicator--debug",
  "-": "ci-code__indicator--continuation",
};

/**
 * The area decorations for one document.
 *
 * `boundaries` comes from `decode` while the text is untouched and from {@link decodedLinesOf} once
 * it has been edited; either way this function only turns offsets into ranges. The overflow area
 * (past byte column 80) has no boundary of its own in the engine's result and is computed here.
 */
export function areaRanges(
  lines: readonly string[],
  boundaries: readonly (readonly [number, number, number, number])[],
  codepage: EditorCodepage,
): AreaRange[] {
  const ranges: AreaRange[] = [];
  lines.forEach((text, index) => {
    const line = index + 1;
    const bounds = boundaries[index];
    if (bounds === undefined) {
      return;
    }
    const [indicator, areaA, , identification] = bounds;
    if (indicator > 0) {
      ranges.push({ line, startColumn: 1, endColumn: indicator + 1, className: AREA_CLASS.sequence });
    }
    if (indicator !== UNREACHABLE) {
      // The indicator is one byte column wide; area A starts at the next character.
      const end = areaA === UNREACHABLE ? text.length : areaA;
      const extra = INDICATOR_CLASS[text.slice(indicator, indicator + 1)];
      ranges.push({
        line,
        startColumn: indicator + 1,
        endColumn: end + 1,
        className: extra === undefined ? AREA_CLASS.indicator : `${AREA_CLASS.indicator} ${extra}`,
      });
    }
    if (identification !== UNREACHABLE) {
      const overflow = charOffsetAtByteColumn(text, OVERFLOW_COLUMN, codepage);
      const end = overflow === UNREACHABLE ? text.length : overflow;
      ranges.push({
        line,
        startColumn: identification + 1,
        endColumn: end + 1,
        className: AREA_CLASS.identification,
      });
      if (overflow !== UNREACHABLE) {
        ranges.push({
          line,
          startColumn: overflow + 1,
          endColumn: text.length + 1,
          className: AREA_CLASS.overflow,
        });
      }
    }
  });
  return ranges;
}
