import { describe, expect, it } from "vitest";
import {
  AREA_CLASS,
  UNREACHABLE,
  areaRanges,
  boundariesOf,
  byteLengthOf,
  charOffsetAtByteColumn,
  editorCodepageOf,
} from "./columns";

/*
 * The lines below are worked out by hand, byte by byte, because that is the only way to tell whether
 * the local recomputation still matches what the engine reports.
 *
 * "000100* 受注" holds six digits, an asterisk, a space and two double-byte characters:
 *   Shift_JIS  1-6 digits, 7 '*', 8 ' ', 9-10 '受', 11-12 '注'          (12 bytes)
 *   UTF-8      1-6 digits, 7 '*', 8 ' ', 9-11 '受', 12-14 '注'          (14 bytes)
 *   CP930      1-6 digits, 7 '*', 8 ' ', 9 shift-out, 10-11 '受',
 *              12-13 '注', 14 shift-in                                  (14 bytes)
 */
const DBCS_LINE = "000100* 受注";

describe("editorCodepageOf", () => {
  it("takes the aliases the rest of the tool accepts", () => {
    expect(editorCodepageOf("SJIS")).toBe("Shift_JIS");
    expect(editorCodepageOf("cp930")).toBe("IBM930");
    expect(editorCodepageOf("x-IBM939")).toBe("IBM939");
  });

  it("falls back to UTF-8 for a name it does not know", () => {
    expect(editorCodepageOf("EUC-JP")).toBe("UTF-8");
    expect(editorCodepageOf(null)).toBe("UTF-8");
  });
});

describe("byteLengthOf", () => {
  it("counts ASCII as one byte in every codepage", () => {
    expect(byteLengthOf("000100 MOVE A TO B.", "Shift_JIS")).toBe(19);
    expect(byteLengthOf("000100 MOVE A TO B.", "IBM930")).toBe(19);
    expect(byteLengthOf("000100 MOVE A TO B.", "UTF-8")).toBe(19);
  });

  it("counts a double-byte character as two in Shift_JIS and three in UTF-8", () => {
    expect(byteLengthOf(DBCS_LINE, "Shift_JIS")).toBe(12);
    expect(byteLengthOf(DBCS_LINE, "UTF-8")).toBe(14);
  });

  it("adds the shift-out and shift-in bytes around an EBCDIC double-byte run", () => {
    // 8 single-byte characters, shift-out, two double-byte characters, shift-in.
    expect(byteLengthOf(DBCS_LINE, "IBM930")).toBe(14);
  });

  it("counts half-width katakana as one byte in CP930 and two in CP939", () => {
    expect(byteLengthOf("ｱｲ", "Shift_JIS")).toBe(2);
    expect(byteLengthOf("ｱｲ", "IBM930")).toBe(2);
    // CP939's single-byte half is Latin, so katakana has to be shifted into the double-byte half.
    expect(byteLengthOf("ｱｲ", "IBM939")).toBe(6);
  });
});

describe("charOffsetAtByteColumn", () => {
  it("gives the character a byte column starts at", () => {
    expect(charOffsetAtByteColumn(DBCS_LINE, 7, "Shift_JIS")).toBe(6);
    expect(charOffsetAtByteColumn(DBCS_LINE, 8, "Shift_JIS")).toBe(7);
  });

  it("moves on to the next character when a column falls inside a double-byte one", () => {
    // In Shift_JIS byte column 11 is the first byte of the tenth character, so it starts there.
    expect(charOffsetAtByteColumn(DBCS_LINE, 11, "Shift_JIS")).toBe(9);
    // In UTF-8 the same column is the third byte of the ninth character; an area never starts half
    // way through a character, so the boundary moves on to the tenth.
    expect(charOffsetAtByteColumn(DBCS_LINE, 11, "UTF-8")).toBe(9);
  });

  it("reports a column the line is too short to reach", () => {
    expect(charOffsetAtByteColumn(DBCS_LINE, 73, "Shift_JIS")).toBe(UNREACHABLE);
    expect(charOffsetAtByteColumn("AB", 4, "UTF-8")).toBe(UNREACHABLE);
  });

  it("takes the column the line break falls on as the position past the last character", () => {
    // decode counts the line break in a line's byte length, so the column just past the content is
    // reachable; an exactly 72-byte line reports the start of its empty identification area.
    expect(charOffsetAtByteColumn("AB", 3, "UTF-8")).toBe(2);
    expect(charOffsetAtByteColumn(DBCS_LINE, 13, "Shift_JIS")).toBe(DBCS_LINE.length);
  });

  it("counts a surrogate pair as one character but four UTF-8 bytes", () => {
    const line = "000100 \u{20BB7}";
    expect(byteLengthOf(line, "UTF-8")).toBe(11);
    // The pair occupies byte columns 8 to 11 and two UTF-16 units, at offset 7.
    expect(charOffsetAtByteColumn(line, 8, "UTF-8")).toBe(7);
    // Column 11 is the pair's last byte; the boundary moves past it, to the end of the line, which
    // counts the pair as the two UTF-16 units Monaco's own columns count it as.
    expect(charOffsetAtByteColumn(line, 11, "UTF-8")).toBe(9);
    expect(line.length).toBe(9);
    expect(charOffsetAtByteColumn(line, 13, "UTF-8")).toBe(UNREACHABLE);
  });
});

describe("boundariesOf", () => {
  it("reports the four areas in the order decode reports them", () => {
    // Byte column 12 is the second byte of the last character in Shift_JIS, so the boundary lands
    // past it; in UTF-8 and CP930 that character starts exactly there.
    expect(boundariesOf(DBCS_LINE, "Shift_JIS")).toEqual([6, 7, 10, UNREACHABLE]);
    expect(boundariesOf(DBCS_LINE, "UTF-8")).toEqual([6, 7, 9, UNREACHABLE]);
    expect(boundariesOf(DBCS_LINE, "IBM930")).toEqual([6, 7, 9, UNREACHABLE]);
  });

  it("finds the identification area on a full 80-column line", () => {
    const line = `000100${" ".repeat(66)}SYK00110`;
    expect(boundariesOf(line, "Shift_JIS")).toEqual([6, 7, 11, 72]);
  });
});

describe("areaRanges", () => {
  const line = `000100*${" ".repeat(65)}SYK00110EXTRA`;
  const ranges = areaRanges([line], [boundariesOf(line, "Shift_JIS")], "Shift_JIS");

  it("marks the sequence area as the first six columns", () => {
    const sequence = ranges.find((range) => range.className === AREA_CLASS.sequence);
    expect(sequence).toEqual({
      line: 1,
      startColumn: 1,
      endColumn: 7,
      className: AREA_CLASS.sequence,
    });
  });

  it("tells a comment indicator apart from an ordinary one", () => {
    const indicator = ranges.find((range) => range.className.startsWith(AREA_CLASS.indicator));
    expect(indicator?.className).toContain("ci-code__indicator--comment");
    expect(indicator?.startColumn).toBe(7);
    expect(indicator?.endColumn).toBe(8);
  });

  it("marks columns 73 to 80 as the identification area and the rest as overflow", () => {
    const identification = ranges.find((range) => range.className === AREA_CLASS.identification);
    expect(identification).toEqual({
      line: 1,
      startColumn: 73,
      endColumn: 81,
      className: AREA_CLASS.identification,
    });
    const overflow = ranges.find((range) => range.className === AREA_CLASS.overflow);
    expect(overflow).toEqual({
      line: 1,
      startColumn: 81,
      endColumn: line.length + 1,
      className: AREA_CLASS.overflow,
    });
  });

  it("leaves a line with no identification area alone", () => {
    const short = areaRanges(["000100 MOVE"], [boundariesOf("000100 MOVE", "UTF-8")], "UTF-8");
    expect(short.some((range) => range.className === AREA_CLASS.identification)).toBe(false);
    expect(short.some((range) => range.className === AREA_CLASS.overflow)).toBe(false);
  });
});
