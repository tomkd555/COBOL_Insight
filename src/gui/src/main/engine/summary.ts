import type { EngineOutputs } from "../../shared/ipc";

/**
 * Extracts the summary JSON from the engine's stdout.
 *
 * The Che4z LSP writes logback status lines to stdout, so the summary is defined as the last line
 * that parses as a JSON object. With `call-graph` and no output file, the graph JSON itself occupies
 * that position. When the result went to a file instead, there is no JSON line and this returns null.
 */
export function extractSummaryJson(stdout: string): Record<string, unknown> | null {
  const lines = stdout.split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line.startsWith("{")) {
      continue;
    }
    try {
      const parsed: unknown = JSON.parse(line);
      if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      // Not JSON (logback noise and the like): keep looking further up.
    }
  }
  return null;
}

/**
 * Copies the artefact paths the summary reports (dbFile/sarifFile/htmlFile/textFile/outputDir) into
 * {@link EngineOutputs}. The runner merges this with the explicitly requested destinations to settle
 * where the files actually landed.
 */
export function summaryOutputs(summary: Record<string, unknown> | null): EngineOutputs {
  const outputs: EngineOutputs = {};
  if (summary === null) {
    return outputs;
  }
  assignString(outputs, "db", summary["dbFile"]);
  assignString(outputs, "sarif", summary["sarifFile"]);
  assignString(outputs, "html", summary["htmlFile"]);
  assignString(outputs, "text", summary["textFile"]);
  assignString(outputs, "outDir", summary["outputDir"]);
  return outputs;
}

function assignString(outputs: EngineOutputs, key: keyof EngineOutputs, value: unknown): void {
  if (typeof value === "string") {
    outputs[key] = value;
  }
}
