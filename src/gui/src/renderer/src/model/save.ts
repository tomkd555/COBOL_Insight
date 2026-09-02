/**
 * What the write-back's result means (pure, independent of React).
 *
 * The engine's exit code is the whole contract: 0 written, 1 written but the reparse found problems,
 * 2 not written. Code 1 is not a failure — the write is deliberately not rolled back, so that an
 * edit that is still half-finished can be saved.
 *
 * The reparse check runs on every save, but copybooks, JCL and BMS cannot be parsed on their own and
 * therefore always report errors. Those errors are shown for COBOL programs only; for every other
 * kind, code 1 simply means "written".
 */

import type { SarifFinding, SaveResult, SourceStamp } from "../../../shared/ipc";
import { text } from "../i18n/text";

import { REPARSE_RULE_ID } from "./ruleIndex";

/** Whether reparse errors mean anything for this asset kind (NODE.type). */
export function showsReparseErrors(assetType: string): boolean {
  return assetType === "PROGRAM";
}

/**
 * The outcome of one write-back. A plain success carries no message: a written file is silent, as it
 * is in VS Code, and only the verification findings are worth raising.
 */
export type SaveOutcome =
  | {
      readonly kind: "saved";
      readonly message: string | null;
      /** The reparse errors, as findings. Always empty for a kind that cannot be parsed alone. */
      readonly diagnostics: readonly SarifFinding[];
    }
  | { readonly kind: "failed"; readonly message: string };

/**
 * Interprets one save result.
 *
 * @param path the asset's path relative to the asset folder. The engine echoes an absolute path,
 *   but the problems table and the editor both look assets up by the relative one.
 * @param assetType NODE.type, which decides whether reparse errors are shown.
 */
export function saveOutcomeOf(path: string, assetType: string, result: SaveResult): SaveOutcome {
  if (result.exitCode === 2 || !result.written) {
    return {
      kind: "failed",
      message: text.save.failed(path, result.error),
    };
  }
  if (!showsReparseErrors(assetType) || result.reparseErrors.length === 0) {
    return { kind: "saved", message: null, diagnostics: [] };
  }
  return {
    kind: "saved",
    message: text.save.savedWithErrors(path, result.reparseErrors.length),
    diagnostics: result.reparseErrors.map((error) => ({
      ruleId: REPARSE_RULE_ID,
      level: "error",
      message: error.message,
      file: path,
      startLine: error.line,
      startColumn: 1,
    })),
  };
}

/**
 * Whether the original changed under the editor since it was decoded.
 *
 * A missing current stamp is not treated as stale: the file is gone or unreadable, and the engine's
 * own save reports that far more precisely than a guess here would.
 */
export function isStale(decoded: SourceStamp, current: SourceStamp | null): boolean {
  if (current === null) {
    return false;
  }
  return current.mtimeMs !== decoded.mtimeMs || current.byteSize !== decoded.byteSize;
}
