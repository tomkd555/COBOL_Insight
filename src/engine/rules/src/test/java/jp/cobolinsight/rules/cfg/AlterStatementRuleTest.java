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

/** R010 ALTER文の合成fixture検証。 */
class AlterStatementRuleTest {

    @TempDir
    Path tempDir;

    private static final String WITH_ALTER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX010.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           ALTER 1000-GATE TO PROCEED TO 2000-ALT",
            "           PERFORM 1000-GATE",
            "           GOBACK.",
            "       1000-GATE.",
            "           GO TO 2000-ALT.",
            "       2000-ALT.",
            "           MOVE 1 TO WS-D.",
            "");

    private static final String WITHOUT_ALTER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX010B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           MOVE 1 TO WS-D",
            "           GOBACK.",
            "");

    @Test
    void detectsAlterStatement() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX010.cbl", WITH_ALTER);
        List<Finding> findings = new AlterStatementRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WITH_ALTER)));
        assertEquals(1, findings.size(), () -> "ALTER文1件を検出すること: " + findings);
        assertEquals("R010", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
    }

    @Test
    void detectsNothingWithoutAlter() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX010B.cbl", WITHOUT_ALTER);
        List<Finding> findings = new AlterStatementRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WITHOUT_ALTER)));
        assertEquals(List.of(), findings);
    }
}
