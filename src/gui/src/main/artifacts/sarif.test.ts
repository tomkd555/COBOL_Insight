import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parseSarif } from "./sarif";
import { fixturePath } from "./fixtures";

describe("parseSarif against the lint fixture", () => {
  const findings = parseSarif(readFileSync(fixturePath("lint.sarif"), "utf-8"));

  it("flattens every result of every run", () => {
    expect(findings.length).toBeGreaterThan(0);
  });

  it("carries the rule id, level, message and position of each finding", () => {
    const first = findings[0];
    expect(first.ruleId).toMatch(/^[RSU]\d+$/);
    expect(first.level).not.toBe("");
    expect(first.message).not.toBe("");
    expect(first.file).toContain("cobol/");
    expect(first.startLine).toBeGreaterThan(0);
  });
});

describe("parseSarif defensiveness", () => {
  it("returns nothing for a document with no runs", () => {
    expect(parseSarif('{"version":"2.1.0"}')).toEqual([]);
  });

  it("fills in a missing region and level rather than dropping the finding", () => {
    const findings = parseSarif(
      JSON.stringify({
        runs: [{ results: [{ ruleId: "R001", message: { text: "m" } }] }],
      }),
    );
    expect(findings).toEqual([
      {
        ruleId: "R001",
        level: "none",
        message: "m",
        file: "",
        startLine: 0,
        startColumn: 0,
        related: [],
      },
    ]);
  });

  it("flattens codeFlows into related locations, decoding the uri", () => {
    const step = (uri: string, line: number, text: string): unknown => ({
      location: {
        physicalLocation: {
          artifactLocation: { uri },
          region: { startLine: line },
        },
        message: { text },
      },
    });
    const findings = parseSarif(
      JSON.stringify({
        runs: [
          {
            results: [
              {
                ruleId: "R017",
                message: { text: "m" },
                codeFlows: [
                  {
                    threadFlows: [
                      {
                        locations: [
                          step("cobol/%E5%9C%A8%E5%BA%AB.cbl", 85, "READ"),
                          step("cobol/a.cbl", 120, "next"),
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      }),
    );
    expect(findings[0].related).toEqual([
      { file: "cobol/在庫.cbl", line: 85, label: "READ" },
      { file: "cobol/a.cbl", line: 120, label: "next" },
    ]);
  });

  it("decodes a percent-encoded uri, so assets with Japanese names resolve", () => {
    const uri = encodeURI("cobol/受注.cbl").replace(
      /受注/,
      encodeURIComponent("受注"),
    );
    const findings = parseSarif(
      JSON.stringify({
        runs: [
          {
            results: [
              {
                ruleId: "R001",
                message: { text: "m" },
                locations: [
                  { physicalLocation: { artifactLocation: { uri } } },
                ],
              },
            ],
          },
        ],
      }),
    );
    expect(findings[0].file).toBe("cobol/受注.cbl");
  });

  it("leaves a uri with a malformed escape untouched", () => {
    const findings = parseSarif(
      JSON.stringify({
        runs: [
          {
            results: [
              {
                ruleId: "R001",
                message: { text: "m" },
                locations: [
                  {
                    physicalLocation: {
                      artifactLocation: { uri: "a%ZZb.cbl" },
                    },
                  },
                ],
              },
            ],
          },
        ],
      }),
    );
    expect(findings[0].file).toBe("a%ZZb.cbl");
  });

  it("keeps ruleIndex only when the document supplies one", () => {
    const withIndex = parseSarif(
      JSON.stringify({
        runs: [{ results: [{ ruleId: "R001", ruleIndex: 3 }] }],
      }),
    );
    expect(withIndex[0].ruleIndex).toBe(3);
    const without = parseSarif(
      JSON.stringify({ runs: [{ results: [{ ruleId: "R001" }] }] }),
    );
    expect(without[0].ruleIndex).toBeUndefined();
  });
});
