import { describe, expect, it } from "vitest";
import { importDirSegments, importFileName, importRelPath } from "./importPath";

describe("where the import lands", () => {
  it("joins the destination folder and the name with forward slashes", () => {
    expect(importRelPath("cobol/new", "A.cbl")).toBe("cobol/new/A.cbl");
    expect(importRelPath("cobol\\src", "SYK001.cbl")).toBe("cobol/src/SYK001.cbl");
  });

  it("puts a file straight into the asset folder when no destination is given", () => {
    expect(importRelPath("", "SYKD010.jcl")).toBe("SYKD010.jcl");
  });

  it("uses the typed file name as-is, without the blanks around it", () => {
    expect(importRelPath("", "SYKCPY1")).toBe("SYKCPY1");
    expect(importRelPath("", "SYKCPY1.CPY")).toBe("SYKCPY1.CPY");
    expect(importRelPath(" cobol ", " A.cbl ")).toBe("cobol/A.cbl");
  });

  it("refuses a name that is empty, absolute, or holds a separator or a reserved character", () => {
    expect(importRelPath("", "")).toBeNull();
    expect(importRelPath("", "  ")).toBeNull();
    expect(importRelPath("", "C:/A.cbl")).toBeNull();
    expect(importRelPath("", "sub/A.cbl")).toBeNull();
    expect(importRelPath("", "a\\b.cbl")).toBeNull();
    expect(importRelPath("", "A?.cbl")).toBeNull();
    expect(importRelPath("", "A\u0007.cbl")).toBeNull();
  });

  it("refuses a name that starts with a dot or ends in a dot", () => {
    expect(importRelPath("", ".cbl")).toBeNull();
    expect(importRelPath("", "SYK001.")).toBeNull();
  });

  it("refuses a destination that climbs out of the asset folder", () => {
    expect(importRelPath("..", "A.cbl")).toBeNull();
    expect(importRelPath("a/../..", "A.cbl")).toBeNull();
    expect(importRelPath("../up", "SYK001.cbl")).toBeNull();
    expect(importRelPath("./here", "A.cbl")).toBeNull();
  });

  it("refuses a destination that starts at a root or a drive, or holds a name Windows cannot keep", () => {
    expect(importDirSegments("C:\\work")).toBeNull();
    expect(importDirSegments("/abs")).toBeNull();
    expect(importDirSegments("\\\\server\\share")).toBeNull();
    expect(importDirSegments("cobol/x:y")).toBeNull();
    expect(importDirSegments("cobol/a*b")).toBeNull();
    expect(importDirSegments("cobol/new.")).toBeNull();
  });

  it("refuses the names Windows keeps for devices, with or without an extension", () => {
    for (const name of ["NUL", "con.cbl", "COM1.txt", "LPT9", "aux"]) {
      expect(importFileName(name)).toBeNull();
    }
    expect(importDirSegments("aux/src")).toBeNull();
  });

  it("refuses PRN, the superscript device numbers and a bare CONOUT$", () => {
    for (const name of ["PRN", "COM¹.cbl", "LPT².txt", "COM³", "CONOUT$"]) {
      expect(importFileName(name)).toBeNull();
    }
  });

  it("refuses a device name in a folder segment after the first, and one with blanks before its extension", () => {
    expect(importDirSegments("src/CON")).toBeNull();
    expect(importRelPath("src/CON", "A.cbl")).toBeNull();
    expect(importDirSegments("con .cbl")).toBeNull();
    expect(importDirSegments("src/con .cbl/deeper")).toBeNull();
  });

  it("keeps the names that only begin with a device name", () => {
    expect(importFileName("NULL.cbl")).toBe("NULL.cbl");
    expect(importFileName("CONSOLE.cbl")).toBe("CONSOLE.cbl");
  });

  it("refuses a device name followed by blanks before its extension", () => {
    expect(importFileName("NUL .cbl")).toBeNull();
  });

  it("refuses the console devices, with or without an extension", () => {
    for (const name of ["CONIN$", "CONOUT$.txt"]) {
      expect(importFileName(name)).toBeNull();
    }
  });

  it("refuses device number 0 of the serial and printer ports", () => {
    for (const name of ["COM0", "LPT0.cbl"]) {
      expect(importFileName(name)).toBeNull();
    }
  });

  it("keeps COM10 and CONIN, which are the names of no device", () => {
    expect(importFileName("COM10.cbl")).toBe("COM10.cbl");
    expect(importFileName("CONIN.cbl")).toBe("CONIN.cbl");
  });

  it("keeps a file name or a folder name of 255 characters and refuses one of 256", () => {
    const longest = `${"A".repeat(251)}.cbl`;
    expect(importFileName(longest)).toBe(longest);
    expect(importFileName(`A${longest}`)).toBeNull();
    expect(importDirSegments(`src/${"B".repeat(255)}`)).toEqual(["src", "B".repeat(255)]);
    expect(importDirSegments(`src/${"B".repeat(256)}`)).toBeNull();
    expect(importRelPath("B".repeat(256), "A.cbl")).toBeNull();
  });

  it("reads the folder and the name each on its own, so each field carries its own error", () => {
    expect(importDirSegments("cobol/new")).toEqual(["cobol", "new"]);
    expect(importDirSegments("")).toEqual([]);
    expect(importFileName(" A.cbl ")).toBe("A.cbl");
    expect(importFileName("a/b")).toBeNull();
  });
});
