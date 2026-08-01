import { describe, it, expect } from "vitest";
import { adviceAt, extractSqlStatement } from "./sqlDetailModel";
import { SAMPLE_SQL_FINDINGS } from "./fixtures";

/** 3 行目から 5 行目に EXEC SQL 文を持つ固定形式ソースを模した本文。 */
const SOURCE = [
  "000100 PROCEDURE DIVISION.",
  "000200     MOVE 1 TO WS-KEY.",
  "000300     EXEC SQL",
  "000400          SELECT * FROM SYKDB.ZAIKOM",
  "000500     END-EXEC.",
  "000600     IF SQLCODE NOT = 0",
].join("\r\n");

describe("extractSqlStatement(SQL 本文の切り出し)", () => {
  it("開始行から END-EXEC の行までを返す", () => {
    expect(extractSqlStatement(SOURCE, 3)).toEqual({
      lines: [
        "000300     EXEC SQL",
        "000400          SELECT * FROM SYKDB.ZAIKOM",
        "000500     END-EXEC.",
      ],
      unterminated: false,
    });
  });

  it("CRLF・LF・CR のいずれの改行でも行を数える", () => {
    expect(extractSqlStatement(SOURCE.replace(/\r\n/g, "\n"), 3).lines).toHaveLength(3);
    expect(extractSqlStatement(SOURCE.replace(/\r\n/g, "\r"), 3).lines).toHaveLength(3);
  });

  it("END-EXEC が無ければ行数で打ち切らず、次の文の開始行の手前までを返す", () => {
    const long =
      "EXEC SQL\n" +
      Array.from({ length: 50 }, (_, i) => `  line ${i + 1}`).join("\n") +
      "\nEXEC SQL\n  SELECT 2\nEND-EXEC.\n";
    const result = extractSqlStatement(long, 1);
    // 52 行目(2 つ目の EXEC SQL)の手前、1〜51 行目までを返す。行数の上限では打ち切らない。
    expect(result.lines).toHaveLength(51);
    expect(result.lines[0]).toBe("EXEC SQL");
    expect(result.lines[50]).toBe("  line 50");
    expect(result.unterminated).toBe(true);
  });

  it("次の文も無くファイル末尾に達した場合も、終端不明として末尾までを返す", () => {
    const result = extractSqlStatement("EXEC SQL\n  SELECT 1", 1);
    expect(result.lines).toEqual(["EXEC SQL", "  SELECT 1"]);
    expect(result.unterminated).toBe(true);
  });

  it("開始行がファイルの範囲外なら空を返す", () => {
    expect(extractSqlStatement(SOURCE, 99)).toEqual({ lines: [], unterminated: false });
    expect(extractSqlStatement(SOURCE, 0)).toEqual({ lines: [], unterminated: false });
  });
});

describe("adviceAt(同一 SQL 文へ付く指摘の集約)", () => {
  it("同一ファイル・同一開始行の指摘を全件返し、名称と重大度を添える", () => {
    const entries = adviceAt(SAMPLE_SQL_FINDINGS, "cobol/SYK006.cbl", 145);
    expect(entries.map((e) => e.finding.ruleId)).toEqual(["S001", "S004", "S006"]);
    expect(entries[0]).toMatchObject({ ruleName: "SELECT * の回避", severity: "medium" });
  });

  it("別のファイル・別の行の指摘は含めない", () => {
    const entries = adviceAt(SAMPLE_SQL_FINDINGS, "cobol/SYK007.cbl", 84);
    expect(entries.map((e) => e.finding.ruleId)).toEqual(["S002", "S003"]);
  });

  it("該当が無ければ空を返す", () => {
    expect(adviceAt(SAMPLE_SQL_FINDINGS, "cobol/SYK006.cbl", 1)).toEqual([]);
  });
});
