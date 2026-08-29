import { describe, expect, it } from "vitest";
import { resolve } from "node:path";
import { importRelPath, importSource, type ImportFileSystem } from "./importSource";

function memory(existing: string[] = []): ImportFileSystem & { written: Map<string, string> } {
  const written = new Map<string, string>();
  const present = new Set(existing.map((path) => resolve(path)));
  return {
    written,
    makeDir: async () => undefined,
    exists: async (absPath) => present.has(resolve(absPath)),
    realPath: async (absPath) => absPath,
    writeText: async (absPath, text) => {
      written.set(resolve(absPath), text);
    },
  };
}

describe("importRelPath", () => {
  it("joins the destination folder and the file name with forward slashes", () => {
    expect(importRelPath("cobol", "cobol/new", "A.cbl")).toBe("cobol/new/A.cbl");
  });

  it("puts a file straight into the asset folder when no destination is given", () => {
    expect(importRelPath("cobol", "", "A.cbl")).toBe("A.cbl");
  });

  it("adds .cpy to a copybook that lacks it, and only to a copybook", () => {
    expect(importRelPath("copybook", "", "SYKCPY1")).toBe("SYKCPY1.cpy");
    expect(importRelPath("copybook", "", "SYKCPY1.CPY")).toBe("SYKCPY1.CPY");
    expect(importRelPath("cobol", "", "SYK001")).toBe("SYK001");
  });

  it("rejects a destination that climbs out, an absolute name, or a name with separators", () => {
    expect(importRelPath("cobol", "..", "A.cbl")).toBeNull();
    expect(importRelPath("cobol", "a/../..", "A.cbl")).toBeNull();
    expect(importRelPath("cobol", "", "C:/A.cbl")).toBeNull();
    expect(importRelPath("cobol", "", "sub/A.cbl")).toBeNull();
    expect(importRelPath("cobol", "", "  ")).toBeNull();
  });
});

describe("importSource", () => {
  const base = { inputDir: "C:/assets", kind: "cobol" as const, destDir: "cobol", overwrite: false };

  it("writes the lines with CRLF endings and a trailing newline", async () => {
    const fs = memory();
    const result = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE", "TWO"] });
    expect(result).toEqual({ status: "written", relPath: "cobol/A.cbl", lineCount: 2 });
    expect(fs.written.get(resolve("C:/assets/cobol/A.cbl"))).toBe("ONE\r\nTWO\r\n");
  });

  it("refuses to overwrite unless allowed", async () => {
    const fs = memory(["C:/assets/cobol/A.cbl"]);
    const blocked = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] });
    expect(blocked).toEqual({ status: "exists", relPath: "cobol/A.cbl", lineCount: 0 });
    expect(fs.written.size).toBe(0);

    const allowed = await importSource(fs, {
      ...base,
      fileName: "A.cbl",
      lines: ["ONE"],
      overwrite: true,
    });
    expect(allowed.status).toBe("written");
  });

  it("refuses a destination outside the asset folder", async () => {
    await expect(
      importSource(memory(), { ...base, destDir: "..", fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/unusable destination/);
  });

  it("refuses when the destination folder is a link leading outside the asset folder", async () => {
    const fs = memory();
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? "C:/elsewhere" : absPath;
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/outside the asset folder/);
  });

  it("allows a file directly in the asset folder, where base and destination coincide", async () => {
    const fs = memory();
    const result = await importSource(fs, {
      ...base,
      destDir: "",
      fileName: "A.cbl",
      lines: ["ONE"],
    });
    expect(result.relPath).toBe("A.cbl");
  });

  it("refuses an import with no lines", async () => {
    await expect(
      importSource(memory(), { ...base, fileName: "A.cbl", lines: [] }),
    ).rejects.toThrow(/no text to import/);
  });
});
