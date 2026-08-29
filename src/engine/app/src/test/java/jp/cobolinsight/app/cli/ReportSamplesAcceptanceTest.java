package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.core.finding.Finding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * samples/ 全体の report 受入回帰テスト。samples を scan して SQLite を作り、
 * その DB と資産フォルダに対し report を実行して、統合レポートに次を含むことを突合する:
 * データフロー解析による7欠陥(expected-results.md No.1/2/3/5/9/13/14 = R001/R003/R004/R005)を lint 検出として、
 * SQL 指摘 S004/S006(SYK006:145)を、呼出関係の要約(プログラム間の CALL 辺)を含み、
 * HTML とテキストの両形式を生成し、統合の終了コードが 2(samples は ERROR レベルの検出を含む)に
 * なること。
 */
class ReportSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path tempDir;

    private static ReportRunner.Result result;

    @BeforeAll
    static void scanThenReport() {
        Path db = tempDir.resolve("report.db");
        Pipelines.scan(SAMPLES, db,
                List.of(SAMPLES.resolve("copybook")), Map.of()).summary();
        result = ReportRunner.run(new ReportRunner.Options(SAMPLES, db,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
    }

    /** finding を "ルールID@ファイル:行" 形式の文字列集合へ変換する。 */
    private static Set<String> keyed(List<Finding> findings) {
        return findings.stream()
                .map(f -> f.ruleId() + "@" + f.location().file() + ":" + f.location().line())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void lintPortionContainsAllSevenDataFlowDefects() {
        Set<String> lint = keyed(result.lintFindings());
        for (String expected : List.of(
                "R001@cobol/SYK001.cbl:121", "R001@cobol/SYK004.cbl:41",
                "R003@cobol/SYK001.cbl:128", "R003@cobol/SYK002.cbl:118",
                "R005@cobol/SYK001.cbl:114", "R005@cobol/SYK006.cbl:139",
                "R004@cobol/SYK007.cbl:79")) {
            assertTrue(lint.contains(expected),
                    () -> "統合レポートの lint 検出に " + expected + " を含むこと: " + lint);
        }
    }

    @Test
    void sqlAdvicePortionContainsCursorAdvice() {
        Set<String> advice = keyed(result.sqlAdviceFindings());
        assertTrue(advice.contains("S004@cobol/SYK006.cbl:145"),
                () -> "SQL指摘に S004(SYK006:145)を含むこと: " + advice);
        assertTrue(advice.contains("S006@cobol/SYK006.cbl:145"),
                () -> "SQL指摘に S006(SYK006:145)を含むこと: " + advice);
    }

    @Test
    void callGraphSummaryHasProgramNodesAndCallEdge() {
        ReportRunner.CallGraphSummary graph = result.callGraph();
        assertTrue(graph.nodeCount() > 0, "呼出関係の要約にノードがあること");
        assertTrue(graph.nodesByType().getOrDefault("PROGRAM", 0) >= 9,
                () -> "COBOL 9本がプログラムノードとして数えられること: " + graph.nodesByType());
        assertTrue(graph.edges().stream()
                        .anyMatch(e -> e.from().equals("SYK002") && e.to().equals("SYK004")),
                () -> "SYK002→SYK004 の呼出辺を要約に含むこと: " + graph.edges());
    }

    @Test
    void inventoryListsAllScannedSources() {
        assertFalse(result.inventory().isEmpty(), "資産インベントリが空でないこと");
        assertTrue(result.inventory().stream()
                        .anyMatch(a -> a.path().equals("cobol/SYK001.cbl")
                                && a.type().equals("PROGRAM")),
                () -> "cobol/SYK001.cbl がプログラムとしてインベントリに載ること: "
                        + result.inventory());
    }

    @Test
    void htmlReportIsGeneratedWithAllFourSections() {
        String html = result.html();
        assertTrue(html.startsWith("<!DOCTYPE html>"), "HTML文書であること");
        assertTrue(html.contains("資産インベントリ"), "資産インベントリ節を持つこと");
        assertTrue(html.contains("検出結果一覧"), "検出結果一覧節を持つこと");
        assertTrue(html.contains("呼出関係の要約"), "呼出関係の要約節を持つこと");
        assertTrue(html.contains("SQL指摘"), "SQL指摘節を持つこと");
        assertTrue(html.contains("R001") && html.contains("R004"),
                "7欠陥のルールIDが HTML に描画されること");
        assertTrue(html.contains("S004"), "SQL指摘 S004 が HTML に描画されること");
        assertTrue(html.contains("SYK002") && html.contains("SYK004"),
                "呼出辺のノードラベルが HTML に描画されること");
    }

    @Test
    void textReportIsGeneratedWithAllFourSections() {
        String text = result.text();
        assertTrue(text.contains("COBOL Insight 統合レポート"), "見出しを持つこと");
        assertTrue(text.contains("1. 資産インベントリ"), "資産インベントリ節を持つこと");
        assertTrue(text.contains("2. 検出結果一覧"), "検出結果一覧節を持つこと");
        assertTrue(text.contains("3. 呼出関係の要約"), "呼出関係の要約節を持つこと");
        assertTrue(text.contains("4. SQL指摘"), "SQL指摘節を持つこと");
        assertTrue(text.contains("R001") && text.contains("S006"),
                "検出とSQL指摘がテキストに描画されること");
        assertTrue(text.contains("終了コード: 2"), "終了コードを明記すること");
    }

    @Test
    void exitCodeIsErrorsBecauseLintDetectsErrorLevelDefects() {
        assertEquals(2, result.exitCode(),
                "統合レポートは ERROR レベル検出を含むため終了コード2で分岐すること");
    }
}
