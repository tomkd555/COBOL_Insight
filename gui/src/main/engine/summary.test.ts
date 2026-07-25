import { describe, it, expect } from "vitest";
import { extractSummaryJson, summaryOutputs } from "./summary";

// Che4z LSP は logback のステータス行を stdout へ出すため、サマリ JSON は「末尾の JSON 行」を拾う。
const LOGBACK =
  "10:10:41,319 |-INFO in ch.qos.logback.classic.LoggerContext[default] - This is logback-classic version 1.3.16\n" +
  "10:10:41,322 |-INFO in ch.qos.logback.classic.util.ContextInitializer@1e683a3e - Here is a list, by rank:";

describe("extractSummaryJson", () => {
  it("logback 雑音の後の末尾 JSON 行を拾う(scan)", () => {
    const stdout = `${LOGBACK}\n\n{"analyzed":["cobol/A.cbl"],"skipped":[],"removed":[],"findingCount":0,"exitCode":0}\n`;
    const summary = extractSummaryJson(stdout);
    expect(summary).not.toBeNull();
    expect(summary?.["analyzed"]).toEqual(["cobol/A.cbl"]);
    expect(summary?.["exitCode"]).toBe(0);
  });

  it("callgraph 無指定時のグラフ本体 JSON を拾う", () => {
    const stdout = `${LOGBACK}\n{"nodes":[{"id":"program:A","kind":"PROGRAM","label":"A"}],"edges":[]}`;
    const summary = extractSummaryJson(stdout);
    expect(Array.isArray(summary?.["nodes"])).toBe(true);
    expect(summary?.["edges"]).toEqual([]);
  });

  it("fix preview の diff 行の後の末尾サマリ JSON を拾う", () => {
    const stdout =
      `${LOGBACK}\n` +
      "--- a/cobol/A.cbl\n+++ b/cobol/A.cbl\n-  OLD LINE\n+  NEW LINE\n" +
      '{"fixedFiles":["cobol/A.cbl"],"copybookFixes":[],"fixCount":1,"analysisErrors":0}';
    const summary = extractSummaryJson(stdout);
    expect(summary?.["fixedFiles"]).toEqual(["cobol/A.cbl"]);
    expect(summary?.["fixCount"]).toBe(1);
  });

  it("JSON 行が無い(callgraph が --json でファイル出力)なら null", () => {
    expect(extractSummaryJson(LOGBACK)).toBeNull();
  });

  it("空文字列なら null", () => {
    expect(extractSummaryJson("")).toBeNull();
  });

  it("末尾に空行があってもオブジェクト行を拾う", () => {
    const stdout = '{"exitCode":2}\n\n   \n';
    expect(extractSummaryJson(stdout)).toEqual({ exitCode: 2 });
  });
});

describe("summaryOutputs", () => {
  it("lint サマリの sarifFile を outputs.sarif へ写す", () => {
    expect(summaryOutputs({ sarifFile: "out/lint.sarif", exitCode: 2 })).toEqual({
      sarif: "out/lint.sarif",
    });
  });

  it("report サマリの htmlFile・textFile を写す", () => {
    expect(
      summaryOutputs({ htmlFile: "out/r.html", textFile: "out/r.txt", exitCode: 0 }),
    ).toEqual({ html: "out/r.html", text: "out/r.txt" });
  });

  it("fix apply サマリの outputDir を outDir へ写す", () => {
    expect(summaryOutputs({ outputDir: "out/fix", writtenFiles: [] })).toEqual({
      outDir: "out/fix",
    });
  });

  it("null サマリなら空", () => {
    expect(summaryOutputs(null)).toEqual({});
  });
});
