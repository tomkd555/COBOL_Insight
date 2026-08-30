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

/** R007 synthetic fixture verification for a GO TO that interrupts into a PERFORM THRU range. */
class PerformThruInterruptGoToRuleTest {

    @TempDir
    Path tempDir;

    /** Jumps from 0000-MAIN, outside the range, to CALC-STEP2 inside the range without going through the entry CALC-START. */
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

    /** Same shape, but the jump target is the range's entry paragraph itself. Not an interruption. */
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
