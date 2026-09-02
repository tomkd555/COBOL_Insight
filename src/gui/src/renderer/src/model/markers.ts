/**
 * Editor markers (pure, independent of React and of Monaco).
 *
 * Three owners write markers into one editor and each is set independently, so clearing one never
 * clears another: `sarif` carries the lint and sql-lint findings, `save` the reparse errors the
 * write-back reported, and `format` the lines whose bytes run past the record.
 *
 * Severity is taken from the rule catalog rather than from the SARIF level, for the same reason the
 * problems table does: level has three values and says nothing rule-specific.
 */

import type { SarifFinding, SaveReparseError } from "../../../shared/ipc";
import { REPARSE_RULE_ID, ruleOf, type RuleIndex } from "./ruleIndex";
import type { Severity } from "./severity";
import { byteLengthOf, OVERFLOW_COLUMN, type EditorCodepage } from "./columns";

/** Who owns a set of markers. Setting one owner's markers leaves the others in place. */
export const MARKER_OWNER = {
  sarif: "sarif",
  save: "save",
  format: "format",
} as const;

/**
 * Monaco's MarkerSeverity values. They are repeated here rather than imported so this module stays
 * loadable without Monaco, which needs a worker and real layout that a unit test does not have.
 */
export const MARKER_SEVERITY = { hint: 1, info: 2, warning: 4, error: 8 } as const;

/** A column past the end of any line. Monaco clamps it to the line's real end. */
const LINE_END_COLUMN = 10_000;

/** What `monaco.editor.setModelMarkers` needs, as plain data. */
export interface EditorMarker {
  readonly startLineNumber: number;
  readonly startColumn: number;
  readonly endLineNumber: number;
  readonly endColumn: number;
  readonly message: string;
  readonly severity: number;
  /** The rule id. The quick-fix provider reads it back to decide whether a fix exists. */
  readonly code: string;
  readonly source: string;
}

/** Maps the interface's four severities onto Monaco's marker severities. */
export function markerSeverityOf(severity: Severity): number {
  switch (severity) {
    case "high":
      return MARKER_SEVERITY.error;
    case "medium":
      return MARKER_SEVERITY.warning;
    case "low":
      return MARKER_SEVERITY.info;
    default:
      return MARKER_SEVERITY.hint;
  }
}

/** The glyph-margin class for a severity, so the margin shows how bad the worst finding is. */
export function glyphClassOf(severity: Severity): string {
  return `ci-code__glyph ci-code__glyph--${severity}`;
}

/**
 * The findings for one file, as markers. A finding whose rule the catalog does not know still
 * becomes a marker: the fallback entry in the rule index gives it a name and a severity.
 */
export function findingMarkers(
  findings: readonly SarifFinding[],
  path: string,
  index: RuleIndex,
): EditorMarker[] {
  return findings
    .filter((finding) => finding.file === path)
    .map((finding) => {
      const rule = ruleOf(index, finding.ruleId);
      return {
        startLineNumber: Math.max(1, finding.startLine),
        startColumn: Math.max(1, finding.startColumn),
        endLineNumber: Math.max(1, finding.startLine),
        endColumn: LINE_END_COLUMN,
        message: `${finding.ruleId} ${rule.name}: ${finding.message}`,
        severity: markerSeverityOf(rule.severity),
        code: finding.ruleId,
        source: MARKER_OWNER.sarif,
      };
    });
}

/** The reparse errors of the last write-back, as markers over the whole offending line. */
export function reparseMarkers(errors: readonly SaveReparseError[]): EditorMarker[] {
  return errors.map((error) => ({
    startLineNumber: Math.max(1, error.line),
    startColumn: 1,
    endLineNumber: Math.max(1, error.line),
    endColumn: LINE_END_COLUMN,
    message: error.message,
    severity: MARKER_SEVERITY.error,
    code: REPARSE_RULE_ID,
    source: MARKER_OWNER.save,
  }));
}

/**
 * Lines whose bytes run past the 80-column record. The engine refuses nothing here — a long line is
 * still saved — but the fixed format gives those bytes no meaning, so the editor says so while the
 * edit is being made rather than after it has been written back.
 */
export function overflowMarkers(
  lines: readonly string[],
  codepage: EditorCodepage,
  message: (bytes: number) => string,
): EditorMarker[] {
  const markers: EditorMarker[] = [];
  lines.forEach((line, index) => {
    const bytes = byteLengthOf(line, codepage);
    if (bytes < OVERFLOW_COLUMN) {
      return;
    }
    markers.push({
      startLineNumber: index + 1,
      startColumn: 1,
      endLineNumber: index + 1,
      endColumn: LINE_END_COLUMN,
      message: message(bytes),
      severity: MARKER_SEVERITY.warning,
      code: "format",
      source: MARKER_OWNER.format,
    });
  });
  return markers;
}
