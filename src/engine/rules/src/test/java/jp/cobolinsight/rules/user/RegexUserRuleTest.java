package jp.cobolinsight.rules.user;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.rules.SourceTextIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 正規表現による利用者定義ルールの当てはめの検証。桁と注記行の扱いを中心に確かめる。 */
class RegexUserRuleTest {

    /** 固定形式の桁を保った行を組む。1〜6桁は一連番号、7桁目は標識、8桁目から本体である。 */
    private static String fixed(String indicator, String body) {
        return "000100" + indicator + body;
    }

    private static AnalysisContext contextOf(Map<String, String> textByPath) {
        return AnalysisContext.of(List.of(), List.of(), List.of(), List.of(), Optional.empty(),
                Map.of(SourceTextIndex.class, new SourceTextIndex(textByPath)));
    }

    private static Rule ruleOf(String json) {
        UserRuleLoader.LoadResult result = UserRuleLoader.parse(json, "test");
        assertEquals(List.of(), result.errors());
        return result.rules().get(0);
    }

    private static final String GOBACK_RULE = """
            {"rules": [{"id": "U001", "name": "GOBACK の使用", "pattern": "GOBACK",
             "message": "GOBACK を使っている"}]}
            """;

    @Test
    void detectsMatchInProgramArea() {
        Rule rule = ruleOf(GOBACK_RULE);
        List<Finding> findings = rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed(" ", "    GOBACK."))));
        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals("U001", finding.ruleId());
        assertEquals(FindingLevel.WARNING, finding.level());
        assertEquals("cobol/A.cbl", finding.location().file());
        assertEquals(1, finding.location().line());
        assertEquals(12, finding.location().column());
    }

    /** 注記行(7桁目が * または /)は対象外とする。 */
    @Test
    void skipsCommentLines() {
        Rule rule = ruleOf(GOBACK_RULE);
        assertEquals(List.of(), rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed("*", "    GOBACK.")))));
        assertEquals(List.of(), rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed("/", "    GOBACK.")))));
    }

    /** 73桁目以降(識別領域)はソースの本体ではないため対象外とする。 */
    @Test
    void ignoresIdentificationArea() {
        Rule rule = ruleOf(GOBACK_RULE);
        String line = fixed(" ", " ".repeat(65) + "GOBACK");
        assertEquals(List.of(), rule.evaluate(contextOf(Map.of("cobol/A.cbl", line))));
    }

    @Test
    void wholeLineOptionCoversSequenceAndIdentificationAreas() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U002", "name": "識別欄の印", "pattern": "TODO",
                 "message": "TODO が残る", "wholeLine": true}]}
                """);
        String line = fixed("*", "    TODO 直す");
        assertEquals(1, rule.evaluate(contextOf(Map.of("cobol/A.cbl", line))).size());
    }

    @Test
    void ignoreCaseOptionMatchesLowerCase() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U003", "name": "小文字の goback", "pattern": "GOBACK",
                 "message": "M", "ignoreCase": true}]}
                """);
        assertEquals(1, rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed(" ", "    goback.")))).size());
    }

    @Test
    void excludePatternSuppressesMatchOnSameLine() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U004", "name": "除外つき", "pattern": "GOBACK",
                 "excludePattern": "NORMAL-END", "message": "M"}]}
                """);
        assertEquals(List.of(), rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed(" ", "    GOBACK. *> NORMAL-END")))));
    }

    @Test
    void targetsLimitTheFilesScanned() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U005", "name": "コピー句だけ", "pattern": "PIC",
                 "message": "M", "targets": ["COPYBOOK"]}]}
                """);
        List<Finding> findings = rule.evaluate(contextOf(Map.of(
                "cobol/A.cbl", fixed(" ", "01  WS-A PIC X."),
                "copy/B.cpy", fixed(" ", "01  WS-B PIC X."))));
        assertEquals(1, findings.size());
        assertEquals("copy/B.cpy", findings.get(0).location().file());
    }

    /** BMS は固定形式の桁割りを持たないため、行全体を対象とする。 */
    @Test
    void bmsTargetScansWholeLine() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U006", "name": "BMS の色指定", "pattern": "COLOR=RED",
                 "message": "M", "targets": ["BMS"]}]}
                """);
        assertEquals(1, rule.evaluate(contextOf(Map.of("bms/M.bms",
                "FLD1 DFHMDF POS=(1,1),COLOR=RED"))).size());
    }

    @Test
    void messagePlaceholderIsReplacedWithMatchedText() {
        Rule rule = ruleOf("""
                {"rules": [{"id": "U007", "name": "置換", "pattern": "MOVE\\\\s+\\\\S+",
                 "message": "禁止した書き方である: ${match}"}]}
                """);
        List<Finding> findings = rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed(" ", "    MOVE WS-A TO WS-B."))));
        assertEquals("禁止した書き方である: MOVE WS-A", findings.get(0).message());
    }

    /** 同じ行に複数一致しても報告は1件にする。行単位の検査であることを崩さないためである。 */
    @Test
    void reportsAtMostOneFindingPerLine() {
        Rule rule = ruleOf(GOBACK_RULE);
        List<Finding> findings = rule.evaluate(contextOf(Map.of("cobol/A.cbl",
                fixed(" ", "    GOBACK. GOBACK."))));
        assertEquals(1, findings.size());
    }

    @Test
    void reportsEveryMatchingLine() {
        Rule rule = ruleOf(GOBACK_RULE);
        String text = fixed(" ", "    GOBACK.") + "\n"
                + fixed(" ", "    DISPLAY WS-A.") + "\n"
                + fixed(" ", "    GOBACK.");
        List<Finding> findings = rule.evaluate(contextOf(Map.of("cobol/A.cbl", text)));
        assertEquals(List.of(1, 3), findings.stream().map(f -> f.location().line()).toList());
    }

    @Test
    void unknownExtensionIsNotScanned() {
        Rule rule = ruleOf(GOBACK_RULE);
        assertEquals(List.of(), rule.evaluate(contextOf(Map.of("jcl/J.jcl",
                "//STEP01 EXEC PGM=GOBACK"))));
    }

    @Test
    void missingSourceTextIndexYieldsNoFindings() {
        Rule rule = ruleOf(GOBACK_RULE);
        AnalysisContext empty = AnalysisContext.of(List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Map.of());
        assertEquals(List.of(), rule.evaluate(empty));
    }

    @Test
    void userRulesProvideNoFixSuggestion() {
        assertTrue(ruleOf(GOBACK_RULE).fixProducer().isEmpty());
    }
}
