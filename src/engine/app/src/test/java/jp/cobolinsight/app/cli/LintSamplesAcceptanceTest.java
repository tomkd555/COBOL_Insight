package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.rules.RuleSet;
import jp.cobolinsight.rules.RulesFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 * Acceptance regression test for lint over the entire samples/. Runs the three stages -
 * SYNTAX, CONTROL_FLOW, and DATA_FLOW - and checks that the defects in expected-results.md are
 * detected by file and line number, and that rules with no intended defect in samples produce no
 * false positives. Covers detection of R002 (unused variable) in the SYNTAX stage, R008
 * (single-paragraph PERFORM without THRU, disabled by default so it is enabled via configuration
 * to verify), R007/R011/R017/R018/R021/R022/R031 in the CONTROL_FLOW stage, and
 * R001/R003/R004/R005 in the DATA_FLOW stage (expected-results.md No.1/2/3/5/9/13/14), and
 * confirms the exit code is 2 because samples contains ERROR-level findings. lint runs only
 * rules whose rule id starts with "R" and excludes SQL advice (S-prefixed). R017 is a
 * path-sensitive, faithful implementation, so incidental detections are tolerated; only the
 * inclusion of the two mandatory findings and the absence of OPEN/CLOSE detections is asserted.
 *
 * <p>The ground truth comes from samples/expected-results.md (18 findings across 12 categories).
 * These 18 findings are the sum of "15 intentionally injected defects" and
 * "3 CICS-related detections". Data-flow analysis picks up 7 of the 18
 * (No.1/2/3/5/9/13/14).
 */
class LintSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static LintRunner.Result result;

    @BeforeAll
    static void lintSamples() {
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of()));
    }

    private static List<Finding> byRule(String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    /** Returns the detection locations of the given rule as a set of "cobol/file:line" strings. */
    private static Set<String> fileLines(String ruleId) {
        return byRule(ruleId).stream()
                .map(f -> f.location().file() + ":" + f.location().line())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean allLevel(String ruleId, FindingLevel level) {
        return !byRule(ruleId).isEmpty()
                && byRule(ruleId).stream().allMatch(f -> f.level() == level);
    }

    @Test
    void allElevenCobolSourcesAreParsedWithoutFailure() {
        assertEquals(13, result.analyzed().size(),
                "COBOL 11本(encoding/の2本を含む)を解析すること");
        assertEquals(List.of(), byRule(Finding.PARSE_FAILURE_RULE_ID), "パース失敗が無いこと");
        assertEquals(List.of(), byRule("decode-failure"), "復号失敗が無いこと");
    }

    @Test
    void r002DetectsExactlyTheExpectedUnusedVariable() {
        List<Finding> findings = byRule("R002");
        assertEquals(1, findings.size(), () -> "R002はexpected-results.md No.8の1件だけ検出すること: "
                + findings);
        Finding finding = findings.get(0);
        assertEquals("cobol/SYK003.cbl", finding.location().file());
        assertEquals(20, finding.location().line(), "expected-results.md No.8: 宣言行20で検出すること");
        assertEquals(FindingLevel.NOTE, finding.level());
        assertTrue(finding.message().contains("WS-旧チェック方式件数"), finding.message());
    }

    @Test
    void rulesWithoutIntendedDefectsProduceNoFindingsOnSamples() {
        // Rules with no intended defect. R012/R015/R016/R020/R025/R027/R028 are DATA_FLOW-stage
        // rules with no corresponding defect in samples, ensuring data-flow analysis also produces no false positives.
        for (String ruleId : List.of("R006", "R009", "R010", "R013", "R014", "R019",
                "R023", "R024", "R026", "R029", "R030",
                "R012", "R015", "R016", "R020", "R025", "R027", "R028")) {
            assertEquals(List.of(), byRule(ruleId),
                    ruleId + " はsamplesに該当欠陥が無いため検出しないこと");
        }
    }

    @Test
    void r001DetectsExactlyTheTwoUninitializedVariableReferences() {
        assertEquals(Set.of("cobol/SYK001.cbl:121", "cobol/SYK004.cbl:41"), fileLines("R001"),
                "R001は未初期化変数の参照2件(expected-results.md No.1,9)を検出すること");
        assertTrue(allLevel("R001", FindingLevel.ERROR), "R001は全件ERRORであること");
    }

    @Test
    void r003DetectsExactlyTheTwoMoveTruncations() {
        assertEquals(Set.of("cobol/SYK001.cbl:128", "cobol/SYK002.cbl:118"), fileLines("R003"),
                "R003はMOVEでの桁落ち・切り捨て2件(expected-results.md No.2,5)を検出すること");
        assertTrue(allLevel("R003", FindingLevel.ERROR), "R003は全件ERRORであること");
    }

    @Test
    void r005DetectsExactlyTheTwoOutOfRangeSubscripts() {
        assertEquals(Set.of("cobol/SYK001.cbl:114", "cobol/SYK006.cbl:139"), fileLines("R005"),
                "R005はOCCURS範囲外になり得る添字2件(expected-results.md No.3,13)を検出すること");
        assertTrue(allLevel("R005", FindingLevel.ERROR), "R005は全件ERRORであること");
    }

    @Test
    void r004DetectsExactlyTheOnSizeErrorMissingCompute() {
        assertEquals(Set.of("cobol/SYK007.cbl:79"), fileLines("R004"),
                "R004はON SIZE ERROR欠如の演算1件(expected-results.md No.14)を検出すること");
        assertTrue(allLevel("R004", FindingLevel.ERROR), "R004は全件ERRORであること");
    }

    @Test
    void lintRunsOnlyRPrefixedRulesAndExcludesSqlAdviceRules() {
        assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().startsWith("S")),
                "lintの検出にSQL指摘(S接頭辞)が混じらないこと");
        assertFalse(result.sarifJson().contains("\"id\":\"S"),
                "lintのSARIF driver.rules にS接頭辞のSQL指摘ルールが載らないこと");
    }

    /**
     * R008 is disabled by default (based on measurements in corpus/rule-hits.md). Checks both that
     * the default run produces zero findings, and that enabling it via configuration detects every
     * single-paragraph PERFORM without THRU, matching line numbers exactly.
     */
    @Test
    void r008DetectsEveryThruLessSingleParagraphPerformAndNothingElse() {
        assertEquals(List.of(), byRule("R008"), "R008は既定で無効のため既定の走行では出ないこと");
        LintRunner.Result withR008 = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(),
                RuleSet.load(new RulesFile(
                        Map.of("R008", new RulesFile.RuleOverride(true, null)),
                        List.of(), List.of()))));
        Map<String, Set<Integer>> expected = Map.of(
                "cobol/SYK001.cbl", new TreeSet<>(Set.of(73, 74, 75, 82, 92, 93, 94, 99, 101)),
                "cobol/SYK002.cbl", new TreeSet<>(Set.of(61, 62, 63, 64, 79, 81, 83, 85, 96)),
                "cobol/SYK004.cbl", new TreeSet<>(Set.of(31, 32)),
                "cobol/SYK005.cbl", new TreeSet<>(Set.of(26, 28, 30)),
                "cobol/SYK006.cbl", new TreeSet<>(Set.of(75, 76, 77, 78, 84, 104, 106, 108, 110, 153)),
                "cobol/SYK007.cbl", new TreeSet<>(Set.of(50, 51, 52, 57, 74, 75, 76)),
                "cobol/SYK008.cbl", new TreeSet<>(Set.of(39, 41, 43)));
        List<Finding> findings = withR008.findings().stream()
                .filter(f -> f.ruleId().equals("R008")).toList();
        Map<String, Set<Integer>> actual = findings.stream()
                .collect(Collectors.groupingBy(f -> f.location().file(),
                        Collectors.mapping(f -> f.location().line(),
                                Collectors.toCollection(TreeSet::new))));
        assertEquals(expected, actual,
                "R008はTHRUなし単独段落PERFORMの全件を行番号どおり検出し、それ以外を検出しないこと");
        assertTrue(findings.stream().allMatch(f -> f.level() == FindingLevel.WARNING));
    }

    @Test
    void exitCodeIsErrorsBecauseSamplesHaveErrorLevelFindings() {
        assertTrue(result.countByLevel(FindingLevel.ERROR) > 0,
                "samplesはERRORレベルの検出(R007/R017/R018/R021/R031)を含むこと");
        assertEquals(2, result.exitCode(), "エラーあり=2で分岐すること");
    }

    @Test
    void r007DetectsExactlyTheGoToIntoThruRange() {
        assertEquals(Set.of("cobol/SYK002.cbl:124"), fileLines("R007"),
                "R007はexpected-results.md No.7のGO TO 1件だけ検出すること");
        assertTrue(allLevel("R007", FindingLevel.ERROR));
    }

    @Test
    void r011DetectsExactlyTheUnreachableAndUnusedParagraphs() {
        assertEquals(Set.of("cobol/SYK004.cbl:47", "cobol/SYK005.cbl:40"), fileLines("R011"),
                "R011は未使用段落(SYK004:47)と到達不能コード(SYK005:40)の2件を検出すること");
        assertTrue(allLevel("R011", FindingLevel.WARNING));
    }

    @Test
    void r018DetectsExactlyTheUncheckedDataDml() {
        assertEquals(Set.of("cobol/SYK006.cbl:119", "cobol/SYK007.cbl:89"), fileLines("R018"),
                "R018は未検査のデータ変更DML2件(END-EXEC行)を検出すること");
        assertTrue(allLevel("R018", FindingLevel.ERROR));
    }

    @Test
    void r021DetectsExactlyTheCicsWithoutResp() {
        assertEquals(Set.of("cobol/SYK008.cbl:38"), fileLines("R021"),
                "R021はRESP/RESP2を持たないEXEC CICS 1件(END-EXEC行)を検出すること");
        assertTrue(allLevel("R021", FindingLevel.ERROR));
    }

    @Test
    void r022DetectsExactlyTheCicsProgramWithoutReturn() {
        assertEquals(Set.of("cobol/SYK009.cbl:21"), fileLines("R022"),
                "R022はRETURN TRANSIDを持たないCICS参加プログラム1件(GOBACK行)を検出すること");
        assertTrue(allLevel("R022", FindingLevel.WARNING));
    }

    @Test
    void r031DetectsExactlyTheSendMapWithoutBmsField() {
        assertEquals(Set.of("cobol/SYK008.cbl:56"), fileLines("R031"),
                "R031はBMSに存在しないマップへのSEND MAP 1件(動詞行)を検出すること");
        assertTrue(allLevel("R031", FindingLevel.ERROR));
    }

    /**
     * R017 is a path-sensitive, faithful implementation, so incidental detections are tolerated and
     * exact set equality is not asserted. Only the inclusion of the two mandatory correct findings
     * (SYK001:85 READ, SYK002:130 REWRITE), that all findings are ERROR level, and that OPEN/CLOSE
     * lines are never detected are asserted.
     * The actual detection count is 9 (all error): SYK001:85/126/130, SYK002:73/107/130, SYK006:87/172,
     * SYK007:60. The 7 findings beyond the two mandatory ones are genuine unchecked cases with the
     * same structure - a FILE STATUS variable that is never referenced afterward
     * (record-access I/O) - which the faithful implementation detects, so the set is not fixed.
     */
    @Test
    void r017ContainsMandatoryUncheckedRecordIoAndNeverOpenOrClose() {
        Set<String> detected = fileLines("R017");
        assertTrue(detected.contains("cobol/SYK001.cbl:85"),
                "R017はSYK001:85(READ)を含むこと: " + detected);
        assertTrue(detected.contains("cobol/SYK002.cbl:130"),
                "R017はSYK002:130(REWRITE)を含むこと: " + detected);
        assertTrue(allLevel("R017", FindingLevel.ERROR), "R017は全件ERRORであること");

        Set<String> openCloseLines = Set.of(
                "cobol/SYK001.cbl:79", "cobol/SYK001.cbl:80", "cobol/SYK001.cbl:81",
                "cobol/SYK001.cbl:134", "cobol/SYK001.cbl:135", "cobol/SYK001.cbl:136",
                "cobol/SYK002.cbl:68", "cobol/SYK002.cbl:69",
                "cobol/SYK002.cbl:136", "cobol/SYK002.cbl:137",
                "cobol/SYK006.cbl:82", "cobol/SYK006.cbl:83", "cobol/SYK006.cbl:151",
                "cobol/SYK006.cbl:156", "cobol/SYK006.cbl:176", "cobol/SYK006.cbl:177",
                "cobol/SYK007.cbl:56", "cobol/SYK007.cbl:92");
        assertTrue(detected.stream().noneMatch(openCloseLines::contains),
                "R017はOPEN/CLOSE行を検出しないこと: " + detected);
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
                List.of(SAMPLES.resolve("copybook")), Map.of()));
        assertEquals(sarif, second.sarifJson(), "同一入力で同一のSARIFテキストになること(決定論)");
    }
}
