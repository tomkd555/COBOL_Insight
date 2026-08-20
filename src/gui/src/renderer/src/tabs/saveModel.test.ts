import { describe, it, expect } from "vitest";
import type { SaveResult } from "../../../shared/engine-api";
import { REPARSE_RULE_ID, reparseMarkers, saveOutcomeOf, showsReparseErrors } from "./saveModel";
import { MARKER_SEVERITY_ERROR } from "../screens/viewer/viewerModel";

/** engine の save が返す要約。既定は「書き戻し済み・誤りなし」である。 */
function result(overrides: Partial<SaveResult> = {}): SaveResult {
  return {
    written: true,
    path: "C:/資産/cobol/SYK001.cbl",
    changedLineFrom: 10,
    changedLineTo: 10,
    reparseErrors: [],
    error: "",
    exitCode: 0,
    ...overrides,
  };
}

describe("再パース検証を示す資産", () => {
  it("COBOL 本体だけに示す", () => {
    expect(showsReparseErrors("PROGRAM")).toBe(true);
    expect(showsReparseErrors("COPYBOOK")).toBe(false);
    expect(showsReparseErrors("JCL")).toBe(false);
    expect(showsReparseErrors("BMS")).toBe(false);
    expect(showsReparseErrors("UNKNOWN")).toBe(false);
  });
});

describe("書き戻しの結末", () => {
  it("誤りが無ければ保存済みとして伝える", () => {
    const outcome = saveOutcomeOf("cobol/SYK001.cbl", "PROGRAM", result());
    expect(outcome.kind).toBe("saved");
    expect(outcome.message).toBe("cobol/SYK001.cbl を保存しました。");
  });

  it("COBOL 本体の再パースの誤りを、資産の相対パスの指摘へ写す", () => {
    const outcome = saveOutcomeOf(
      "cobol/SYK001.cbl",
      "PROGRAM",
      result({
        exitCode: 1,
        reparseErrors: [
          { line: 42, message: "PERIOD が足りない。" },
          { line: 51, message: "END-IF が無い。" },
        ],
      }),
    );
    if (outcome.kind !== "saved") throw new Error("保存済みとして扱う");
    expect(outcome.diagnostics).toEqual([
      {
        ruleId: REPARSE_RULE_ID,
        level: "error",
        message: "PERIOD が足りない。",
        file: "cobol/SYK001.cbl",
        startLine: 42,
        startColumn: 1,
      },
      {
        ruleId: REPARSE_RULE_ID,
        level: "error",
        message: "END-IF が無い。",
        file: "cobol/SYK001.cbl",
        startLine: 51,
        startColumn: 1,
      },
    ]);
    expect(outcome.message).toContain("2 件");
  });

  it("コピー句は単体で構文解析できないため、終了コード 1 でも誤りを示さない", () => {
    const outcome = saveOutcomeOf(
      "copybook/SYKCPY1.cpy",
      "COPYBOOK",
      result({ exitCode: 1, reparseErrors: [{ line: 1, message: "DIVISION が無い。" }] }),
    );
    if (outcome.kind !== "saved") throw new Error("保存済みとして扱う");
    expect(outcome.diagnostics).toEqual([]);
    expect(outcome.message).toBe("copybook/SYKCPY1.cpy を保存しました。");
  });

  it("終了コード 2 は書き戻していない。理由と次の手を示す", () => {
    const outcome = saveOutcomeOf(
      "cobol/SYK001.cbl",
      "PROGRAM",
      result({ exitCode: 2, written: false, error: "識別欄を書き換えています。" }),
    );
    expect(outcome.kind).toBe("failed");
    expect(outcome.message).toContain("識別欄を書き換えています。");
    expect(outcome.message).toContain("もう一度保存してください");
  });

  it("理由が返らなくても、書き戻していないことは伝える", () => {
    const outcome = saveOutcomeOf("cobol/SYK001.cbl", "PROGRAM", result({ exitCode: 2, error: "" }));
    expect(outcome.kind).toBe("failed");
    expect(outcome.message).toContain("解析エンジンが理由を返しませんでした");
  });
});

describe("誤りのマーカー", () => {
  it("誤りのある行全体を誤りの重大度で指す", () => {
    const markers = reparseMarkers([
      {
        ruleId: REPARSE_RULE_ID,
        level: "error",
        message: "PERIOD が足りない。",
        file: "cobol/SYK001.cbl",
        startLine: 42,
        startColumn: 1,
      },
    ]);
    expect(markers).toHaveLength(1);
    expect(markers[0].startLineNumber).toBe(42);
    expect(markers[0].endLineNumber).toBe(42);
    expect(markers[0].severity).toBe(MARKER_SEVERITY_ERROR);
    expect(markers[0].message).toBe("PERIOD が足りない。");
  });
});
