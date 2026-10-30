import { describe, expect, it } from "vitest";
import type { SaveResult } from "../../../shared/ipc";
import { isStale, saveOutcomeOf, showsReparseErrors } from "./save";
import { REPARSE_RULE_ID } from "./ruleIndex";
import { text } from "../i18n/text";

function result(overrides: Partial<SaveResult> = {}): SaveResult {
  return {
    written: true,
    path: "C:/assets/cobol/A.cbl",
    reparseErrors: [],
    error: "",
    exitCode: 0,
    ...overrides,
  };
}

describe("showsReparseErrors", () => {
  it("is true for a COBOL program", () => {
    expect(showsReparseErrors("PROGRAM")).toBe(true);
  });

  it("is false for other asset types", () => {
    // A copybook, JCL or BMS cannot be parsed on its own, so its reparse errors say nothing.
    expect(showsReparseErrors("COPYBOOK")).toBe(false);
    expect(showsReparseErrors("JCL")).toBe(false);
    expect(showsReparseErrors("")).toBe(false);
  });
});

describe("saveOutcomeOf", () => {
  it("reports a refused write as a failure", () => {
    const outcome = saveOutcomeOf(
      "cobol/A.cbl",
      "PROGRAM",
      result({ written: false, exitCode: 2, error: "Shift_JIS へ変換できない文字があります。" }),
    );
    expect(outcome.kind).toBe("failed");
  });

  it("names the engine's reason in the failure message", () => {
    const outcome = saveOutcomeOf(
      "cobol/A.cbl",
      "PROGRAM",
      result({ written: false, exitCode: 2, error: "Shift_JIS へ変換できない文字があります。" }),
    );
    expect(outcome.message).toContain("Shift_JIS へ変換できない文字");
  });

  it("reports a refused write with no reason as a complete sentence", () => {
    const outcome = saveOutcomeOf("cobol/A.cbl", "PROGRAM", result({ written: false, exitCode: 2 }));
    expect(outcome.message).toBe("「cobol/A.cbl」を保存できませんでした。");
  });

  it("reports saved with a warning when the reparse check failed after the write", () => {
    const outcome = saveOutcomeOf(
      "cobol/A.cbl",
      "PROGRAM",
      result({ written: true, exitCode: 2, error: "構文解析を完了できませんでした。" }),
    );
    expect(outcome).toEqual({
      kind: "saved",
      message: text.save.checkFailed("cobol/A.cbl", "構文解析を完了できませんでした。"),
      diagnostics: [],
    });
  });

  it("reports unchanged with no message when the file needed no write", () => {
    expect(saveOutcomeOf("cobol/A.cbl", "PROGRAM", result({ written: false, exitCode: 0 }))).toEqual({
      kind: "unchanged",
      message: null,
    });
  });

  it("treats a clean write as saved with nothing to report", () => {
    // A written file is silent: nothing is raised unless the verification found something.
    const outcome = saveOutcomeOf("cobol/A.cbl", "PROGRAM", result());
    expect(outcome).toEqual({ kind: "saved", message: null, diagnostics: [] });
  });

  it("turns a program's reparse errors into findings, the write standing", () => {
    const outcome = saveOutcomeOf(
      "cobol/A.cbl",
      "PROGRAM",
      result({ exitCode: 1, reparseErrors: [{ line: 12, message: "PERIOD がありません。" }] }),
    );
    expect(outcome.kind).toBe("saved");
    if (outcome.kind !== "saved") return;
    expect(outcome.diagnostics).toEqual([
      {
        ruleId: REPARSE_RULE_ID,
        level: "error",
        message: "PERIOD がありません。",
        file: "cobol/A.cbl",
        startLine: 12,
        startColumn: 1,
      },
    ]);
  });

  it("drops the reparse errors of a kind that cannot be parsed on its own", () => {
    const outcome = saveOutcomeOf(
      "copybook/A.cpy",
      "COPYBOOK",
      result({ exitCode: 1, reparseErrors: [{ line: 1, message: "..." }] }),
    );
    expect(outcome.kind).toBe("saved");
    if (outcome.kind !== "saved") return;
    expect(outcome.diagnostics).toEqual([]);
  });
});

describe("isStale", () => {
  const decoded = { mtimeMs: 1000, byteSize: 40 };

  it("is false while the file is as it was read", () => {
    expect(isStale(decoded, { mtimeMs: 1000, byteSize: 40 })).toBe(false);
  });

  it("is true when either the time or the size moved", () => {
    expect(isStale(decoded, { mtimeMs: 1001, byteSize: 40 })).toBe(true);
    expect(isStale(decoded, { mtimeMs: 1000, byteSize: 41 })).toBe(true);
  });

  it("is false with no current stamp, leaving the engine to report why", () => {
    expect(isStale(decoded, null)).toBe(false);
  });

  it("is false while the tab's own stamp is unknown after a failed stat", () => {
    expect(isStale(null, { mtimeMs: 1001, byteSize: 40 })).toBe(false);
  });
});
