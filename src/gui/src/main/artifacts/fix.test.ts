import { describe, it, expect } from "vitest";
import { buildFixDiff } from "./fix";

describe("buildFixDiff", () => {
  it("原本・修正後テキストを Monaco 用の対へ組む", () => {
    expect(buildFixDiff("cobol/A.cbl", "OLD\n", "NEW\n")).toEqual({
      relPath: "cobol/A.cbl",
      originalText: "OLD\n",
      fixedText: "NEW\n",
    });
  });
});
