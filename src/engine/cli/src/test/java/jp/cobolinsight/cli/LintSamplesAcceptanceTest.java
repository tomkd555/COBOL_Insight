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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * samples/ 全体の lint 受入回帰テスト。構文段階(SYNTAX)・制御フロー段階
 * (CONTROL_FLOW)・データフロー段階(DATA_FLOW)の3段階を実行し、期待結果.md の欠陥をファイル・行番号
 * どおりに検出し、samplesに意図的欠陥の無いルールが誤検出を出さないことを突合する。構文段階のR002
 * (未使用変数)・R008(THRUなし単独段落PERFORM)、制御フロー段階のR007/R011/R017/R018/R021/R022/R031、
 * データフロー段階のR001/R003/R004/R005(期待結果.md No.1/2/3/5/9/13/14)の検出と、samplesがERRORレベルの検出を
 * 含むため終了コードが2であることを確認する。lint は rule id が "R" で始まるルールのみを実行し、
 * SQL指摘(S接頭辞)を除外する。R017は path-sensitive な忠実実装のため付随検出を許容し、
 * 必須2件の包含とOPEN/CLOSE非検出のみを表明する。
 *
 * <p>正解の出所は samples/期待結果.md(12種別18件)。この18件は「意図的に混入した欠陥15件」と
 * 「CICS関連の検出3件」の合計である。データフロー解析が拾うのは18件中の7件
 * (No.1/2/3/5/9/13/14)。
 */
class LintSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static LintRunner.Result result;

    @BeforeAll
    static void lintSamples() {
        result = LintRunner.run(new LintRunner.Options(SAMPLES,
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
    }

    private static List<Finding> byRule(String ruleId) {
        return result.findings().stream().filter(f -> f.ruleId().equals(ruleId)).toList();
    }

    /** 指定ルールの検出位置を "cobol/ファイル:行" 集合として返す。 */
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
        assertEquals(11, result.analyzed().size(),
                "COBOL 11本(encoding/の2本を含む)を解析すること");
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
        // 意図的欠陥の無いルール。R012/R015/R016/R020/R025/R027/R028 はデータフロー段階(DATA_FLOW)のうち
        // samplesに該当欠陥が無いもので、データフロー解析でも偽陽性を出さないことを担保する。
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
                "R001は未初期化変数の参照2件(期待結果.md No.1,9)を検出すること");
        assertTrue(allLevel("R001", FindingLevel.ERROR), "R001は全件ERRORであること");
    }

    @Test
    void r003DetectsExactlyTheTwoMoveTruncations() {
        assertEquals(Set.of("cobol/SYK001.cbl:128", "cobol/SYK002.cbl:118"), fileLines("R003"),
                "R003はMOVEでの桁落ち・切り捨て2件(期待結果.md No.2,5)を検出すること");
        assertTrue(allLevel("R003", FindingLevel.ERROR), "R003は全件ERRORであること");
    }

    @Test
    void r005DetectsExactlyTheTwoOutOfRangeSubscripts() {
        assertEquals(Set.of("cobol/SYK001.cbl:114", "cobol/SYK006.cbl:139"), fileLines("R005"),
                "R005はOCCURS範囲外になり得る添字2件(期待結果.md No.3,13)を検出すること");
        assertTrue(allLevel("R005", FindingLevel.ERROR), "R005は全件ERRORであること");
    }

    @Test
    void r004DetectsExactlyTheOnSizeErrorMissingCompute() {
        assertEquals(Set.of("cobol/SYK007.cbl:79"), fileLines("R004"),
                "R004はON SIZE ERROR欠如の演算1件(期待結果.md No.14)を検出すること");
        assertTrue(allLevel("R004", FindingLevel.ERROR), "R004は全件ERRORであること");
    }

    @Test
    void lintRunsOnlyRPrefixedRulesAndExcludesSqlAdviceRules() {
        assertTrue(result.findings().stream().noneMatch(f -> f.ruleId().startsWith("S")),
                "lintの検出にSQL指摘(S接頭辞)が混じらないこと");
        assertFalse(result.sarifJson().contains("\"id\":\"S"),
                "lintのSARIF driver.rules にS接頭辞のSQL指摘ルールが載らないこと");
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
    void exitCodeIsErrorsBecauseSamplesHaveErrorLevelFindings() {
        assertTrue(result.countByLevel(FindingLevel.ERROR) > 0,
                "samplesはERRORレベルの検出(R007/R017/R018/R021/R031)を含むこと");
        assertEquals(2, result.exitCode(), "エラーあり=2で分岐すること");
    }

    @Test
    void r007DetectsExactlyTheGoToIntoThruRange() {
        assertEquals(Set.of("cobol/SYK002.cbl:124"), fileLines("R007"),
                "R007は期待結果.md No.7のGO TO 1件だけ検出すること");
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
     * R017は path-sensitive な忠実実装のため付随検出を許容し、集合の完全一致は表明しない。
     * 必須とする正解2件(SYK001:85 READ・SYK002:130 REWRITE)の包含と、
     * 全件ERROR、およびOPEN/CLOSE行を検出しないことのみを表明する。
     * 実測の検出は9件(全件error): SYK001:85/126/130、SYK002:73/107/130、SYK006:87/172、
     * SYK007:60。必須2件を除く7件は、FILE STATUS変数が後続で参照されない構造同一の真の未検査
     * (record-access I/O)であり忠実実装が検出するため、集合固定はしない。
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
                List.of(SAMPLES.resolve("copybook")), Map.of(), Set.of()));
        assertEquals(sarif, second.sarifJson(), "同一入力で同一のSARIFテキストになること(決定論)");
    }
}
