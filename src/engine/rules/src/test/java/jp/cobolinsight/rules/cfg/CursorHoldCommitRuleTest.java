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

/** R039 synthetic fixtures: a COMMIT between OPEN and CLOSE, with and without WITH HOLD. */
class CursorHoldCommitRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String name, String declare, String betweenOpenAndClose) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + name + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-COL1 PIC X(08).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               DECLARE MYCUR CURSOR " + declare,
                "                   SELECT COL1 FROM MYTAB",
                "           END-EXEC",
                "           EXEC SQL OPEN MYCUR END-EXEC",
                "           PERFORM 1000-FETCH",
                betweenOpenAndClose,
                "           EXEC SQL CLOSE MYCUR END-EXEC",
                "           GOBACK.",
                "       1000-FETCH.",
                "           EXEC SQL FETCH MYCUR INTO :WS-COL1 END-EXEC.",
                "");
    }

    private static final String COMMITS =
            program("FIX039", "FOR", "           EXEC SQL COMMIT END-EXEC");
    private static final String HELD =
            program("FIX039B", "WITH HOLD FOR", "           EXEC SQL COMMIT END-EXEC");
    private static final String NO_COMMIT =
            program("FIX039C", "FOR", "           CONTINUE");

    private static List<Finding> evaluate(CobolSemanticModel model, String source) {
        return new CursorHoldCommitRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), source)));
    }

    @Test
    void detectsACommitTakenWhileACursorWithoutHoldIsOpen() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX039.cbl", COMMITS);
        List<Finding> findings = evaluate(model, COMMITS);
        assertEquals(1, findings.size(), () -> "WITH HOLD なしの COMMIT 1件: " + findings);
        assertEquals("R039", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("MYCUR"), findings.get(0).message());
        assertEquals(13, findings.get(0).location().line(), "OPEN の行で報告すること");
        assertEquals(1, findings.get(0).codeFlows().size(), "同期点までの経路を持つこと");
    }

    @Test
    void ignoresACursorDeclaredWithHold() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX039B.cbl", HELD);
        assertEquals(List.of(), evaluate(model, HELD));
    }

    @Test
    void ignoresACursorWithNoSyncPointWhileItIsOpen() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX039C.cbl", NO_COMMIT);
        assertEquals(List.of(), evaluate(model, NO_COMMIT));
    }
}
