import { describe, it, expect } from "vitest";
import { parseFixSummary } from "./fixSummary";

describe("parseFixSummary", () => {
  it("fix preview サマリの fixedFiles と copybookFixes を正規化する", () => {
    const info = parseFixSummary({
      fixedFiles: ["cobol/SYK001.cbl", "cobol/SYK002.cbl"],
      copybookFixes: [{ copybook: "copybook/SYKCPY1.cpy", importers: ["SYK001", "SYK006"] }],
      fixCount: 8,
      analysisErrors: 0,
    });
    expect(info.files).toEqual(["cobol/SYK001.cbl", "cobol/SYK002.cbl"]);
    expect(info.copybookFixes).toEqual([
      { copybook: "copybook/SYKCPY1.cpy", importers: ["SYK001", "SYK006"] },
    ]);
    expect(info.fixCount).toBe(8);
    expect(info.reparseFailures).toBeUndefined();
  });

  it("fix apply サマリの writtenFiles と reparseFailures を拾う", () => {
    const info = parseFixSummary({
      outputDir: "out/fix",
      writtenFiles: ["cobol/SYK001.cbl"],
      copybookFixes: [],
      fixCount: 5,
      analysisErrors: 0,
      reparseFailures: 1,
      exitCode: 2,
    });
    expect(info.files).toEqual(["cobol/SYK001.cbl"]);
    expect(info.reparseFailures).toBe(1);
  });

  it("null サマリは空の情報を返す", () => {
    expect(parseFixSummary(null)).toEqual({
      files: [],
      copybookFixes: [],
      fixCount: 0,
      analysisErrors: 0,
    });
  });

  it("型の合わない値は落とす", () => {
    const summary = parseFixSummary({ fixedFiles: ["a.cbl", 3, null], fixCount: "4" });
    expect(summary.files).toEqual(["a.cbl"]);
    expect(summary.fixCount).toBe(0);
  });
});
