import { describe, it, expect } from "vitest";
import {
  adoptedPaths,
  analysisWarning,
  applyCaution,
  applyNotice,
  buildFixCandidates,
  candidateLocation,
  candidateRuleSummary,
  decisionCounts,
  decisionLabel,
  decisionModifier,
  deriveDiffView,
  extractUnifiedDiff,
  fixCountLabel,
  fixOutputPaths,
  joinPath,
  readFixSummary,
  reparseWarning,
  resolveSelection,
  setDecision,
  type FixCandidate,
} from "./diffModel";
import {
  SAMPLE_APPLY_SUMMARY,
  SAMPLE_PREVIEW_STDOUT,
  SAMPLE_PREVIEW_SUMMARY,
} from "./fixtures";
import { SAMPLE_FINDINGS } from "../findings/fixtures";
import type { FixSummaryInfo, SarifFinding } from "../../../../shared/engine-api";

/** 件数だけを与える最小のサマリ。 */
function summaryOf(overrides: Partial<FixSummaryInfo> = {}): FixSummaryInfo {
  return { files: [], copybookFixes: [], fixCount: 0, analysisErrors: 0, ...overrides };
}

describe("buildFixCandidates", () => {
  const candidates = buildFixCandidates(readFixSummary(SAMPLE_PREVIEW_SUMMARY), SAMPLE_FINDINGS);

  it("engine が出したファイル順を保つ", () => {
    expect(candidates.map((c) => c.relPath)).toEqual([
      "cobol/SYK007.cbl",
      "cobol/SYK006.cbl",
      "copybook/ORDREC.cpy",
    ]);
  });

  it("コピー句の修正には取込プログラム一覧を持たせる", () => {
    const copybook = candidates[2];
    expect(copybook.copybook).toBe(true);
    expect(copybook.importers).toEqual(["SYK001", "SYK002"]);
    expect(copybook.name).toBe("ORDREC.cpy");
  });

  it("COBOL 本体の修正はコピー句として扱わない", () => {
    expect(candidates[0].copybook).toBe(false);
    expect(candidates[0].importers).toEqual([]);
  });

  it("修正案を持つルールの指摘だけを内訳に載せる", () => {
    expect(candidates[0].findings).toEqual([
      {
        ruleId: "R004",
        ruleName: "ON SIZE ERROR句の欠如",
        line: 79,
        message: "COMPUTE 文に ON SIZE ERROR 句が無く、けたあふれが検知されない。",
      },
    ]);
    expect(candidates[1].findings.map((f) => f.ruleId)).toEqual(["R018"]);
  });

  it("修正案を持たないルールの指摘は内訳に載せない", () => {
    const findings: SarifFinding[] = [
      { ruleId: "R009", level: "warning", message: "x", file: "cobol/A.cbl", startLine: 5, startColumn: 1 },
      { ruleId: "R017", level: "error", message: "y", file: "cobol/A.cbl", startLine: 3, startColumn: 1 },
    ];
    const built = buildFixCandidates(summaryOf({ files: ["cobol/A.cbl"] }), findings);
    expect(built[0].findings.map((f) => f.ruleId)).toEqual(["R017"]);
  });

  it("同一ファイルの複数の指摘を行の昇順で並べる", () => {
    const findings: SarifFinding[] = [
      { ruleId: "R018", level: "error", message: "b", file: "cobol/A.cbl", startLine: 90, startColumn: 1 },
      { ruleId: "R004", level: "error", message: "a", file: "cobol/A.cbl", startLine: 12, startColumn: 1 },
    ];
    const built = buildFixCandidates(summaryOf({ files: ["cobol/A.cbl"] }), findings);
    expect(built[0].findings.map((f) => f.line)).toEqual([12, 90]);
  });

  it("対象ファイル一覧に無いコピー句も一覧から落とさない", () => {
    const summary = summaryOf({
      files: ["cobol/A.cbl"],
      copybookFixes: [{ copybook: "copybook/B.cpy", importers: ["A"] }],
    });
    expect(buildFixCandidates(summary, []).map((c) => c.relPath)).toEqual([
      "cobol/A.cbl",
      "copybook/B.cpy",
    ]);
  });
});

describe("見出しの文言", () => {
  const candidates = buildFixCandidates(readFixSummary(SAMPLE_PREVIEW_SUMMARY), SAMPLE_FINDINGS);

  it("件数見出しは修正案を持つ3ルールを併記する", () => {
    expect(fixCountLabel(candidates)).toBe("3 件（R004 / R017 / R018）");
  });

  it("単一ルールはルール名を添える", () => {
    expect(candidateRuleSummary(candidates[0])).toBe("R004 ON SIZE ERROR句の欠如");
  });

  it("複数ルールはルール ID を並べて件数を添える", () => {
    const multi: FixCandidate = {
      relPath: "cobol/A.cbl",
      name: "A.cbl",
      copybook: false,
      importers: [],
      findings: [
        { ruleId: "R004", ruleName: "x", line: 1, message: "" },
        { ruleId: "R017", ruleName: "y", line: 2, message: "" },
      ],
    };
    expect(candidateRuleSummary(multi)).toBe("R004・R017（2 ルール）");
  });

  it("指摘が取れていないときはルール名を騙らない", () => {
    expect(candidateRuleSummary(candidates[2])).toBe("修正案");
    expect(candidateLocation(candidates[2])).toBe("copybook/ORDREC.cpy");
  });

  it("位置は先頭の指摘行を添える", () => {
    expect(candidateLocation(candidates[0])).toBe("cobol/SYK007.cbl:79");
  });
});

describe("採用・棄却の集合", () => {
  const candidates = buildFixCandidates(readFixSummary(SAMPLE_PREVIEW_SUMMARY), SAMPLE_FINDINGS);

  it("判定の表示文言を返す", () => {
    expect(decisionLabel("adopted")).toBe("✓ 採用済");
    expect(decisionLabel("rejected")).toBe("✗ 棄却済");
    expect(decisionLabel(undefined)).toBe("未判定");
    expect(decisionModifier(undefined)).toBe("pending");
    expect(decisionModifier("adopted")).toBe("adopted");
  });

  it("判定を設定した新しい集合を返し、元の集合を変えない", () => {
    const before = { "cobol/SYK006.cbl": "rejected" } as const;
    const after = setDecision(before, "cobol/SYK007.cbl", "adopted");
    expect(after).toEqual({ "cobol/SYK006.cbl": "rejected", "cobol/SYK007.cbl": "adopted" });
    expect(before).toEqual({ "cobol/SYK006.cbl": "rejected" });
  });

  it("同じ判定を再度指定すると未判定へ戻す", () => {
    const after = setDecision({ "cobol/A.cbl": "adopted" }, "cobol/A.cbl", "adopted");
    expect(after).toEqual({});
  });

  it("別の判定を指定すると置き換える", () => {
    const after = setDecision({ "cobol/A.cbl": "adopted" }, "cobol/A.cbl", "rejected");
    expect(after).toEqual({ "cobol/A.cbl": "rejected" });
  });

  it("内訳は一覧にある修正案だけを数える", () => {
    const counts = decisionCounts(candidates, {
      "cobol/SYK007.cbl": "adopted",
      "cobol/SYK006.cbl": "rejected",
      "cobol/消えた.cbl": "adopted",
    });
    expect(counts).toEqual({ adopted: 1, rejected: 1, pending: 1 });
  });

  it("採用した修正案を一覧の順序で返す", () => {
    expect(
      adoptedPaths(candidates, {
        "copybook/ORDREC.cpy": "adopted",
        "cobol/SYK007.cbl": "adopted",
      }),
    ).toEqual(["cobol/SYK007.cbl", "copybook/ORDREC.cpy"]);
  });
});

describe("resolveSelection", () => {
  const candidates = buildFixCandidates(readFixSummary(SAMPLE_PREVIEW_SUMMARY), SAMPLE_FINDINGS);

  it("選択中の修正案を返す", () => {
    expect(resolveSelection(candidates, "cobol/SYK006.cbl")?.relPath).toBe("cobol/SYK006.cbl");
  });

  it("選択が一覧から消えていれば先頭を採る", () => {
    expect(resolveSelection(candidates, "cobol/消えた.cbl")?.relPath).toBe("cobol/SYK007.cbl");
  });

  it("一覧が空なら null を返す", () => {
    expect(resolveSelection([], "cobol/A.cbl")).toBeNull();
  });
});

describe("書出先パス", () => {
  it("プロジェクトファイルと同じ場所の直下へ置く", () => {
    expect(fixOutputPaths("C:\\proj\\cobol-insight.db")).toEqual({
      previewDir: "C:\\proj\\fix-preview",
      applyDir: "C:\\proj\\fix",
    });
  });

  it("スラッシュ区切りのパスも扱う", () => {
    expect(fixOutputPaths("/home/u/p/cobol-insight.db").applyDir).toBe("/home/u/p/fix");
  });

  it("プロジェクトファイルが無いときは既定名から導く", () => {
    expect(fixOutputPaths(null)).toEqual({ previewDir: "fix-preview", applyDir: "fix" });
  });

  it("基準側の区切りにそろえて連結する", () => {
    expect(joinPath("C:\\proj\\fix", "cobol/SYK007.cbl")).toBe("C:\\proj\\fix\\cobol\\SYK007.cbl");
    expect(joinPath("/home/u", "cobol/A.cbl")).toBe("/home/u/cobol/A.cbl");
  });

  it("末尾の区切りを重ねない", () => {
    expect(joinPath("C:\\proj\\fix\\", "a.cbl")).toBe("C:\\proj\\fix\\a.cbl");
  });
});

describe("extractUnifiedDiff", () => {
  it("1ファイル分の差分を engine の出力そのままで切り出す", () => {
    expect(extractUnifiedDiff(SAMPLE_PREVIEW_STDOUT, "copybook/ORDREC.cpy")).toEqual([
      "--- a/copybook/ORDREC.cpy",
      "+++ b/copybook/ORDREC.cpy",
      "@@ -10,2 +10,3 @@",
      "        05 ORD-金額  PIC S9(9)V99 COMP-3.",
      "+       05 ORD-状態  PIC X(1).",
    ]);
  });

  it("次のファイルの見出しで打ち切る", () => {
    const lines = extractUnifiedDiff(SAMPLE_PREVIEW_STDOUT, "cobol/SYK007.cbl");
    expect(lines[lines.length - 1]).toBe("+               END-COMPUTE");
    expect(lines).not.toContain("--- a/copybook/ORDREC.cpy");
  });

  it("該当ファイルの差分が無ければ空を返す", () => {
    expect(extractUnifiedDiff(SAMPLE_PREVIEW_STDOUT, "cobol/無い.cbl")).toEqual([]);
  });

  it("CRLF 改行でも切り出せる", () => {
    const stdout = "--- a/a.cbl\r\n+++ b/a.cbl\r\n@@ -1 +1 @@\r\n+x\r\n";
    expect(extractUnifiedDiff(stdout, "a.cbl")).toEqual([
      "--- a/a.cbl",
      "+++ b/a.cbl",
      "@@ -1 +1 @@",
      "+x",
    ]);
  });
});

describe("警告と注意の文言", () => {
  it("再パース検証の失敗を件数付きで示す", () => {
    expect(reparseWarning(2)).toContain("2 件");
  });

  it("失敗が無い・未検証のときは警告を出さない", () => {
    expect(reparseWarning(0)).toBeNull();
    expect(reparseWarning(null)).toBeNull();
  });

  it("解析段のエラーを件数付きで示す", () => {
    expect(analysisWarning(summaryOf({ analysisErrors: 3 }))).toContain("3 件");
    expect(analysisWarning(summaryOf())).toBeNull();
  });

  it("棄却があるときだけ書き出し前の注意を出す", () => {
    expect(applyCaution({ adopted: 1, rejected: 2, pending: 0 })).toContain("2 件");
    expect(applyCaution({ adopted: 1, rejected: 0, pending: 1 })).toBeNull();
  });

  it("書き出し結果は出力先・件数・コピー句の扱いを示す", () => {
    const notice = applyNotice({
      outDir: "C:\\proj\\fix",
      written: ["cobol/A.cbl", "cobol/B.cbl"],
      copybookFixes: [{ copybook: "copybook/C.cpy", importers: ["A"] }],
      reparseFailures: 1,
    });
    expect(notice).toContain("C:\\proj\\fix");
    expect(notice).toContain("2 件");
    expect(notice).toContain("コピー句 1 件");
    expect(notice).toContain("再構文解析の失敗 1 件");
  });
});

describe("deriveDiffView", () => {
  it("解析未実行は空状態", () => {
    expect(deriveDiffView("empty", null, { status: "idle" })).toEqual({ kind: "empty" });
  });

  it("解析実行中は実行中", () => {
    expect(deriveDiffView("running", "C:\\src", { status: "idle" })).toEqual({ kind: "running" });
  });

  it("資産フォルダ未選択は専用の案内", () => {
    expect(deriveDiffView("results", null, { status: "idle" })).toEqual({ kind: "no-project" });
  });

  it("修正案の生成中は実行中", () => {
    expect(deriveDiffView("results", "C:\\src", { status: "loading" })).toEqual({ kind: "running" });
  });

  it("修正案の生成失敗はエラーとして理由を持つ", () => {
    expect(deriveDiffView("results", "C:\\src", { status: "error", message: "起動できない" })).toEqual({
      kind: "error",
      message: "起動できない",
    });
  });

  it("取得できたら結果を出す", () => {
    expect(
      deriveDiffView("results", "C:\\src", {
        status: "ready",
        summary: summaryOf(),
        stdout: "",
        reparseFailures: 0,
        materializedDir: "C:\\proj\\fix-preview",
      }),
    ).toEqual({ kind: "results" });
  });

  it("解析が部分的に失敗していても取得できた修正案は表示する", () => {
    expect(
      deriveDiffView("error", "C:\\src", {
        status: "ready",
        summary: summaryOf({ analysisErrors: 1 }),
        stdout: "",
        reparseFailures: null,
        materializedDir: null,
      }),
    ).toEqual({ kind: "results" });
  });
});
