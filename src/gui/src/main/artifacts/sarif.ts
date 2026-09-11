import type { RelatedLocation, SarifFinding } from "../../shared/ipc";

/**
 * Flattens SARIF 2.1.0 text (the --sarif output of lint and sql-lint) into the finding list the
 * screens read. Unknown structure is handled defensively: a missing region becomes line and column
 * 0, a missing level becomes "none".
 */
export function parseSarif(text: string): SarifFinding[] {
  const doc: unknown = JSON.parse(text);
  const findings: SarifFinding[] = [];
  for (const run of asArray(prop(doc, "runs"))) {
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
    related: relatedOf(result),
  };
  return finding;
}

/** Every codeFlow step, in order. A step without a file or line is dropped. */
function relatedOf(result: unknown): RelatedLocation[] {
  const related: RelatedLocation[] = [];
  for (const flow of asArray(prop(result, "codeFlows"))) {
    for (const thread of asArray(prop(flow, "threadFlows"))) {
      for (const step of asArray(prop(thread, "locations"))) {
        const location = prop(step, "location");
        const physical = prop(location, "physicalLocation");
        const file = asString(prop(prop(physical, "artifactLocation"), "uri"));
        const line = asNumber(prop(prop(physical, "region"), "startLine"));
        if (file === undefined || line === undefined) continue;
        related.push({
          file: decodeUri(file),
          line,
          label: asString(prop(prop(location, "message"), "text")) ?? "",
        });
      }
    }
  }
  return related;
}

/**
 * Turns artifactLocation.uri back into a relative path. The SARIF writer percent-encodes non-pchar
 * bytes as UTF-8, so assets with Japanese names arrive as %E3%.. sequences, and both the display and
 * the jump to source need the decoded path. A uri with a malformed escape is returned unchanged.
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
