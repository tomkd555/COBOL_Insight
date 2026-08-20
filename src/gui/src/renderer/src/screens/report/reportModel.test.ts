import { describe, it, expect } from "vitest";
import {
  REPORT_SANDBOX,
  deriveReportView,
  exitCodeWarning,
  previewPath,
  readReportSummary,
  reportArtifactPaths,
  reportMetrics,
  withTrailingSeparator,
  writeNotice,
  type ReportSummary,
} from "./reportModel";
import { SAMPLE_REPORT_SUMMARY } from "./fixtures";

function summaryOf(overrides: Partial<ReportSummary> = {}): ReportSummary {
  return {
    assets: 0,
    scanFindings: 0,
    lintFindings: 0,
    sqlAdvice: 0,
    callGraphNodes: 0,
    callGraphEdges: 0,
    exitCode: 0,
    ...overrides,
  };
}

describe("REPORT_SANDBOX", () => {
  it("すべての権限を落とす(スクリプトも同一オリジン扱いも許さない)", () => {
    expect(REPORT_SANDBOX).toBe("");
    expect(REPORT_SANDBOX).not.toContain("allow-scripts");
    expect(REPORT_SANDBOX).not.toContain("allow-same-origin");
  });
});

describe("reportArtifactPaths", () => {
  it("出力先を指定しなければプロジェクトファイルと同じ場所へ書く", () => {
    expect(reportArtifactPaths("C:\\proj\\cobol-insight.db")).toEqual({
      db: "C:\\proj\\cobol-insight.db",
      dir: "C:\\proj\\",
      html: "C:\\proj\\cobol-insight-report.html",
      text: "C:\\proj\\cobol-insight-report.txt",
    });
  });

  it("指定した出力先フォルダへ書く", () => {
    const paths = reportArtifactPaths("C:\\proj\\cobol-insight.db", "D:\\出力");
    expect(paths.html).toBe("D:\\出力\\cobol-insight-report.html");
    expect(paths.text).toBe("D:\\出力\\cobol-insight-report.txt");
  });

  it("末尾に区切りがある出力先でも区切りを重ねない", () => {
    expect(reportArtifactPaths("C:\\proj\\x.db", "D:\\出力\\").html).toBe(
      "D:\\出力\\cobol-insight-report.html",
    );
  });

  it("スラッシュ区切りの出力先も扱う", () => {
    expect(reportArtifactPaths("/home/u/x.db", "/home/u/out").text).toBe(
      "/home/u/out/cobol-insight-report.txt",
    );
  });

  it("プロジェクトファイルが無いときは既定名から導く", () => {
    expect(reportArtifactPaths(null)).toEqual({
      db: "cobol-insight.db",
      dir: "",
      html: "cobol-insight-report.html",
      text: "cobol-insight-report.txt",
    });
  });

  it("空文字の出力先は未指定として扱う", () => {
    expect(reportArtifactPaths("C:\\proj\\x.db", "").dir).toBe("C:\\proj\\");
  });

  it("末尾の区切りは1つだけ付ける", () => {
    expect(withTrailingSeparator("C:\\a")).toBe("C:\\a\\");
    expect(withTrailingSeparator("C:\\a\\")).toBe("C:\\a\\");
    expect(withTrailingSeparator("/a")).toBe("/a/");
    expect(withTrailingSeparator("")).toBe("");
  });
});

describe("readReportSummary", () => {
  it("サマリ JSON から件数を取り出す", () => {
    expect(readReportSummary(SAMPLE_REPORT_SUMMARY)).toEqual({
      assets: 19,
      scanFindings: 0,
      lintFindings: 24,
      sqlAdvice: 6,
      callGraphNodes: 33,
      callGraphEdges: 41,
      exitCode: 0,
    });
  });

  it("サマリが無いときは 0 件として扱う", () => {
    expect(readReportSummary(null).assets).toBe(0);
  });

  it("型の合わない値は 0 として扱う", () => {
    expect(readReportSummary({ assets: "19" }).assets).toBe(0);
  });
});

describe("reportMetrics", () => {
  it("engine のサマリが返した実件数だけを並べる", () => {
    const metrics = reportMetrics(readReportSummary(SAMPLE_REPORT_SUMMARY));
    expect(metrics.map((metric) => metric.label)).toEqual([
      "資産",
      "指摘",
      "SQL指摘",
      "呼出関係",
      "解析エラー",
    ]);
    expect(metrics[1].value).toBe("24");
    expect(metrics[3].value).toBe("ノード 33 ・ エッジ 41");
  });
});

describe("previewPath", () => {
  const paths = reportArtifactPaths("C:\\proj\\x.db");

  it("HTML を選ぶと HTML のパスを返す", () => {
    expect(previewPath("HTML", paths)).toBe(paths.html);
  });

  it("テキストを選ぶとテキストのパスを返す", () => {
    expect(previewPath("テキスト", paths)).toBe(paths.text);
  });
});

describe("exitCodeWarning", () => {
  it("成功では警告を出さない", () => {
    expect(exitCodeWarning(0)).toBeNull();
  });

  it("警告ありではレポート本体が書かれた旨を添える", () => {
    expect(exitCodeWarning(1)).toContain("書き出しています");
  });

  it("エラーありでは解析できていない可能性を示す", () => {
    expect(exitCodeWarning(2)).toContain("解析できていない");
  });
});

describe("writeNotice", () => {
  it("engine が書いた2つのファイルのパスを示す", () => {
    const notice = writeNotice(reportArtifactPaths("C:\\proj\\x.db"));
    expect(notice).toContain("C:\\proj\\cobol-insight-report.html");
    expect(notice).toContain("C:\\proj\\cobol-insight-report.txt");
  });
});

describe("deriveReportView", () => {
  it("解析未実行は空状態", () => {
    expect(deriveReportView("empty", null, { status: "idle" })).toEqual({ kind: "empty" });
  });

  it("解析実行中は実行中", () => {
    expect(deriveReportView("running", "C:\\src", { status: "idle" })).toEqual({ kind: "running" });
  });

  it("資産フォルダ未選択は専用の案内", () => {
    expect(deriveReportView("results", null, { status: "idle" })).toEqual({ kind: "no-project" });
  });

  it("解析済みでレポート未生成は書き出しの操作を待つ", () => {
    expect(deriveReportView("results", "C:\\src", { status: "idle" })).toEqual({
      kind: "not-generated",
    });
  });

  it("レポート生成中は実行中", () => {
    expect(deriveReportView("results", "C:\\src", { status: "loading" })).toEqual({
      kind: "running",
    });
  });

  it("生成の失敗はエラーとして理由を持つ", () => {
    expect(
      deriveReportView("results", "C:\\src", { status: "error", message: "書き込めない" }),
    ).toEqual({ kind: "error", message: "書き込めない" });
  });

  it("生成できたら結果を出す", () => {
    expect(
      deriveReportView("error", "C:\\src", {
        status: "ready",
        summary: summaryOf(),
        html: "<p></p>",
        text: "",
        paths: reportArtifactPaths("C:\\proj\\x.db"),
      }),
    ).toEqual({ kind: "results" });
  });
});
