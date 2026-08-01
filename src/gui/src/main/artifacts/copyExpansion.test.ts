import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseCopyExpansion } from "./copyExpansion";

const fixture = readFileSync(join(__dirname, "..", "__fixtures__", "copy-expansion.json"), "utf-8");

describe("parseCopyExpansion", () => {
  it("プログラムごとの COPY 展開を取り出す", () => {
    const data = parseCopyExpansion(fixture);
    expect(data.programs.map((program) => program.path)).toEqual([
      "cobol/SYK001.cbl",
      "cobol/SYK002.cbl",
    ]);
    expect(data.programs[0].programId).toBe("SYK001");
    expect(data.programs[0].expansions.map((expansion) => expansion.copyStatementLine)).toEqual([
      32, 48,
    ]);
  });

  it("展開1件はコピー句名・パスと、コピー句の行番号付きの本文を持つ", () => {
    const expansion = parseCopyExpansion(fixture).programs[0].expansions[0];
    expect(expansion.copybookName).toBe("SYKCPY1");
    expect(expansion.copybookPath).toBe("copybook/SYKCPY1.cpy");
    expect(expansion.lines[2]).toEqual({
      copybookLine: 7,
      text: "       01  ORD1-受注レコード.",
    });
  });

  it("前処理で空になった注記行を、空文字の行としてそのまま保つ", () => {
    const expansion = parseCopyExpansion(fixture).programs[0].expansions[0];
    expect(expansion.lines.slice(0, 2)).toEqual([
      { copybookLine: 1, text: "" },
      { copybookLine: 2, text: "" },
    ]);
  });

  it("programs 欠落でも空配列を返す", () => {
    expect(parseCopyExpansion("{}")).toEqual({ programs: [] });
  });

  it("項目が欠けた要素は既定値で補う", () => {
    const doc = JSON.stringify({
      programs: [{ expansions: [{ copyStatementLine: 3, lines: [{ text: "  MOVE A TO B." }] }] }],
    });
    expect(parseCopyExpansion(doc)).toEqual({
      programs: [
        {
          path: "",
          programId: "",
          expansions: [
            {
              copyStatementLine: 3,
              copybookName: "",
              copybookPath: "",
              lines: [{ copybookLine: 0, text: "  MOVE A TO B." }],
            },
          ],
        },
      ],
    });
  });

  it("JSON として読めない本文は例外にする", () => {
    expect(() => parseCopyExpansion("壊れている")).toThrow();
  });
});
