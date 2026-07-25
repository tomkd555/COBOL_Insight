import { describe, it, expect } from "vitest";
import { toSourcePreview } from "./sourcePreview";
import type { SourceTextResult } from "../../../../shared/engine-api";

const result = (over: Partial<SourceTextResult>): SourceTextResult => ({
  text: "",
  codepage: "UTF-8",
  truncated: false,
  unsupported: false,
  ...over,
});

describe("toSourcePreview(readSourceText の結果 → プレビュー状態)", () => {
  it("本文を行へ分けて保持する", () => {
    const preview = toSourcePreview(result({ text: "001000 IDENTIFICATION DIVISION.\n001100 PROGRAM-ID." }));
    expect(preview).toEqual({
      status: "ready",
      lines: ["001000 IDENTIFICATION DIVISION.", "001100 PROGRAM-ID."],
      codepage: "UTF-8",
    });
  });

  it("復号非対応はコードページ名を添えて非対応として示す", () => {
    expect(toSourcePreview(result({ unsupported: true, codepage: "CP930" }))).toEqual({
      status: "unsupported",
      codepage: "CP930",
    });
  });

  it("空の本文は行なしの ready とする(非対応と区別する)", () => {
    expect(toSourcePreview(result({ text: "" }))).toEqual({
      status: "ready",
      lines: [],
      codepage: "UTF-8",
    });
  });

  it("末尾の空行は落とす", () => {
    const preview = toSourcePreview(result({ text: "A\nB\n" }));
    expect(preview).toEqual({ status: "ready", lines: ["A", "B"], codepage: "UTF-8" });
  });
});
