package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R055 on the seeded fixture samples/cobol/SYK011.cbl, which carries one cursor per defect kind, on
 * samples/cobol/SYK006.cbl, whose cursor is used in order, and on synthetic programs for what the
 * fixture does not carry: WITH HOLD against a cursor a COMMIT closes, the SYNCPOINT of a CICS
 * program, a ROLLBACK no OPEN reaches, a cursor nothing in the program opens, a column outside the
 * cursor's FOR UPDATE OF list and one qualified with the UPDATE's correlation name, a positioned
 * DELETE on an ambiguous and on a read-only declaration, an OPEN under a first-time flag, and a
 * WITH HOLD declaration a COPY brought in.
 */
class CursorLifecycleRuleTest {

    private static List<String> linesOf(List<Finding> findings) {
        return findings.stream().map(finding -> finding.location().line() + "").toList();
    }

    @Test
    void reportsOneFindingPerDefectKindOnTheSeededFixture() {
        List<Finding> findings = new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(
                        SqlAdviceFixtures.samplesProgram("SYK011.cbl")));
        assertEquals(List.of("51", "73", "77", "93", "113"), linesOf(findings),
                () -> findings.toString());
        assertEquals("R055", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("CSR-JUNBI の FETCH に到達する経路"),
                findings.get(0).message());
        assertTrue(findings.get(1).message().contains("再び OPEN されます"),
                findings.get(1).message());
        assertTrue(findings.get(2).message().contains("どこからも OPEN されていません"),
                findings.get(2).message());
        assertTrue(findings.get(3).message().contains("ROLLBACK は WITH HOLD"),
                findings.get(3).message());
        assertTrue(findings.get(4).message().contains("FOR READ ONLY で宣言されています"),
                findings.get(4).message());
    }

    @Test
    void silentOnACursorUsedInOrder() {
        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(
                        SqlAdviceFixtures.samplesProgram("SYK006.cbl"))));
    }

    @Test
    void aCommitClosesACursorDeclaredWithoutWithHold(@TempDir Path dir) {
        assertEquals(List.of(), new CursorLifecycleRule()
                .evaluate(SqlAdviceFixtures.cfgProgramContext(program(dir, "FIX055A", ""))));
    }

    @Test
    void aWithHoldCursorIsStillOpenAfterACommit(@TempDir Path dir) {
        List<Finding> findings = new CursorLifecycleRule()
                .evaluate(SqlAdviceFixtures.cfgProgramContext(program(dir, "FIX055B", "WITH HOLD")));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().contains("再び OPEN されます"),
                findings.get(0).message());
    }

    /**
     * On a CICS program the unit of work ends with SYNCPOINT, and such a program carries no EXEC SQL
     * COMMIT at all. Were SYNCPOINT left out of the boundary, the second OPEN would read as a -502.
     */
    @Test
    void aCicsSyncpointEndsTheUnitOfWorkTheWayACommitDoes(@TempDir Path dir) {
        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055C", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC CICS SYNCPOINT END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   EXEC CICS RETURN END-EXEC.
                        """))));
    }

    /**
     * The ROLLBACK stands in an error paragraph no OPEN of the cursor reaches, and only the
     * paragraph fall-through of the graph carries it into the FETCH. No execution opens the cursor,
     * rolls back and then fetches, so there is no -501 to report. Line 93 of the seeded fixture is
     * the case where an OPEN does reach the ROLLBACK.
     */
    @Test
    void silentOnARollbackNoOpenOfTheCursorReaches(@TempDir Path dir) {
        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055D", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   PERFORM 2000-READ
                                   STOP RUN.
                               1000-ERROR.
                                   EXEC SQL ROLLBACK END-EXEC.
                               2000-READ.
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC.
                               3000-OPEN.
                                   EXEC SQL OPEN CSR1 END-EXEC.
                        """))));
    }

    /** A cursor declared in a copybook the program does not include: no OPEN anywhere to find. */
    @Test
    void reportsAFetchOfACursorNothingInTheProgramOpens(@TempDir Path dir) {
        List<Finding> findings = new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055E", """
                                   EXEC SQL FETCH CSR-GAIBU INTO :WS-CD END-EXEC
                                   EXEC SQL CLOSE CSR-GAIBU END-EXEC
                                   STOP RUN.
                        """)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().contains(
                        "CSR-GAIBU を OPEN する文がプログラムのどこにもありません"),
                findings.get(0).message());
        assertTrue(findings.get(0).message().contains("-501"), findings.get(0).message());
    }

    /** FOR UPDATE OF names the columns the cursor may write; a column outside it is a -503. */
    @Test
    void reportsAnUpdateOfAColumnOutsideTheForUpdateOfList(@TempDir Path dir) {
        List<Finding> findings = new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055F", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD, ZAIKO_SU
                                             FROM SYKDB.ZAIKOM
                                            FOR UPDATE OF ZAIKO_SU
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC
                                   EXEC SQL UPDATE SYKDB.ZAIKOM
                                               SET SHOHIN_CD = :WS-CD
                                             WHERE CURRENT OF CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                        """)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().contains(
                        "SHOHIN_CD は CSR1 の FOR UPDATE OF に挙げられていません"),
                findings.get(0).message());
        assertTrue(findings.get(0).message().contains("-503"), findings.get(0).message());
    }

    /**
     * A SET column may carry the correlation name the UPDATE declares, which the FOR UPDATE OF list
     * never does. The qualified name is the same column, so there is no -503 to report.
     */
    @Test
    void silentOnAQualifiedSetColumnInsideTheForUpdateOfList(@TempDir Path dir) {
        assertTrue(SqlAdviceFixtures
                        .model("UPDATE SYKDB.ZAIKOM Z SET Z.ZAIKO_SU = :WS-SU "
                                + "WHERE CURRENT OF CSR1", 1)
                        .setPairs().stream().anyMatch(pair -> pair.column().contains(".")),
                "the fixture has to reach the rule with the qualifier on the SET column");

        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055G", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD, ZAIKO_SU
                                             FROM SYKDB.ZAIKOM
                                            FOR UPDATE OF ZAIKO_SU
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC
                                   EXEC SQL UPDATE SYKDB.ZAIKOM Z
                                               SET Z.ZAIKO_SU = :WS-CD
                                             WHERE CURRENT OF CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                        """))));
    }

    /**
     * A declaration with neither FOR UPDATE nor a read-only clause is ambiguous, and the SQL
     * statement coprocessor a current shop compiles with leaves such a cursor updatable; S004
     * reports the ambiguity itself. A declaration that does state FOR READ ONLY is a -510, and the
     * message says what the positioned statement was going to do.
     */
    @Test
    void reportsAPositionedDeleteOnlyOnACursorDeclaredReadOnly(@TempDir Path dir) {
        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055H", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC
                                   EXEC SQL DELETE FROM SYKDB.ZAIKOM
                                             WHERE CURRENT OF CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                        """))));

        List<Finding> findings = new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055I", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC
                                   EXEC SQL DELETE FROM SYKDB.ZAIKOM
                                             WHERE CURRENT OF CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                        """)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message()
                        .contains("CSR1 は FOR READ ONLY で宣言されています。"
                                + "WHERE CURRENT OF による削除は SQLCODE -510 で失敗します。"),
                findings.get(0).message());
    }

    /**
     * The read paragraph opens the cursor on its first entry and turns the flag off in the same
     * branch, so the path around the OPEN is the one every later entry takes with the cursor open.
     * A branch that does not write its own guard is the seeded defect's shape and still reported.
     */
    @Test
    void silentOnAnOpenUnderAFirstTimeFlagTheBranchTurnsOff(@TempDir Path dir) {
        assertEquals(List.of(), new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055J", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   PERFORM 2100-YOMI UNTIL SQLCODE = 100
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                               2100-YOMI.
                                   IF WS-SHOKAI = 'Y'
                                       EXEC SQL OPEN CSR1 END-EXEC
                                       MOVE 'N' TO WS-SHOKAI
                                   END-IF
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC.
                        """))));
    }

    @Test
    void reportsAFetchUnderABranchTheOpenDoesNotWrite(@TempDir Path dir) {
        List<Finding> findings = new CursorLifecycleRule().evaluate(
                SqlAdviceFixtures.cfgProgramContext(programWith(dir, "FIX055K", """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   PERFORM 2100-YOMI UNTIL SQLCODE = 100
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   STOP RUN.
                               2100-YOMI.
                                   IF WS-SHOKAI = 'Y'
                                       EXEC SQL OPEN CSR1 END-EXEC
                                   END-IF
                                   EXEC SQL FETCH CSR1 INTO :WS-CD END-EXEC.
                        """)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().contains("CSR1 の FETCH に到達する経路"),
                findings.get(0).message());
    }

    /**
     * The cursor declaration stands in a copybook, so it is no statement of the program's own file;
     * WITH HOLD still says the cursor survives the COMMIT, and the second OPEN is a -502.
     */
    @Test
    void readsAWithHoldDeclarationOutOfACopybook(@TempDir Path dir) throws IOException {
        Path copybooks = Files.createDirectories(dir.resolve("cpy"));
        Files.writeString(copybooks.resolve("CSRHOLD.cpy"), """
                           EXEC SQL
                               DECLARE CSR1 CURSOR WITH HOLD FOR
                                   SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                    FOR READ ONLY
                           END-EXEC.
                """);
        CobolSemanticModel model = SqlAdviceFixtures.parse(dir, "FIX055L.cbl", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID.  FIX055L.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           EXEC SQL INCLUDE SQLCA END-EXEC.
                       01  WS-CD                       PIC X(08) VALUE SPACE.
                           COPY CSRHOLD.
                       PROCEDURE DIVISION.
                       0000-MAIN.
                           EXEC SQL OPEN CSR1 END-EXEC
                           EXEC SQL COMMIT END-EXEC
                           EXEC SQL OPEN CSR1 END-EXEC
                           EXEC SQL CLOSE CSR1 END-EXEC
                           STOP RUN.
                """, copybooks);
        List<Finding> findings =
                new CursorLifecycleRule().evaluate(SqlAdviceFixtures.cfgProgramContext(model));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().contains("再び OPEN されます"),
                findings.get(0).message());
    }

    /** OPEN, COMMIT, OPEN again of one cursor, declared with the given hold clause. */
    private static CobolSemanticModel program(Path dir, String name, String hold) {
        return programWith(dir, name, """
                                   EXEC SQL
                                       DECLARE CSR1 CURSOR %s FOR
                                           SELECT SHOHIN_CD FROM SYKDB.ZAIKOM
                                            FOR READ ONLY
                                   END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL COMMIT END-EXEC
                                   EXEC SQL OPEN CSR1 END-EXEC
                                   EXEC SQL CLOSE CSR1 END-EXEC
                                   DISPLAY 'DONE ' WS-CD
                                   STOP RUN.
                        """.formatted(hold));
    }

    /** A program whose PROCEDURE DIVISION opens with 0000-MAIN and carries the given text. */
    private static CobolSemanticModel programWith(Path dir, String name, String procedure) {
        return SqlAdviceFixtures.parse(dir, name + ".cbl", """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID.  %s.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                           EXEC SQL INCLUDE SQLCA END-EXEC.
                       01  WS-CD                       PIC X(08) VALUE SPACE.
                       01  WS-SHOKAI                   PIC X(01) VALUE 'Y'.
                       PROCEDURE DIVISION.
                       0000-MAIN.
                %s
                """.formatted(name, procedure));
    }
}
