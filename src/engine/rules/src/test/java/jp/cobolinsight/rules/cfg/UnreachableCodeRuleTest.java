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

/** R007 の兄弟である R011 の合成fixture検証。到達不能な文と、呼ばれない段落の2判定を別々に見る。 */
class UnreachableCodeRuleTest {

    @TempDir
    Path tempDir;

    /** GOBACK の後に置いた文は、制御が届かない。 */
    private static final String AFTER_GOBACK = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           PERFORM 1000-STEP",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       1000-STEP.",
            /* 11 */ "           GOBACK",
            /* 12 */ "           MOVE 1 TO WS-D.",
            "");

    /** どの PERFORM・GO TO からも参照されず、本流の流下経路上にもない段落。 */
    private static final String UNUSED_PARAGRAPH = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX011B.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-D PIC 9(01).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           MOVE 1 TO WS-D",
            /*  9 */ "           GOBACK.",
            /* 10 */ "       9000-DEAD.",
            /* 11 */ "           MOVE 2 TO WS-D.",
            "");

    /**
     * 同じ段落を、READ の NOT INVALID KEY 句の中から PERFORM する。条件句の中の PERFORM も
     * 参照として数えないと、そこからしか呼ばれない段落が使われていないものに見える。
     */
    private static final String PERFORMED_FROM_AN_IO_CLAUSE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX011C.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT MSTR ASSIGN TO MSTR",
            "                  ORGANIZATION IS INDEXED",
            "                  ACCESS MODE IS DYNAMIC",
            "                  RECORD KEY IS MST-KEY",
            "                  FILE STATUS IS WS-STATUS.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  MSTR.",
            "       01  MST-REC.",
            "           05  MST-KEY  PIC X(08).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-STATUS PIC X(02).",
            "       01  WS-D PIC 9(01).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           READ MSTR",
            "               INVALID KEY",
            "                   MOVE 1 TO WS-D",
            "               NOT INVALID KEY",
            "                   PERFORM 3210-UPDATE",
            "           END-READ",
            "           GOBACK.",
            "       3210-UPDATE.",
            "           MOVE 2 TO WS-D.",
            "");

    private List<Finding> run(String fileName, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, fileName, text);
        return new UnreachableCodeRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsTheStatementAfterGoback() {
        List<Finding> findings = run("FIX011.cbl", AFTER_GOBACK);
        assertEquals(1, findings.size(), () -> "到達不能な文1件を検出すること: " + findings);
        assertEquals("R011", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(12, findings.get(0).location().line());
    }

    @Test
    void detectsTheParagraphNothingCalls() {
        Finding finding = unusedParagraphFinding(run("FIX011B.cbl", UNUSED_PARAGRAPH));
        assertEquals(10, finding.location().line());
        assertTrue(finding.message().contains("9000-DEAD"), finding.message());
    }

    /**
     * 条件句の中の PERFORM は CFG の呼出辺にならないため、段落の中身は到達不能のまま出る。
     * ここで見るのは「段落が参照されない」判定のほうであり、そちらは出ないこと。
     */
    @Test
    void countsAPerformInsideAnIoClauseAsAReference() {
        assertTrue(run("FIX011C.cbl", PERFORMED_FROM_AN_IO_CLAUSE).stream()
                        .noneMatch(f -> f.message().contains("3210-UPDATE")),
                "NOT INVALID KEY の中の PERFORM も参照として数えること");
    }

    private static Finding unusedParagraphFinding(List<Finding> findings) {
        return findings.stream().filter(f -> f.message().contains("段落 ")).findFirst()
                .orElseThrow(() -> new AssertionError("呼ばれない段落の検出が無い: " + findings));
    }
}
