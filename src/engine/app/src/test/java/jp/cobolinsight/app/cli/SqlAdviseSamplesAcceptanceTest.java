package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance regression test for sql-lint against the entire samples/ folder. Converts embedded
 * SQL to a SQL statement model via the SqlParser SPI, runs only the SQL advice rules (rules whose
 * id starts with "S"), and checks that detection matches the following: SYK006 has S004 fire on
 * the cursor declaration line (145) with no other detections. S001 and S002 are negative because
 * samples has no matching syntax. Since the highest SQL advice level is S004 (medium -> warning),
 * the exit code is 1.
 */
class SqlAdviseSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static SqlAdviseRunner.Result result;

    @BeforeAll
    static void adviseSamples() {
        result = SqlAdviseRunner.run(new SqlAdviseRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
    }

    private static List<Finding> byRule(String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    private static Set<String> fileLines(String ruleId) {
        return byRule(ruleId).stream()
                .map(f -> f.location().file() + ":" + f.location().line())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void allElevenCobolSourcesAreParsedWithoutFailure() {
        assertEquals(13, result.analyzed().size(),
                "COBOL 11本(encoding/の2本を含む)を解析すること");
        assertEquals(List.of(), byRule(Finding.PARSE_FAILURE_RULE_ID), "パース失敗が無いこと");
        assertEquals(List.of(), byRule("decode-failure"), "復号失敗が無いこと");
    }

    @Test
    void onlySqlAdviceRulesRunAndNoBugDetectionRulesLeak() {
        assertTrue(result.findings().stream().allMatch(f -> f.ruleId().startsWith("S")),
                "sql-lint の検出は全て SQL指摘(S接頭辞)であること: " + result.findings());
        assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().startsWith("R")),
                "バグ検出(R接頭辞)が混じらないこと");
        assertTrue(result.sarifJson().contains("\"id\":\"S001\""),
                "sql-lint の SARIF driver.rules に S接頭辞のルールが載ること");
        assertTrue(!result.sarifJson().contains("\"id\":\"R"),
                "sql-lint の SARIF driver.rules に R接頭辞のルールが載らないこと");
    }

    @Test
    void s001AndS002AreNegativeOnSamples() {
        assertEquals(List.of(), byRule("S001"), "S001(SELECT *)は samples に該当構文が無く陰性");
        assertEquals(List.of(), byRule("S002"),
                "S002(非SARGable述語・列への関数適用)は samples に該当構文が無く陰性");
    }

    @Test
    void s004FiresOnTheReadOnlyCursorDeclaration() {
        assertEquals(Set.of("cobol/SYK006.cbl:145"), fileLines("S004"),
                "S004は SYK006 のカーソル宣言(FOR READ ONLY欠如)1件を検出すること");
        assertTrue(byRule("S004").stream().allMatch(f -> f.level() == FindingLevel.WARNING),
                "S004(中)は警告レベルであること");
    }

    /** S004 is the only rule positive on samples. Also checks that withdrawn S005/S006 have not come back. */
    @Test
    void s004IsTheOnlyAdviceOnSamples() {
        assertEquals(Set.of("S004"), result.findings().stream().map(Finding::ruleId)
                .collect(Collectors.toCollection(TreeSet::new)),
                () -> "samples の SQL指摘は S004 のみであること: " + result.findings());
    }

    @Test
    void exitCodeIsWarningsBecauseHighestAdviceLevelIsWarning() {
        assertEquals(0, result.countByLevel(FindingLevel.ERROR),
                "samples の SQL指摘に error レベル(S002)は無いこと");
        assertTrue(result.countByLevel(FindingLevel.WARNING) > 0, "S004(警告)を含むこと");
        assertEquals(1, result.exitCode(), "警告あり=1で分岐すること");
    }

    @Test
    void sarifOutputIsSarif210AndDeterministic() {
        String sarif = result.sarifJson();
        assertTrue(sarif.contains("\"version\":\"2.1.0\""), "SARIF 2.1.0であること");
        assertTrue(sarif.contains("sarif-schema-2.1.0"), "スキーマURIを持つこと");
        long resultCount = sarif.split("\"ruleId\":", -1).length - 1;
        assertEquals(result.findings().size(), resultCount,
                "SARIFのresultsがfindings全件を含むこと");
        assertTrue(sarif.contains("\"uri\":\"cobol/SYK006.cbl\""),
                "位置のURIが入力フォルダ相対のスラッシュ区切りであること");

        SqlAdviseRunner.Result second = SqlAdviseRunner.run(new SqlAdviseRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(sarif, second.sarifJson(), "同一入力で同一のSARIFテキストになること(決定論)");
    }
}
