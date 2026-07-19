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
 * M3受入回帰テスト(05_開発計画.md §3.3)。samples/ 全体の lint が、M3対応の欠陥
 * (期待結果.md No.8 の使われない変数)をファイル・行番号どおりに検出し、samplesに意図的欠陥の
 * 無いルールが誤検出を出さないことを突合する。R008は仕様(THRUなしの単独段落PERFORMを検出)
 * どおりの検出が全件出ること、およびそれ以外の検出が無いことを行番号の完全一致で確認する。
 */
class LintSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private static LintRunner.Result result;

    @BeforeAll
    static void lintSamples() {
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
    }

    private static List<Finding> byRule(String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    @Test
    void allNineCobolSourcesAreParsedWithoutFailure() {
        assertEquals(9, result.analyzed().size(), "COBOL 9本を解析すること");
        assertEquals(List.of(), byRule(Finding.PARSE_FAILURE_RULE_ID), "パース失敗が無いこと");
        assertEquals(List.of(), byRule("decode-failure"), "復号失敗が無いこと");
    }

    @Test
    void r002DetectsExactlyTheExpectedUnusedVariable() {
        List<Finding> findings = byRule("R002");
        assertEquals(1, findings.size(), () -> "R002は期待結果.md No.8の1件だけ検出すること: "
                + findings);
        Finding finding = findings.get(0);
        assertEquals("cobol/SYK003.cbl", finding.location().file());
        assertEquals(20, finding.location().line(), "期待結果.md No.8: 宣言行20で検出すること");
        assertEquals(FindingLevel.NOTE, finding.level());
        assertTrue(finding.message().contains("WS-旧チェック方式件数"), finding.message());
    }

    @Test
    void rulesWithoutIntendedDefectsProduceNoFindingsOnSamples() {
        for (String ruleId : List.of("R006", "R013", "R023", "R024", "R026")) {
            assertEquals(List.of(), byRule(ruleId),
                    ruleId + " はsamplesに該当欠陥が無いため検出しないこと");
        }
    }

    @Test
    void r008DetectsEveryThruLessSingleParagraphPerformAndNothingElse() {
        Map<String, Set<Integer>> expected = Map.of(
                "cobol/SYK001.cbl", new TreeSet<>(Set.of(73, 74, 75, 82, 92, 93, 94, 99, 101)),
                "cobol/SYK002.cbl", new TreeSet<>(Set.of(61, 62, 63, 64, 79, 81, 83, 85, 96)),
                "cobol/SYK004.cbl", new TreeSet<>(Set.of(31, 32)),
                "cobol/SYK005.cbl", new TreeSet<>(Set.of(26, 28, 30)),
                "cobol/SYK006.cbl", new TreeSet<>(Set.of(75, 76, 77, 78, 84, 104, 106, 108, 110, 153)),
                "cobol/SYK007.cbl", new TreeSet<>(Set.of(50, 51, 52, 57, 74, 75, 76)),
                "cobol/SYK008.cbl", new TreeSet<>(Set.of(39, 41, 43)));
        Map<String, Set<Integer>> actual = byRule("R008").stream()
                .collect(Collectors.groupingBy(f -> f.location().file(),
                        Collectors.mapping(f -> f.location().line(),
                                Collectors.toCollection(TreeSet::new))));
        assertEquals(expected, actual,
                "R008はTHRUなし単独段落PERFORMの全件を行番号どおり検出し、それ以外を検出しないこと");
        assertTrue(byRule("R008").stream().allMatch(f -> f.level() == FindingLevel.WARNING));
    }

    @Test
    void exitCodeIsWarningsBecauseSamplesHaveNoErrorLevelFindings() {
        assertEquals(0, result.countByLevel(FindingLevel.ERROR));
        assertTrue(result.countByLevel(FindingLevel.WARNING) > 0);
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
        assertTrue(sarif.contains("\"uri\":\"cobol/SYK003.cbl\""),
                "位置のURIが入力フォルダ相対のスラッシュ区切りであること");

        LintRunner.Result second = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
        assertEquals(sarif, second.sarifJson(), "同一入力で同一のSARIFテキストになること(決定論)");
    }
}
