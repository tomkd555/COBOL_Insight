import { describe, expect, it } from "vitest";
import type { RuleCatalogEntry, SarifFinding } from "../../../shared/ipc";
import { buildRuleIndex } from "./ruleIndex";
import {
  MARKER_OWNER,
  MARKER_SEVERITY,
  REPARSE_RULE_ID,
  findingMarkers,
  glyphClassOf,
  markerSeverityOf,
  overflowMarkers,
  reparseMarkers,
} from "./markers";

function rule(id: string, severity: string): RuleCatalogEntry {
  return {
    id,
    name: `${id} の名前`,
    category: "",
    severity,
    phase: "SYNTAX",
    hasFix: false,
    source: "builtin",
    enabled: true,
    defaultEnabled: true,
    commands: ["lint"],
    targets: ["COBOL"],
    needs: [],
    summary: "",
    rationale: "",
    detection: "",
    remedy: "",
    badExample: "",
    goodExample: "",
  };
}

const index = buildRuleIndex([rule("R001", "HIGH"), rule("R004", "MEDIUM"), rule("S001", "LOW")]);

function finding(ruleId: string, file: string, line: number): SarifFinding {
  return { ruleId, level: "warning", message: `${ruleId} の内容`, file, startLine: line, startColumn: 12 };
}

describe("markerSeverityOf", () => {
  it("maps the four interface severities onto Monaco's", () => {
    expect(markerSeverityOf("high")).toBe(MARKER_SEVERITY.error);
    expect(markerSeverityOf("medium")).toBe(MARKER_SEVERITY.warning);
    expect(markerSeverityOf("low")).toBe(MARKER_SEVERITY.info);
    expect(markerSeverityOf("warning")).toBe(MARKER_SEVERITY.hint);
  });
});

describe("glyphClassOf", () => {
  it("names one class per severity", () => {
    expect(glyphClassOf("high")).toBe("ci-code__glyph ci-code__glyph--high");
  });
});

describe("findingMarkers", () => {
  const findings = [
    finding("R001", "cobol/A.cbl", 24),
    finding("R004", "cobol/B.cbl", 10),
    finding("S001", "cobol/A.cbl", 30),
  ];

  it("keeps only the findings of the file in view", () => {
    const markers = findingMarkers(findings, "cobol/A.cbl", index);
    expect(markers.map((marker) => marker.code)).toEqual(["R001", "S001"]);
  });

  it("takes the severity from the rule rather than from the SARIF level", () => {
    // Every finding above carries level "warning"; the rules grade them HIGH and LOW.
    const [high, low] = findingMarkers(findings, "cobol/A.cbl", index);
    expect(high.severity).toBe(MARKER_SEVERITY.error);
    expect(low.severity).toBe(MARKER_SEVERITY.info);
  });

  it("names the rule in the message and files the marker under the sarif owner", () => {
    const [marker] = findingMarkers(findings, "cobol/A.cbl", index);
    expect(marker.message).toBe("R001 R001 の名前: R001 の内容");
    expect(marker.source).toBe(MARKER_OWNER.sarif);
    expect(marker.startLineNumber).toBe(24);
    expect(marker.startColumn).toBe(12);
  });

  it("still marks a finding whose rule the catalog does not know", () => {
    const markers = findingMarkers([finding("R999", "a.cbl", 1)], "a.cbl", index);
    expect(markers).toHaveLength(1);
    expect(markers[0].severity).toBe(MARKER_SEVERITY.warning);
  });
});

describe("reparseMarkers", () => {
  it("covers the whole offending line and files it under the save owner", () => {
    const [marker] = reparseMarkers([{ line: 7, message: "構文解析に失敗しました。" }]);
    expect(marker.startLineNumber).toBe(7);
    expect(marker.startColumn).toBe(1);
    expect(marker.endColumn).toBeGreaterThan(80);
    expect(marker.severity).toBe(MARKER_SEVERITY.error);
    expect(marker.source).toBe(MARKER_OWNER.save);
    expect(marker.code).toBe(REPARSE_RULE_ID);
  });
});

describe("overflowMarkers", () => {
  const message = (bytes: number): string => `${bytes} バイト`;

  it("marks only the lines whose bytes run past the 80-column record", () => {
    const lines = ["a".repeat(80), "a".repeat(81), "受".repeat(40)];
    const markers = overflowMarkers(lines, "Shift_JIS", message);
    // The third line is 80 bytes in Shift_JIS, so only the second overflows.
    expect(markers.map((marker) => marker.startLineNumber)).toEqual([2]);
    expect(markers[0].message).toBe("81 バイト");
    expect(markers[0].source).toBe(MARKER_OWNER.format);
  });

  it("counts the bytes of the codepage in force, not the characters", () => {
    // 40 double-byte characters are 80 bytes in Shift_JIS but 120 in UTF-8.
    expect(overflowMarkers(["受".repeat(40)], "UTF-8", message)).toHaveLength(1);
  });
});
