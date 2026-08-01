import type { SarifFinding } from "../../shared/engine-api";

/**
 * SARIF 2.1.0 テキストを、画面が要する平坦な検出結果一覧へ変換する純関数。lint・sql-lint の
 * --sarif 出力を入力とする。未知構造には防御的に当たり、region 欠落は行・列を 0、level 欠落は
 * none で補う。
 */
export function parseSarif(text: string): SarifFinding[] {
  const doc: unknown = JSON.parse(text);
  const findings: SarifFinding[] = [];
  const runs = asArray(prop(doc, "runs"));
  for (const run of runs) {
    for (const result of asArray(prop(run, "results"))) {
      findings.push(toFinding(result));
    }
  }
  return findings;
}

function toFinding(result: unknown): SarifFinding {
  const location = asArray(prop(result, "locations"))[0];
  const physical = prop(location, "physicalLocation");
  const region = prop(physical, "region");
  const finding: SarifFinding = {
    ruleId: asString(prop(result, "ruleId")) ?? "",
    level: asString(prop(result, "level")) ?? "none",
    message: asString(prop(prop(result, "message"), "text")) ?? "",
    file: decodeUri(asString(prop(prop(physical, "artifactLocation"), "uri")) ?? ""),
    startLine: asNumber(prop(region, "startLine")) ?? 0,
    startColumn: asNumber(prop(region, "startColumn")) ?? 0,
  };
  const ruleIndex = asNumber(prop(result, "ruleIndex"));
  if (ruleIndex !== undefined) {
    finding.ruleIndex = ruleIndex;
  }
  return finding;
}

/**
 * artifactLocation.uri を相対パスへ戻す。SarifWriter が非 pchar バイトを UTF-8 パーセント
 * エンコードするため、日本語名の資産は %E3%.. の形で届く。表示とソースジャンプは復号後の
 * パスを要する。パーセント列が不正な uri は復号せずそのまま返す。
 */
function decodeUri(uri: string): string {
  if (!uri.includes("%")) {
    return uri;
  }
  try {
    return decodeURIComponent(uri);
  } catch {
    return uri;
  }
}

function prop(value: unknown, key: string): unknown {
  return value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}
