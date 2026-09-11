import { describe, expect, it } from "vitest";
import type { SaveResult } from "../../../shared/ipc";
import { isStale, saveOutcomeOf, showsReparseErrors } from "./save";
import { REPARSE_RULE_ID } from "./ruleIndex";

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
  it("holds for a COBOL program and for nothing else", () => {
    expect(showsReparseErrors("PROGRAM")).toBe(true);
    // A copybook, JCL or BMS cannot be parsed on its own, so its reparse errors say nothing.
    expect(showsReparseErrors("COPYBOOK")).toBe(false);
    expect(showsReparseErrors("JCL")).toBe(false);
    expect(showsReparseErrors("")).toBe(false);
  });
});

describe("saveOutcomeOf", () => {
  it("reports a refused write as a failure and names the engine's reason", () => {
    const outcome = saveOutcomeOf(
      "cobol/A.cbl",
      "PROGRAM",
      result({ written: false, exitCode: 2, error: "Shift_JIS へ変換できない文字があります。" }),
    );
    expect(outcome.kind).toBe("failed");
    expect(outcome.message).toContain("Shift_JIS へ変換できない文字");
  });

  it("reports a refused write with no reason as a complete sentence", () => {
    const outcome = saveOutcomeOf("cobol/A.cbl", "PROGRAM", result({ written: false, exitCode: 2 }));
    expect(outcome.message).toBe("「cobol/A.cbl」を保存できませんでした。");
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
});
