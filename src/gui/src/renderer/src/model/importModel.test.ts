import { describe, expect, it } from "vitest";
import { clipColumns, clipLine, columnRuler, displayWidth, importRelPath } from "./importModel";

describe("cutting one line to a column range", () => {
  it("takes the columns, counting from one and including both ends", () => {
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

describe("cutting a pasted screen", () => {
  const PASTED = [
    "000100 IDENTIFICATION DIVISION.      ",
    "000200 PROGRAM-ID. SYK001.",
    "",
    "",
  ].join("\r\n");

  it("drops the line-number area, the padding and the blank lines below the source", () => {
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

describe("the column ruler", () => {
  it("marks every tenth column above the units", () => {
    expect(columnRuler(12)).toEqual({ tens: "         1  ", ones: "123456789012" });
  });
});

describe("where the import lands", () => {
  it("joins the destination folder and the name with forward slashes", () => {
    expect(importRelPath("cobol", "cobol\\src", "SYK001.cbl")).toBe("cobol/src/SYK001.cbl");
    expect(importRelPath("jcl", "", "SYKD010.jcl")).toBe("SYKD010.jcl");
  });

  it("completes a copybook's extension, because the COPY search looks for it", () => {
    expect(importRelPath("copybook", "", "SYKCPY1")).toBe("SYKCPY1.cpy");
    expect(importRelPath("copybook", "", "SYKCPY1.cpy")).toBe("SYKCPY1.cpy");
  });

  it("refuses a name that is empty, holds a separator, or climbs out of the folder", () => {
    expect(importRelPath("cobol", "", "")).toBeNull();
    expect(importRelPath("cobol", "", "a/b.cbl")).toBeNull();
    expect(importRelPath("cobol", "", ".cbl")).toBeNull();
    expect(importRelPath("cobol", "", "SYK001.")).toBeNull();
    expect(importRelPath("cobol", "../up", "SYK001.cbl")).toBeNull();
  });
});
