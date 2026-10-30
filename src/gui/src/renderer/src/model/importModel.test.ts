import { describe, expect, it } from "vitest";
import { clipColumns, clipLine, columnRuler, displayWidth } from "./importModel";

describe("clipLine", () => {
  it("clips columns inclusively, counting from one", () => {
    expect(clipLine("ABCDEFGH", 3, 5)).toBe("CDE");
  });

  it("counts a full-width character as the two cells it occupies", () => {
    // 受注 is columns 1-4; the ASCII text that follows starts at column 5.
    expect(displayWidth("受注")).toBe(4);
    expect(clipLine("受注DATA", 5, 8)).toBe("DATA");
    expect(clipLine("受注DATA", 1, 4)).toBe("受注");
  });

  it("replaces a character straddling the boundary with the cells it covered", () => {
    // Keeping half of a full-width character would move every column after it.
    // 受 covers columns 1-2 and 注 columns 3-4, so a range of 2 to 3 halves both.
    expect(clipLine("受注DATA", 2, 3)).toBe("  ");
    expect(clipLine("受注DATA", 2, 6)).toBe(" 注DA");
  });

  it("expands a tab to the next stop before counting", () => {
    expect(clipLine("A\tB", 1, 9)).toBe("A       B");
  });
});

describe("clipColumns", () => {
  const PASTED = [
    "000100 IDENTIFICATION DIVISION.      ",
    "000200 PROGRAM-ID. SYK001.",
    "",
    "",
  ].join("\r\n");

  it("returns the source columns with trailing blanks and blank lines dropped", () => {
    expect(clipColumns(PASTED, 8, 72)).toEqual([
      "IDENTIFICATION DIVISION.",
      "PROGRAM-ID. SYK001.",
    ]);
  });

  it("keeps a blank line inside the source", () => {
    expect(clipColumns("A\n\nB", 1, 10)).toEqual(["A", "", "B"]);
  });

  it("reads no text as no lines", () => {
    expect(clipColumns("", 1, 80)).toEqual([]);
  });
});

describe("columnRuler", () => {
  it("marks every tenth column above the units", () => {
    expect(columnRuler(12)).toEqual({ tens: "         1  ", ones: "123456789012" });
  });
});
