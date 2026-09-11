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

/** R043 synthetic fixture verification for a HANDLE ABEND exit with no SYNCPOINT ROLLBACK. */
class HandleAbendRollbackMissingRuleTest {

    @TempDir
    Path tempDir;

    /** The exit paragraph only sends a message and returns; the unit of work is never backed out. */
    private static final String NO_ROLLBACK = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX043.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-REC PIC X(80).",
            /*  6 */ "       01  WS-KEY PIC X(08).",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN.",
            /*  9 */ "           EXEC CICS HANDLE ABEND LABEL(9500-ABEND)",
            /* 10 */ "           END-EXEC",
            /* 11 */ "           EXEC CICS READ FILE('FLFILE') INTO(WS-REC)",
            /* 12 */ "                RIDFLD(WS-KEY)",
            /* 13 */ "           END-EXEC",
            /* 14 */ "           GOBACK.",
            /* 15 */ "       9500-ABEND.",
            /* 16 */ "           EXEC CICS RETURN",
            /* 17 */ "           END-EXEC.",
            "");

    /** The exit paragraph rolls the unit of work back before it returns. */
    private static final String WITH_ROLLBACK = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX043B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-REC PIC X(80).",
            "       01  WS-KEY PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS HANDLE ABEND LABEL(9500-ABEND)",
            "           END-EXEC",
            "           EXEC CICS READ FILE('FLFILE') INTO(WS-REC)",
            "                RIDFLD(WS-KEY)",
            "           END-EXEC",
            "           GOBACK.",
            "       9500-ABEND.",
            "           EXEC CICS SYNCPOINT ROLLBACK",
            "           END-EXEC",
            "           EXEC CICS RETURN",
            "           END-EXEC.",
            "");

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new HandleAbendRollbackMissingRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsHandleAbendExitWithNoRollback() {
        List<Finding> findings = run("FIX043", NO_ROLLBACK);
        assertEquals(1, findings.size(), () -> "ROLLBACK のない出口1件を検出すること: " + findings);
        assertEquals("R043", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(9, findings.get(0).location().line(), "LABEL オペランドの行で報告すること");
        assertTrue(findings.get(0).message().contains("9500-ABEND"), findings.get(0).message());
    }

    @Test
    void ignoresHandleAbendExitThatRollsBack() {
        assertEquals(List.of(), run("FIX043B", WITH_ROLLBACK), "ROLLBACK を実行していれば対象外");
    }
}
