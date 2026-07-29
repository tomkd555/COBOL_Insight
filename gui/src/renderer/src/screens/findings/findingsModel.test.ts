import { describe, it, expect } from "vitest";
import {
  ALL,
  fileOptions,
  filterAndSortFindings,
  initialFindingFilters,
  ruleOptions,
  severityAllowed,
  severityCounts,
  summaryText,
  thresholdNote,
  type FindingFilters,
} from "./findingsModel";
import { SAMPLE_FINDINGS } from "./fixtures";
import type { SarifFinding } from "../../../../shared/engine-api";

function withFilters(overrides: Partial<FindingFilters>): FindingFilters {
  return { ...initialFindingFilters, ...overrides };
}

describe("findingsView(共有一覧のビューモデル)", () => {
  it("重大度をルールカタログから集計する(SARIF の level ではない)", () => {
    // 高: R017/R005/R001/R018/R004、中: R008/R011/R022、低: R002、警告: R009
    expect(severityCounts(SAMPLE_FINDINGS)).toEqual({ high: 5, medium: 3, low: 1, warning: 1 });
  });

  it("ルール選択肢は出現ルールを昇順に並べ先頭へ「すべて」を置く", () => {
    const opts = ruleOptions(SAMPLE_FINDINGS);
    expect(opts[0]).toEqual({ value: ALL, label: "ルール: すべて" });
    expect(opts.map((o) => o.value)).toEqual([
      ALL, "R001", "R002", "R004", "R005", "R008", "R009", "R011", "R017", "R018", "R022",
    ]);
    expect(opts[1].label).toBe("R001 未初期化変数の参照");
  });

  it("ファイル選択肢は出現ファイルを昇順に並べ先頭へ「すべて」を置く", () => {
    const opts = fileOptions(SAMPLE_FINDINGS);
    expect(opts[0]).toEqual({ value: ALL, label: "ファイル: すべて" });
    expect(opts.map((o) => o.value)).toEqual([
      ALL, "cobol/SYK001.cbl", "cobol/SYK002.cbl", "cobol/SYK003.cbl",
      "cobol/SYK004.cbl", "cobol/SYK006.cbl", "cobol/SYK007.cbl", "cobol/SYK009.cbl",
    ]);
  });

  it("重大度フィルタで絞る(高のみ)", () => {
    const rows = filterAndSortFindings(
      SAMPLE_FINDINGS,
      withFilters({ severity: { high: true, medium: false, low: false, warning: false } }),
    );
    expect(rows).toHaveLength(5);
    expect(rows.every((r) => r.severity === "high")).toBe(true);
  });

  it("ルールフィルタで絞る", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ rule: "R017" }));
    expect(rows).toHaveLength(1);
    expect(rows[0].finding.ruleId).toBe("R017");
  });

  it("ファイルフィルタで絞る", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ file: "cobol/SYK001.cbl" }));
    expect(rows).toHaveLength(4);
    expect(rows.every((r) => r.finding.file === "cobol/SYK001.cbl")).toBe(true);
  });

  it("内容テキストは message・ルール名・ファイルを対象に部分一致する", () => {
    expect(filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ text: "SQLCODE" }))).toHaveLength(1);
    // ルール名「未初期化変数の参照」に一致(R001)。
    expect(filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ text: "未初期化変数" }))).toHaveLength(1);
    // ファイル名にも一致。
    expect(filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ text: "SYK001" }))).toHaveLength(4);
  });

  it("重大度ソート(昇順)は 高→中→低→警告、同順位はファイル→行", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "sev", direction: "asc" });
    const order = rows.map((r) => r.severity);
    expect(order).toEqual([...order].sort((a, b) => {
      const idx = { high: 0, medium: 1, low: 2, warning: 3 } as const;
      return idx[a] - idx[b];
    }));
    expect(order[0]).toBe("high");
    expect(order[order.length - 1]).toBe("warning");
    // 同じ重大度(高)の 5 件はファイル昇順、同一ファイル内は行昇順に並ぶ。
    const high = rows.filter((r) => r.severity === "high").map((r) => `${r.finding.file}:${r.finding.startLine}`);
    expect(high).toEqual([
      "cobol/SYK001.cbl:85",
      "cobol/SYK001.cbl:114",
      "cobol/SYK001.cbl:121",
      "cobol/SYK006.cbl:119",
      "cobol/SYK007.cbl:79",
    ]);
  });

  it("重大度ソート(降順)は昇順をそのまま逆順にする(警告→低→中→高)", () => {
    const asc = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "sev", direction: "asc" });
    const desc = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "sev", direction: "desc" });
    expect(desc).toEqual([...asc].reverse());
  });

  it("行ソートは startLine 昇順、降順で逆になる", () => {
    const asc = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "line", direction: "asc" });
    const ascLines = asc.map((r) => r.finding.startLine);
    expect(ascLines).toEqual([...ascLines].sort((a, b) => a - b));

    const desc = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "line", direction: "desc" });
    const descLines = desc.map((r) => r.finding.startLine);
    expect(descLines).toEqual([...ascLines].reverse());
  });

  it("ファイルソートはファイル名昇順、同一ファイル内は行昇順", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "file", direction: "asc" });
    const keys = rows.map((r) => `${r.finding.file}:${r.finding.startLine}`);
    expect(keys).toEqual([
      "cobol/SYK001.cbl:73",
      "cobol/SYK001.cbl:85",
      "cobol/SYK001.cbl:114",
      "cobol/SYK001.cbl:121",
      "cobol/SYK002.cbl:124",
      "cobol/SYK003.cbl:20",
      "cobol/SYK004.cbl:47",
      "cobol/SYK006.cbl:119",
      "cobol/SYK007.cbl:79",
      "cobol/SYK009.cbl:21",
    ]);
  });

  it("ルールソートは ID を昇順に並べる(カタログ順の R001→R031→S001…と一致)", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "rule", direction: "asc" });
    const ids = rows.map((r) => r.finding.ruleId);
    expect(ids).toEqual([...ids].sort());
  });

  it("ルールソートは ID を文字列比較せず接頭辞+数値で比べる(2桁と3桁の混在でも数値順)", () => {
    // 文字列比較では "R100" < "R9" になってしまうが、接頭辞+数値の比較では R9 → R100 の順になる。
    const findings: SarifFinding[] = [
      { ruleId: "R100", level: "error", message: "m1", file: "a.cbl", startLine: 1, startColumn: 1 },
      { ruleId: "R9", level: "error", message: "m2", file: "a.cbl", startLine: 2, startColumn: 1 },
      { ruleId: "R10", level: "error", message: "m3", file: "a.cbl", startLine: 3, startColumn: 1 },
    ];
    const rows = filterAndSortFindings(findings, withFilters({}), { column: "rule", direction: "asc" });
    expect(rows.map((r) => r.finding.ruleId)).toEqual(["R9", "R10", "R100"]);
  });

  it("ソート向きを指定しない場合は昇順(既定値)になる", () => {
    const withDefault = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}));
    const explicitAsc = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({}), { column: "sev", direction: "asc" });
    expect(withDefault).toEqual(explicitAsc);
  });

  it("空配列は行 0 件・件数 0 件の要約になる", () => {
    expect(filterAndSortFindings([], initialFindingFilters)).toEqual([]);
    expect(severityCounts([])).toEqual({ high: 0, medium: 0, low: 0, warning: 0 });
    expect(summaryText([], 0)).toBe("0 件（高 0 / 中 0 / 低 0 / 警告 0）― 表示 0 件");
  });

  it("カタログに無いルール ID(engine の解析エラー)も重大度 高として扱い、フィルタで残す", () => {
    const parseFailure: SarifFinding = {
      ruleId: "parse-failure",
      level: "error",
      message: "構文解析に失敗した。",
      file: "cobol/SYKBAD.cbl",
      startLine: 5,
      startColumn: 1,
    };
    const findings = [...SAMPLE_FINDINGS, parseFailure];
    expect(severityCounts(findings).high).toBe(6);
    const rows = filterAndSortFindings(findings, withFilters({ rule: "parse-failure" }));
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ severity: "high", ruleName: "構文解析失敗", hasFix: false });
    // 高を切ると未知ルールの指摘も消える。
    const hidden = filterAndSortFindings(findings, withFilters({ severity: { high: false, medium: true, low: true, warning: true } }));
    expect(hidden.some((r) => r.finding.ruleId === "parse-failure")).toBe(false);
  });

  describe("重大度しきい値との合成", () => {
    it("しきい値より低い重大度は一覧から除く", () => {
      const rows = filterAndSortFindings(SAMPLE_FINDINGS, withFilters({ threshold: "medium" }));
      // 高 5 件と中 3 件だけが残り、低(R002)と警告(R009)は消える。
      expect(rows).toHaveLength(8);
      expect(rows.some((r) => r.severity === "low")).toBe(false);
      expect(rows.some((r) => r.severity === "warning")).toBe(false);
    });

    it("しきい値が許した範囲の中でチップが絞る(合成は論理積)", () => {
      const rows = filterAndSortFindings(
        SAMPLE_FINDINGS,
        withFilters({
          threshold: "medium",
          severity: { high: false, medium: true, low: true, warning: true },
        }),
      );
      expect(rows).toHaveLength(3);
      expect(rows.every((r) => r.severity === "medium")).toBe(true);
    });

    it("初期のしきい値(警告)はすべての重大度を通す", () => {
      expect(initialFindingFilters.threshold).toBe("warning");
      expect(filterAndSortFindings(SAMPLE_FINDINGS, initialFindingFilters)).toHaveLength(10);
    });

    it("しきい値が許す重大度だけをチップの操作対象にする", () => {
      expect(severityAllowed("high", "medium")).toBe(true);
      expect(severityAllowed("medium", "medium")).toBe(true);
      expect(severityAllowed("low", "medium")).toBe(false);
      expect(severityAllowed("warning", "medium")).toBe(false);
      expect(severityAllowed("warning", "warning")).toBe(true);
    });

    it("しきい値が全件を通すときは注記を出さない", () => {
      expect(thresholdNote("warning")).toBeNull();
      expect(thresholdNote("medium")).toBe("重大度しきい値「中」以上を表示（設定で変更する）");
    });
  });

  it("要約文は全件・重大度内訳・表示件数を示す", () => {
    const rows = filterAndSortFindings(SAMPLE_FINDINGS, initialFindingFilters);
    expect(summaryText(SAMPLE_FINDINGS, rows.length)).toBe(
      "10 件（高 5 / 中 3 / 低 1 / 警告 1）― 表示 10 件",
    );
  });
});
