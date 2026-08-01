import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseSarif } from "./sarif";

const fixture = readFileSync(join(__dirname, "..", "__fixtures__", "lint.sarif"), "utf-8");

describe("parseSarif", () => {
  it("実 lint SARIF から検出結果を平坦化する", () => {
    const findings = parseSarif(fixture);
    expect(findings.length).toBe(68);
    const first = findings[0];
    expect(first.ruleId).toBe("R008");
    expect(first.level).toBe("warning");
    expect(first.file).toBe("samples/cobol/SYK001.cbl");
    expect(first.startLine).toBe(73);
    expect(first.startColumn).toBe(12);
    expect(first.message).toContain("PERFORM");
  });

  it("results が空の SARIF は空配列を返す", () => {
    const empty = JSON.stringify({
      version: "2.1.0",
      runs: [{ tool: { driver: { name: "x", rules: [] } }, results: [] }],
    });
    expect(parseSarif(empty)).toEqual([]);
  });

  it("パーセントエンコードされた uri を復号して相対パスへ戻す", () => {
    // SarifWriter は非 pchar バイトを UTF-8 パーセントエンコードするため、日本語名の資産は
    // %E3%.. の形で現れる。表示とジャンプに使える相対パスへ戻す必要がある。
    const doc = JSON.stringify({
      version: "2.1.0",
      runs: [
        {
          tool: { driver: { name: "x", rules: [] } },
          results: [
            {
              ruleId: "R008",
              message: { text: "m" },
              locations: [
                {
                  physicalLocation: {
                    artifactLocation: { uri: "cobol/%E5%9C%A8%E5%BA%AB%E6%9B%B4%E6%96%B0.cbl" },
                    region: { startLine: 1, startColumn: 1 },
                  },
                },
              ],
            },
          ],
        },
      ],
    });
    expect(parseSarif(doc)[0].file).toBe("cobol/在庫更新.cbl");
  });

  it("復号できない uri はそのまま返す(不正なパーセント列)", () => {
    const doc = JSON.stringify({
      version: "2.1.0",
      runs: [
        {
          tool: { driver: { name: "x", rules: [] } },
          results: [
            {
              ruleId: "R008",
              message: { text: "m" },
              locations: [{ physicalLocation: { artifactLocation: { uri: "cobol/%E3%81.cbl" } } }],
            },
          ],
        },
      ],
    });
    expect(parseSarif(doc)[0].file).toBe("cobol/%E3%81.cbl");
  });

  it("region の欠落を 0 で補い、level 欠落は none とする", () => {
    const doc = JSON.stringify({
      version: "2.1.0",
      runs: [
        {
          tool: { driver: { name: "x", rules: [] } },
          results: [
            {
              ruleId: "R999",
              message: { text: "no region" },
              locations: [
                { physicalLocation: { artifactLocation: { uri: "a/b.cbl" } } },
              ],
            },
          ],
        },
      ],
    });
    const findings = parseSarif(doc);
    expect(findings[0]).toMatchObject({
      ruleId: "R999",
      level: "none",
      file: "a/b.cbl",
      startLine: 0,
      startColumn: 0,
      message: "no region",
    });
  });
});
