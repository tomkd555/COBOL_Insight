package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R007 PERFORM THRU の範囲へ割り込む GO TO の合成fixture検証。 */
class PerformThruInterruptGoToRuleTest {

    @TempDir
    Path tempDir;

    /** 範囲外の 0000-MAIN から、入口 CALC-START を経ずに範囲内の CALC-STEP2 へ飛ぶ。 */
    private static final String INTERRUPTS = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX007.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           PERFORM CALC-START THRU CALC-EXIT",
            /*  9 */ "           GO TO CALC-STEP2.",
            /* 10 */ "       CALC-START.",
            /* 11 */ "           MOVE 1 TO WS-D.",
            /* 12 */ "       CALC-STEP2.",
            /* 13 */ "           MOVE 2 TO WS-D.",
            /* 14 */ "       CALC-EXIT.",
            /* 15 */ "           EXIT.",
            "");

    /** 同じ形だが、飛び先が範囲の入口段落そのもの。割り込みではない。 */
    private static final String ENTERS_AT_THE_ENTRY = INTERRUPTS
            .replace("PROGRAM-ID. FIX007.", "PROGRAM-ID. FIX007B.")
            .replace("GO TO CALC-STEP2.", "GO TO CALC-START.");

    private List<Finding> run(String fileName, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, fileName, text);
        return new PerformThruInterruptGoToRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsGoToJumpingIntoTheMiddleOfAThruRange() {
        List<Finding> findings = run("FIX007.cbl", INTERRUPTS);
        assertEquals(1, findings.size(), () -> "範囲への割り込み1件を検出すること: " + findings);
        assertEquals("R007", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(9, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("CALC-STEP2"), findings.get(0).message());
    }

    @Test
    void ignoresGoToTheRangeEntry() {
        assertEquals(List.of(), run("FIX007B.cbl", ENTERS_AT_THE_ENTRY),
                "入口段落へ飛ぶ GO TO は割り込みではない");
    }
}
