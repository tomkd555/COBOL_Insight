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

/** R019 synthetic fixture verification for a missing cursor close. */
class CursorNotClosedRuleTest {

    @TempDir
    Path tempDir;

    private static final String NOT_CLOSED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX019.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-C PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL",
            "               DECLARE MYCUR CURSOR FOR",
            "                   SELECT COL1 FROM MYTAB",
            "           END-EXEC",
            "           EXEC SQL OPEN MYCUR END-EXEC",
            "           GOBACK.",
            "");

    private static final String CLOSED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX019B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-C PIC X(08).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL",
            "               DECLARE MYCUR CURSOR FOR",
            "                   SELECT COL1 FROM MYTAB",
            "           END-EXEC",
            "           EXEC SQL OPEN MYCUR END-EXEC",
            "           EXEC SQL CLOSE MYCUR END-EXEC",
            "           GOBACK.",
            "");

    @Test
    void detectsCursorOpenedButNotClosed() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX019.cbl", NOT_CLOSED);
        List<Finding> findings = new CursorNotClosedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), NOT_CLOSED)));
        assertEquals(1, findings.size(), () -> "CLOSE漏れカーソル1件を検出すること: " + findings);
        assertEquals("R019", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("MYCUR"), findings.get(0).message());
    }

    @Test
    void ignoresProperlyClosedCursor() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX019B.cbl", CLOSED);
        List<Finding> findings = new CursorNotClosedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), CLOSED)));
        assertEquals(List.of(), findings, () -> "DECLARE/OPEN/CLOSE完備は対象外: " + findings);
    }
}
