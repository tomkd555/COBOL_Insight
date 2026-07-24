package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
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
 * samples/ 全体の sql-advise 受入回帰テスト。埋め込みSQLを SqlParser SPI で写像し、SQL助言
 * (id が "S" のルール)のみを実行して、m5-spec §5.2 の発火予測どおりに検出することを突合する。
 * SYK006 は S004・S006 をカーソル宣言行(145)で、S005 を SELECT INTO(96)とカーソル宣言(145)で
 * 検出し、SYK007 は S005 を SELECT INTO(69)で検出する。S001〜S003 は samples に該当構文が無いため
 * 陰性である。SQL助言の最上位は S004(中→警告)のため終了コードは1になる。
 */
class SqlAdviseSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private static SqlAdviseRunner.Result result;

    @BeforeAll
    static void adviseSamples() {
        result = SqlAdviseRunner.run(new SqlAdviseRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
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
    void allNineCobolSourcesAreParsedWithoutFailure() {
        assertEquals(9, result.analyzed().size(), "COBOL 9本を解析すること");
        assertEquals(List.of(), byRule(Finding.PARSE_FAILURE_RULE_ID), "パース失敗が無いこと");
        assertEquals(List.of(), byRule("decode-failure"), "復号失敗が無いこと");
    }

    @Test
    void onlySqlAdviceRulesRunAndNoBugDetectionRulesLeak() {
        assertTrue(result.findings().stream().allMatch(f -> f.ruleId().startsWith("S")),
                "sql-advise の検出は全て SQL助言(S接頭辞)であること(裁定A5): " + result.findings());
        assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().startsWith("R")),
                "バグ検出(R接頭辞)が混じらないこと(裁定A5)");
        assertTrue(result.sarifJson().contains("\"id\":\"S001\""),
                "sql-advise の SARIF driver.rules に S接頭辞のルールが載ること");
        assertTrue(!result.sarifJson().contains("\"id\":\"R"),
                "sql-advise の SARIF driver.rules に R接頭辞のルールが載らないこと(裁定A5)");
    }

    @Test
    void s001ThroughS003AreNegativeOnSamples() {
        assertEquals(List.of(), byRule("S001"), "S001(SELECT *)は samples に該当構文が無く陰性");
        assertEquals(List.of(), byRule("S002"), "S002(非SARGable述語)は陰性");
        assertEquals(List.of(), byRule("S003"), "S003(列への関数適用・CAST)は陰性");
    }

    @Test
    void s004FiresOnTheReadOnlyCursorDeclaration() {
        assertEquals(Set.of("cobol/SYK006.cbl:145"), fileLines("S004"),
                "S004は SYK006 のカーソル宣言(FOR READ ONLY欠如)1件を検出すること");
        assertTrue(byRule("S004").stream().allMatch(f -> f.level() == FindingLevel.WARNING),
                "S004(中)は警告レベルであること");
    }

    @Test
    void s005FiresOnEverySelectAndCursorDeclaration() {
        assertEquals(Set.of("cobol/SYK006.cbl:96", "cobol/SYK006.cbl:145", "cobol/SYK007.cbl:69"),
                fileLines("S005"),
                "S005は SELECT INTO(SYK006:96・SYK007:69)とカーソル宣言(SYK006:145)へ一律発火すること");
        assertTrue(byRule("S005").stream().allMatch(f -> f.level() == FindingLevel.NOTE),
                "S005(低)は注記レベルであること");
    }

    @Test
    void s006FiresOnTheCursorDeclarationMissingOptimizeFor() {
        assertEquals(Set.of("cobol/SYK006.cbl:145"), fileLines("S006"),
                "S006は SYK006 のカーソル宣言(OPTIMIZE FOR欠如)1件を検出すること");
        assertTrue(byRule("S006").stream().allMatch(f -> f.level() == FindingLevel.NOTE),
                "S006(低)は注記レベルであること");
    }

    @Test
    void exitCodeIsWarningsBecauseHighestAdviceLevelIsWarning() {
        assertEquals(0, result.countByLevel(FindingLevel.ERROR),
                "samples の SQL助言に error レベル(S002/S003)は無いこと");
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
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
        assertEquals(sarif, second.sarifJson(), "同一入力で同一のSARIFテキストになること(決定論)");
    }
}
