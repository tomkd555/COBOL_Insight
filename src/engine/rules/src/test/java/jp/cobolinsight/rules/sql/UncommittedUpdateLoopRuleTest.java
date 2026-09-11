package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R040 synthetic fixtures: an update loop with and without a COMMIT. */
class UncommittedUpdateLoopRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String name, String commitParagraph) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + name + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-EOF-FLAG       PIC X(01) VALUE 'N'.",
                "           88  WS-EOF                VALUE 'Y'.",
                "       01  WS-KEIYAKU-NO     PIC X(10) VALUE SPACE.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           PERFORM 2000-UPDATE-LOOP UNTIL WS-EOF",
                "           GOBACK.",
                "       2000-UPDATE-LOOP.",
                "           EXEC SQL",
                "               UPDATE MYDB.KEIYAKU SET SHORI_KBN = '1'",
                "                WHERE KEIYAKU_NO = :WS-KEIYAKU-NO",
                "           END-EXEC",
                commitParagraph,
                "           MOVE 'Y' TO WS-EOF-FLAG.",
                "");
    }

    @Test
    void detectsAnUpdateLoopThatNeverCommits() {
        String source = program("FIX040", "           CONTINUE");
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX040.cbl", source);
        List<Finding> findings = new UncommittedUpdateLoopRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(1, findings.size(), () -> "COMMIT のない更新の繰り返し1件: " + findings);
        assertEquals("R040", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("MYDB.KEIYAKU"), findings.get(0).message());
    }

    @Test
    void ignoresAnUpdateLoopThatCommits() {
        String source = program("FIX040B", "           EXEC SQL COMMIT END-EXEC");
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX040B.cbl", source);
        List<Finding> findings = new UncommittedUpdateLoopRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(List.of(), findings, () -> "COMMIT があるものは対象外: " + findings);
    }

    /** An update that no loop reaches runs once; there is nothing to break into units of work. */
    @Test
    void ignoresAnUpdateOutsideEveryLoop() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX040C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-KEIYAKU-NO     PIC X(10) VALUE SPACE.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               UPDATE MYDB.KEIYAKU SET SHORI_KBN = '1'",
                "                WHERE KEIYAKU_NO = :WS-KEIYAKU-NO",
                "           END-EXEC",
                "           GOBACK.",
                "");
        CobolSemanticModel model = SqlAdviceFixtures.parse(tempDir, "FIX040C.cbl", source);
        List<Finding> findings = new UncommittedUpdateLoopRule()
                .evaluate(SqlAdviceFixtures.programContext(model));
        assertEquals(List.of(), findings, () -> "繰り返しの外の更新は対象外: " + findings);
    }
}
