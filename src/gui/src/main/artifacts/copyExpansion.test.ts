import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parseCopyExpansion } from "./copyExpansion";
import { fixturePath } from "./fixtures";

describe("parseCopyExpansion against the fixture", () => {
  const data = parseCopyExpansion(readFileSync(fixturePath("copy-expansion.json"), "utf-8"));

  it("reads every program that has a COPY statement", () => {
    expect(data.programs.length).toBeGreaterThan(0);
    expect(data.programs[0].path).toContain("cobol/");
  });

  it("keeps the copybook name, its path and the expanded lines of each COPY statement", () => {
    const expansion = data.programs[0].expansions[0];
    expect(expansion.copyStatementLine).toBeGreaterThan(0);
    expect(expansion.copybookName).not.toBe("");
    expect(expansion.copybookPath).toContain("copybook/");
    expect(expansion.lines.length).toBeGreaterThan(0);
    expect(expansion.lines[0].copybookLine).toBeGreaterThan(0);
  });
});

describe("parseCopyExpansion defensiveness", () => {
  it("returns no programs for an empty document", () => {
    expect(parseCopyExpansion("{}")).toEqual({ programs: [] });
  });

  it("fills in missing fields rather than dropping the entry", () => {
    const data = parseCopyExpansion('{"programs":[{"expansions":[{"lines":[{}]}]}]}');
    expect(data.programs[0]).toMatchObject({ path: "", programId: "" });
    expect(data.programs[0].expansions[0]).toMatchObject({ copyStatementLine: 0, copybookName: "" });
    expect(data.programs[0].expansions[0].lines[0]).toEqual({ copybookLine: 0, text: "" });
  });

  it("keeps blank lines, which hold the correspondence to the copybook's line numbers", () => {
    const data = parseCopyExpansion(
      '{"programs":[{"expansions":[{"lines":[{"copybookLine":1,"text":""},{"copybookLine":2,"text":"01 A."}]}]}]}',
    );
    expect(data.programs[0].expansions[0].lines).toHaveLength(2);
  });
});
