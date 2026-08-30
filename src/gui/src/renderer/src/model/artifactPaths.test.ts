import { describe, expect, it } from "vitest";
import { artifactSubdir, directoryOf, fixOutDirOf } from "./artifactPaths";

describe("directoryOf", () => {
  it("keeps the separator the path already uses", () => {
    expect(directoryOf("C:\\data\\cobol-insight.db")).toBe("C:\\data\\");
    expect(directoryOf("/var/data/cobol-insight.db")).toBe("/var/data/");
  });

  it("names no folder for a bare file name", () => {
    expect(directoryOf("cobol-insight.db")).toBe("");
  });
});

describe("artifactSubdir", () => {
  it("puts the directory beside the project file", () => {
    expect(artifactSubdir("C:\\data\\cobol-insight.db", "fix")).toBe("C:\\data\\fix");
  });

  it("is empty while the project file's location is unknown", () => {
    expect(artifactSubdir(null, "fix")).toBe("");
    expect(artifactSubdir("", "fix")).toBe("");
  });
});

describe("fixOutDirOf", () => {
  it("takes the setting when it names a directory", () => {
    expect(fixOutDirOf("D:\\out", "C:\\data\\cobol-insight.db")).toBe("D:\\out");
  });

  it("trims the setting, so whitespace alone is not a directory", () => {
    expect(fixOutDirOf("  D:\\out  ", null)).toBe("D:\\out");
    expect(fixOutDirOf("   ", "C:\\data\\cobol-insight.db")).toBe("C:\\data\\fix");
  });

  it("falls back to the directory beside the project file", () => {
    expect(fixOutDirOf("", "C:\\data\\cobol-insight.db")).toBe("C:\\data\\fix");
  });
});
