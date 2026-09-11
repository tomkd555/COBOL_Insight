package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R011 on two field idioms that reach a procedure without a PERFORM: the INPUT/OUTPUT PROCEDURE
 * of a SORT, and the GO TO target of an EXEC SQL WHENEVER.
 */
class SortProcedureAndWheneverReachabilityTest {

    @TempDir
    Path tempDir;

    private static final String SORT_PROCEDURES = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX011S.",
            "       ENVIRONMENT DIVISION.",
            "       INPUT-OUTPUT SECTION.",
            "       FILE-CONTROL.",
            "           SELECT INFILE  ASSIGN TO INFILE.",
            "           SELECT OUTFILE ASSIGN TO OUTFILE.",
            "           SELECT SORTWK  ASSIGN TO SORTWK.",
            "       DATA DIVISION.",
            "       FILE SECTION.",
            "       FD  INFILE.",
            "       01  IN-REC                          PIC X(80).",
            "       FD  OUTFILE.",
            "       01  OUT-REC                         PIC X(80).",
            "       SD  SORTWK.",
            "       01  SORT-REC.",
            "           05  SORT-KEY                    PIC X(10).",
            "           05  FILLER                      PIC X(70).",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-EOF                          PIC X(01) VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN SECTION.",
            "       0000-START.",
            "           SORT SORTWK ON ASCENDING KEY SORT-KEY",
            "                INPUT PROCEDURE  IS 1000-INPUT",
            "                OUTPUT PROCEDURE IS 2000-OUTPUT THRU 2000-EXIT",
            "           STOP RUN.",
            "       1000-INPUT SECTION.",
            "       1000-START.",
            "           OPEN INPUT INFILE",
            "           PERFORM UNTIL WS-EOF = 'Y'",
            "               READ INFILE",
            "                   AT END MOVE 'Y' TO WS-EOF",
            "                   NOT AT END RELEASE SORT-REC FROM IN-REC",
            "               END-READ",
            "           END-PERFORM",
            "           CLOSE INFILE.",
            "       2000-OUTPUT SECTION.",
            "       2000-START.",
            "           OPEN OUTPUT OUTFILE",
            "           MOVE 'N' TO WS-EOF",
            "           PERFORM UNTIL WS-EOF = 'Y'",
            "               RETURN SORTWK",
            "                   AT END MOVE 'Y' TO WS-EOF",
            "                   NOT AT END WRITE OUT-REC FROM SORT-REC",
            "               END-RETURN",
            "           END-PERFORM",
            "           CLOSE OUTFILE.",
            "       2000-EXIT.",
            "           EXIT.",
            "");

    private static final String WHENEVER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX011W.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-V PIC S9(04) COMP.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC",
            "           EXEC SQL UPDATE MYTAB SET COL1 = 1 END-EXEC",
            "           IF SQLCODE NOT = 0 DISPLAY 'NG' END-IF",
            "           STOP RUN.",
            "       9900-SQL-ERROR.",
            "           DISPLAY 'SQL ERROR' SQLCODE",
            "           STOP RUN.",
            "");

    @Test
    void sortProceduresAreReachable() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX011S.cbl", SORT_PROCEDURES);
        List<Finding> findings = new UnreachableCodeRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), SORT_PROCEDURES)));
        assertEquals(List.of(), findings, () -> "SORT performs its procedures: " + findings);
    }

    @Test
    void wheneverTargetIsReachable() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX011W.cbl", WHENEVER);
        List<Finding> findings = new UnreachableCodeRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WHENEVER)));
        assertEquals(List.of(), findings, () -> "the precompiler branches to the target: " + findings);
    }
}
