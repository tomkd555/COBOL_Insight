package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.ProgramLines;
import jp.cobolinsight.core.semantic.Statements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R018 boundary fixture verification for unchecked SQLCODE: DML and SELECT INTO/FETCH, with and without WHENEVER. */
class SqlCodeUncheckedRuleTest {

    @TempDir
    Path tempDir;

    private static String source(String programId, boolean checkSqlCode) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-D PIC 9(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               UPDATE MYTAB SET COL1 = 1",
                "           END-EXEC",
                checkSqlCode
                        ? "           IF SQLCODE NOT = 0\n"
                                + "               DISPLAY 'SQL ERROR'\n"
                                + "           END-IF"
                        : "           DISPLAY 'UPDATE DONE'",
                "           GOBACK.",
                "");
    }

    // A SELECT INTO whose +100 (no row) leaves WS-V stale and is never tested.
    private static final String SELECT_INTO = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX018C.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-V PIC S9(04) COMP.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL",
            "               SELECT COL1 INTO :WS-V FROM MYTAB",
            "           END-EXEC",
            "           DISPLAY 'SELECT DONE'",
            "           GOBACK.",
            "");

    @Test
    void detectsUpdateWhoseSqlCodeIsNeverChecked() {
        String text = source("FIX018", false);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "未検査UPDATE1件を検出すること: " + findings);
        assertEquals("R018", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(11, findings.get(0).location().line());
    }

    @Test
    void ignoresUpdateFollowedBySqlCodeCheck() {
        String text = source("FIX018B", true);
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018B.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings, () -> "SQLCODE検査つきUPDATEは対象外: " + findings);
    }

    @Test
    void detectsSelectIntoWhoseSqlCodeIsNeverChecked() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018C.cbl", SELECT_INTO);
        List<Finding> findings = new SqlCodeUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), SELECT_INTO)));
        assertEquals(1, findings.size(), () -> "an unchecked SELECT INTO is reported: " + findings);
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(11, findings.get(0).location().line());
    }

    private static String withWhenever(String programId, String dml) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-D PIC 9(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC",
                "           EXEC SQL",
                "               " + dml,
                "           END-EXEC",
                "           DISPLAY 'DONE'",
                "           GOBACK.",
                "       9900-SQL-ERROR.",
                "           DISPLAY 'SQL ERROR' SQLCODE",
                "           GOBACK.",
                "");
    }

    @Test
    void reportsUpdateUnderWheneverAsWarningForTheUnhandledPlus100() {
        String text = withWhenever("FIX018D", "UPDATE MYTAB SET COL1 = 1 WHERE ID = 7");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018D.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "the +100 case stays unhandled: " + findings);
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(12, findings.get(0).location().line());
    }

    @Test
    void ignoresInsertUnderWhenever() {
        String text = withWhenever("FIX018E", "INSERT INTO MYTAB (COL1) VALUES (1)");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018E.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings,
                () -> "WHENEVER SQLERROR covers every failure an INSERT can have: " + findings);
    }

    private static final String FETCH_LOOP = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX018F.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",
            "       01  WS-V PIC S9(04) COMP.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC SQL OPEN CSR1 END-EXEC",
            "           IF SQLCODE NOT = 0 GOBACK END-IF",
            "           PERFORM 2000-FETCH UNTIL SQLCODE NOT = 0",
            "           EXEC SQL CLOSE CSR1 END-EXEC",
            "           GOBACK.",
            "       2000-FETCH.",
            "           EXEC SQL FETCH CSR1 INTO :WS-V END-EXEC",
            "           IF SQLCODE = 0",
            "               ADD 1 TO WS-V",
            "           END-IF.",
            "");

    private static String withWhenevers(String programId, String dml, String... whenevers) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-D PIC 9(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                String.join("\n", whenevers),
                "           EXEC SQL",
                "               " + dml,
                "           END-EXEC",
                "           DISPLAY 'DONE'",
                "           GOBACK.",
                "       8000-NOT-FOUND.",
                "           DISPLAY 'NOT FOUND'",
                "           GOBACK.",
                "       9900-SQL-ERROR.",
                "           DISPLAY 'SQL ERROR' SQLCODE",
                "           GOBACK.",
                "");
    }

    /**
     * One copybook's WHENEVER SQLERROR GO TO is copied at line 8 and again at line 11, with a
     * WHENEVER SQLERROR CONTINUE between them. The CONTINUE at line 9 governs line 10, and the
     * copy at line 11 governs line 12. The model holds both copies, each at its own COPY line.
     */
    @Test
    void eachCopyOfAWheneverTakesEffectAtItsOwnCopyStatement() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy"));
        Files.writeString(copybooks.resolve("SQLERR.cpy"),
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC.\n");
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX018K.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                /*  6 */ "       PROCEDURE DIVISION.",
                /*  7 */ "       0000-MAIN.",
                /*  8 */ "           COPY SQLERR.",
                /*  9 */ "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.",
                /* 10 */ "           EXEC SQL INSERT INTO MYTAB VALUES (1) END-EXEC.",
                /* 11 */ "           COPY SQLERR.",
                /* 12 */ "           EXEC SQL INSERT INTO MYTAB VALUES (2) END-EXEC.",
                /* 13 */ "           GOBACK.",
                /* 14 */ "       9900-SQL-ERROR.",
                /* 15 */ "           DISPLAY 'SQL ERROR' SQLCODE",
                /* 16 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018K.cbl", text, copybooks);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(10), findings.stream().map(f -> f.location().line()).toList());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(List.of(8, 11), copyLinesOf(model, "SQLERR.cpy"));
    }

    /**
     * The verifier's program: the copy at line 8 governs the INSERT at line 9, the CONTINUE at
     * line 10 is cancelled by the copy at line 11, and that copy governs the INSERT at line 12.
     * Neither INSERT is reported.
     */
    @Test
    void theFirstCopyOfAWheneverGovernsTheStatementAfterIt() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy4"));
        Files.writeString(copybooks.resolve("SQLERR.cpy"),
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC.\n");
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX018N.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                /*  6 */ "       PROCEDURE DIVISION.",
                /*  7 */ "       0000-MAIN.",
                /*  8 */ "           COPY SQLERR.",
                /*  9 */ "           EXEC SQL INSERT INTO MYTAB VALUES (1) END-EXEC.",
                /* 10 */ "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.",
                /* 11 */ "           COPY SQLERR.",
                /* 12 */ "           EXEC SQL INSERT INTO MYTAB VALUES (2) END-EXEC.",
                /* 13 */ "           GOBACK.",
                /* 14 */ "       9900-SQL-ERROR.",
                /* 15 */ "           DISPLAY 'SQL ERROR' SQLCODE",
                /* 16 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018N.cbl", text, copybooks);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings, findings::toString);
    }

    /**
     * A copied DELETE included at line 8 and again at line 10: the first inclusion goes on to the
     * second, which stands on the same copybook line, and the message names the copybook.
     */
    @Test
    void theNextInclusionOfOneCopybookIsAheadOfThePreviousOne() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy5"));
        Files.writeString(copybooks.resolve("SQLC.cpy"),
                "           EXEC SQL DELETE FROM MYTAB2 WHERE ID = 1 END-EXEC.\n");
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX018P.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                /*  6 */ "       PROCEDURE DIVISION.",
                /*  7 */ "       0000-MAIN.",
                /*  8 */ "           COPY SQLC.",
                /*  9 */ "           DISPLAY 'X'.",
                /* 10 */ "           COPY SQLC.",
                /* 11 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018P.cbl", text, copybooks);
        List<String> messages = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)))
                .stream().map(Finding::message).toList();
        assertEquals(2, messages.size(), messages::toString);
        assertTrue(messages.get(0).contains("次の SQL（1行）へ進む"), messages::toString);
        assertTrue(messages.get(1).contains("プログラムの終端まで進む"), messages::toString);
    }

    /**
     * A program statement on the line of the first COPY leaves the second COPY of one copybook
     * its own place: the INSERT at line 10 runs under the CONTINUE of line 9, and the INSERT at
     * line 12 under the copy at line 11. The model holds both copies, each at its own COPY line.
     */
    @Test
    void aProgramStatementOnACopysLineKeepsTheNextCopyInItsPlace() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy2"));
        Files.writeString(copybooks.resolve("SQLERR.cpy"),
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC.\n");
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX018L.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                /*  6 */ "       01  WS-D PIC 9.",
                /*  7 */ "       PROCEDURE DIVISION.",
                /*  8 */ "       0000-MAIN.",
                /*  9 */ "           COPY SQLERR. EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC.",
                /* 10 */ "           EXEC SQL INSERT INTO MYTAB VALUES (1) END-EXEC.",
                /* 11 */ "           COPY SQLERR.",
                /* 12 */ "           EXEC SQL INSERT INTO MYTAB VALUES (2) END-EXEC.",
                /* 13 */ "           GOBACK.",
                /* 14 */ "       9900-SQL-ERROR.",
                /* 15 */ "           DISPLAY 'SQL ERROR' SQLCODE",
                /* 16 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018L.cbl", text, copybooks);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(10), findings.stream().map(f -> f.location().line()).toList());
        assertEquals(List.of(9, 11), copyLinesOf(model, "SQLERR.cpy"));
    }

    /** The COPY line of each statement the copybook named {@code fileName} brings in. */
    private static List<Integer> copyLinesOf(CobolSemanticModel model, String fileName) {
        ProgramLines lines = new ProgramLines(model);
        List<Integer> copyLines = new java.util.ArrayList<>();
        for (var procedure : model.procedures()) {
            Statements.walk(procedure.statements(), statement -> {
                if (Path.of(statement.range().start().file()).getFileName().toString()
                        .equals(fileName)) {
                    copyLines.add(lines.copyLineOf(statement));
                }
            });
        }
        return copyLines;
    }

    /**
     * One copybook of an EXEC SQL statement included before and after an UPDATE: the SQL after
     * the UPDATE is the second inclusion, which comes later in the program, so the UPDATE goes on
     * to it and loops back to nothing. Two inclusions give two blocks of one range, told apart
     * by identity.
     */
    @Test
    void theSecondInclusionOfACopiedStatementIsTheNextSqlAfterAnUpdate() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy3"));
        Files.writeString(copybooks.resolve("SQLC.cpy"),
                "           EXEC SQL DELETE FROM MYTAB2 WHERE ID = 1 END-EXEC.\n");
        String text = String.join("\n",
                /*  1 */ "       IDENTIFICATION DIVISION.",
                /*  2 */ "       PROGRAM-ID. FIX018M.",
                /*  3 */ "       DATA DIVISION.",
                /*  4 */ "       WORKING-STORAGE SECTION.",
                /*  5 */ "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                /*  6 */ "       PROCEDURE DIVISION.",
                /*  7 */ "       0000-MAIN.",
                /*  8 */ "           COPY SQLC.",
                /*  9 */ "           EXEC SQL UPDATE MYTAB SET COL1 = 1 END-EXEC.",
                /* 10 */ "           DISPLAY 'X'.",
                /* 11 */ "           COPY SQLC.",
                /* 12 */ "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018M.cbl", text, copybooks);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        Finding update = findings.stream()
                .filter(f -> f.location().file().equals(model.sourceFile())
                        && f.location().line() == 9)
                .findFirst().orElseThrow(() -> new AssertionError(findings.toString()));
        assertTrue(update.message().contains("次の SQL（SQLC.cpy 1行）へ進む"), update.message());
    }

    /** WHENEVER is a precompiler directive: the last one before the statement decides. */
    @Test
    void continueCancelsAnEarlierWheneverBranch() {
        String text = withWhenevers("FIX018G", "UPDATE MYTAB SET COL1 = 1 WHERE ID = 7",
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC",
                "           EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018G.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "nothing handles the UPDATE any more: " + findings);
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(13, findings.get(0).location().line());
    }

    @Test
    void wheneverNotFoundCompletesTheHandlingOfARead() {
        String text = withWhenevers("FIX018H", "SELECT COL1 INTO :WS-D FROM MYTAB",
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC",
                "           EXEC SQL WHENEVER NOT FOUND GO TO 8000-NOT-FOUND END-EXEC");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018H.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings,
                () -> "SQLERROR and NOT FOUND between them cover every SQLCODE: " + findings);
    }

    @Test
    void ignoresFetchWhoseLoopConditionTestsSqlCode() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018F.cbl", FETCH_LOOP);
        List<Finding> findings = new SqlCodeUncheckedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), FETCH_LOOP)));
        assertEquals(List.of(), findings, () -> "a FETCH tested in its loop is checked: " + findings);
    }

    /**
     * A copybook holding the shop's error handling is copied at the end of the procedure division,
     * and its own lines are far above the statements written before the COPY. The WHENEVER it
     * carries takes effect where the COPY statement stands, so it covers nothing above it and the
     * UPDATE is an unhandled error, not a WARNING for the +100 alone.
     */
    @Test
    void aWheneverCopiedInTakesEffectWhereTheCopyStands() throws IOException {
        Path copybooks = Files.createDirectories(tempDir.resolve("cpy"));
        Files.writeString(copybooks.resolve("ERRCPY.cpy"), String.join("\n",
                "       8000-SQL-CHECK.",
                "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC",
                "           CONTINUE.",
                "       9900-SQL-ERROR.",
                "           DISPLAY 'SQL ERROR' SQLCODE",
                "           GOBACK.",
                ""));
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX018J.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-D PIC 9(01).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               UPDATE MYTAB SET COL1 = 1 WHERE ID = 7",
                "           END-EXEC",
                "           DISPLAY 'DONE'",
                "           GOBACK.",
                "           COPY ERRCPY.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018J.cbl", text, copybooks);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(1, findings.size(), () -> "the UPDATE is unhandled: " + findings);
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(11, findings.get(0).location().line());
    }

    /** GET DIAGNOSTICS reads the condition the statement before it raised, so it is the check. */
    @Test
    void getDiagnosticsAfterTheUpdateCountsAsTheCheck() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX018I.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-ROWS PIC S9(09) COMP.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC SQL",
                "               UPDATE MYTAB SET COL1 = 1",
                "           END-EXEC",
                "           EXEC SQL",
                "               GET DIAGNOSTICS :WS-ROWS = ROW_COUNT",
                "           END-EXEC",
                "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018I.cbl", text);
        List<Finding> findings = new SqlCodeUncheckedRule()
                .evaluate(CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
        assertEquals(List.of(), findings,
                () -> "a following GET DIAGNOSTICS is the check: " + findings);
    }
}
