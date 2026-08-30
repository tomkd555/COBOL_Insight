import { describe, expect, it } from "vitest";
import { extractSummaryJson, summaryOutputs } from "./summary";

describe("extractSummaryJson", () => {
  it("takes the last JSON object line, ignoring log noise around it", () => {
    const stdout = [
      "12:00:00 INFO starting",
      '{"stale":true}',
      "|-INFO in ch.qos.logback ...",
      '{"assets":5,"findings":2}',
      "",
    ].join("\n");
    expect(extractSummaryJson(stdout)).toEqual({ assets: 5, findings: 2 });
  });

  it("returns null when no line parses as a JSON object", () => {
    expect(extractSummaryJson("done\n")).toBeNull();
    expect(extractSummaryJson("")).toBeNull();
  });

  it("skips a JSON array, since the summary is always an object", () => {
    expect(extractSummaryJson('{"a":1}\n[1,2,3]\n')).toEqual({ a: 1 });
  });

  it("tolerates CRLF line endings", () => {
    expect(extractSummaryJson('noise\r\n{"a":1}\r\n')).toEqual({ a: 1 });
  });
});

describe("summaryOutputs", () => {
  it("copies the artefact paths the summary reports", () => {
    expect(
      summaryOutputs({
        dbFile: "C:/p.db",
        sarifFile: "C:/o.sarif",
        htmlFile: "C:/r.html",
        textFile: "C:/r.txt",
        outputDir: "C:/gen",
      }),
    ).toEqual({
      db: "C:/p.db",
      sarif: "C:/o.sarif",
      html: "C:/r.html",
      text: "C:/r.txt",
      outDir: "C:/gen",
    });
  });

  it("ignores non-string values and a missing summary", () => {
    expect(summaryOutputs({ dbFile: 7, sarifFile: null })).toEqual({});
    expect(summaryOutputs(null)).toEqual({});
  });
});
