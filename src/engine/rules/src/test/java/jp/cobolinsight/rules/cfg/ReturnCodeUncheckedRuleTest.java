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

/** R029 synthetic fixture verification for an unchecked RETURN-CODE after CALL. */
class ReturnCodeUncheckedRuleTest {

    @TempDir
    Path tempDir;

    // Design that references RETURN-CODE. CALL 'SUBA' is checked but CALL 'SUBB' is not.
    private static final String MIXED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX029.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           CALL 'SUBA'",
            "           IF RETURN-CODE = 0",
            "               CONTINUE",
            "           END-IF",
            "           CALL 'SUBB'",
            "           DISPLAY 'DONE'",
            "           GOBACK.",
            "");

    // A program that never references RETURN-CODE at all. In this case an unchecked CALL, if any, is out of scope.
    private static final String NO_RC_REFERENCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX029B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           CALL 'SUBA'",
            "           DISPLAY 'DONE'",
            "           GOBACK.",
            "");

    @Test
    void detectsUncheckedCallWhenProgramUsesReturnCode() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX029.cbl", MIXED);
        List<Finding> findings = new ReturnCodeUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), MIXED)));
        assertEquals(1, findings.size(),
                () -> "検査済みCALLを除き、未検査のCALL 'SUBB'1件を検出すること: " + findings);
        assertEquals("R029", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(12, findings.get(0).location().line());
    }

    @Test
    void ignoresProgramNotUsingReturnCode() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX029B.cbl", NO_RC_REFERENCE);
        List<Finding> findings = new ReturnCodeUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), NO_RC_REFERENCE)));
        assertEquals(List.of(), findings,
                () -> "RETURN-CODEを参照しないプログラムは対象外: " + findings);
    }
}
