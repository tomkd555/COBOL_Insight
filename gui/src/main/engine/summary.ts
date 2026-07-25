import type { EngineOutputs } from "../../shared/engine-api";

/**
 * engine CLI の stdout からサマリ JSON を取り出す。Che4z LSP が logback のステータス行を
 * stdout へ書くため、サマリ JSON は「末尾の、JSON オブジェクトとしてパースできる行」とする。
 * callgraph 無指定時はグラフ本体 JSON がこの位置に来る。--json 等でファイル出力した場合は
 * JSON 行が無く null を返す。
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
      // JSON でない行(logback 雑音等)は読み飛ばす。
    }
  }
  return null;
}

/**
 * サマリ JSON に含まれる成果物パス項目(sarifFile/htmlFile/textFile/outputDir)を
 * {@link EngineOutputs} へ写す。runner が明示指定の出力先とこれを併合し、実際に書かれた
 * ファイルの位置を確定する。
 */
export function summaryOutputs(summary: Record<string, unknown> | null): EngineOutputs {
  const outputs: EngineOutputs = {};
  if (summary === null) {
    return outputs;
  }
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
