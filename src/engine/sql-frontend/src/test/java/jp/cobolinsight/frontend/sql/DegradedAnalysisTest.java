package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement the analyzer cannot read in full is never thrown away. Each of the four ways the
 * full parse can stop — a ':' the mangler could not read, an empty block, a syntax error, a
 * statement the grammar recognises as nothing — has to come back DEGRADED with the kind, the
 * tables and the cursor name a keyword scan can still see.
 */
class DegradedAnalysisTest {

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    private SqlAnalysisResult degraded(String sql) {
        SqlAnalysisResult result = analyzer.analyze(new SqlBlock(sql, SqlBlockKind.EXECUTABLE,
                new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(SqlAnalysis.DEGRADED, result.analysis(), "degraded: " + sql);
        assertFalse(result.diagnostic().isBlank(), "a degraded statement says why");
        return result;
    }

    @Test
    void aHostVariableTheManglerCannotReadStillYieldsKindAndTables() {
        SqlAnalysisResult result = degraded("UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = :WS-QTY-"
                + " WHERE SHOHIN_CD = :WS-CD");

        assertEquals(SqlStatementKind.UPDATE, result.statementKind());
        assertEquals(List.of("SYKDB.ZAIKOM"), result.tableNames());
        assertTrue(result.diagnostic().contains("ホスト変数"), result.diagnostic());
    }

    @Test
    void aBlockThatHoldsOnlyACommentYieldsKindOther() {
        SqlAnalysisResult result = degraded("-- nothing to run here");

        assertEquals(SqlStatementKind.OTHER, result.statementKind());
        assertEquals(List.of(), result.tableNames());
        assertNull(result.cursorName());
        assertTrue(result.diagnostic().contains("中身がありません"), result.diagnostic());
    }

    @Test
    void aBlockTheGrammarRecognisesAsNoStatementYieldsKindOther() {
        SqlAnalysisResult result = degraded("/* nothing to run here */");

        assertEquals(SqlStatementKind.OTHER, result.statementKind());
        assertTrue(result.diagnostic().contains("認識できませんでした"), result.diagnostic());
    }

    @Test
    void aSyntaxErrorStillYieldsTheKindOfADeclaration() {
        SqlAnalysisResult result = degraded("DECLARE Z#####T TABLE (ACCTNO CHAR(8) NOT NULL,");

        assertEquals(SqlStatementKind.DECLARE_TABLE, result.statementKind());
        assertEquals(List.of(), result.tableNames(),
                "a declaration reads and writes no row, so it references no table");
        assertTrue(result.diagnostic().contains("Db2"), result.diagnostic());
    }

    /** The word that says which DECLARE it is has to be a word, not a column's first syllable. */
    @Test
    void aColumnNamedCursorIdLeavesADeclarationADeclaration() {
        SqlAnalysisResult result =
                degraded("DECLARE CSQDB.LOG TABLE ( CURSOR_ID INTEGER NOT NULL,");

        assertEquals(SqlStatementKind.DECLARE_TABLE, result.statementKind());
        assertEquals(List.of(), result.tableNames(),
                "a declaration reads and writes no row, so it references no table");
    }

    /** A block the grammar reads as two statements loses one of them, so it is read degraded. */
    @Test
    void aBlockHoldingTwoStatementsIsDegradedRatherThanHalfRead() {
        SqlAnalysisResult result = degraded("UPDATE T1 SET C = 1; UPDATE T2 SET C = 2");

        assertEquals(SqlStatementKind.UPDATE, result.statementKind());
        assertEquals(List.of("T1", "T2"), result.tableNames());
        assertTrue(result.diagnostic().contains("2 文"), result.diagnostic());
    }

    @Test
    void aSyntaxErrorInACursorDeclarationStillYieldsTheCursorAndTheTable() {
        SqlAnalysisResult result =
                degraded("DECLARE CUR1 CURSOR FOR SELECT * FROM Z#####T WHERE (");

        assertEquals(SqlStatementKind.DECLARE_CURSOR, result.statementKind());
        assertEquals("CUR1", result.cursorName());
        assertEquals(List.of("Z#####T"), result.tableNames());
    }

    @Test
    void aBrokenFetchKeepsItsKeywordKindAndItsCursor() {
        SqlAnalysisResult result = degraded("FETCH CUR1 INTO :WS-A(");

        assertEquals(SqlStatementKind.FETCH, result.statementKind());
        assertEquals("CUR1", result.cursorName());
    }

    @Test
    void aDegradedStatementCarriesNoStructureSignals() {
        SqlAnalysisResult result =
                degraded("SELECT * FROM Z#####T WHERE UPPER(NAME) = 'A' AND");

        assertFalse(result.structureSignals().selectStar());
        assertEquals(List.of(), result.structureSignals().nonSargablePredicates());
        assertTrue(result.structureSignals().cursor().isEmpty());
    }

    @Test
    void japaneseTableNamesComeBackInTheirOriginalSpelling() {
        SqlAnalysisResult result = degraded("SELECT 数量 FROM 在庫マスタ WHERE 商品CD = :WS-CD-");

        assertEquals(SqlStatementKind.SELECT, result.statementKind());
        assertEquals(List.of("在庫マスタ"), result.tableNames());
    }

    @Test
    void aPositionedDeleteNamesTheCursorItDeletesThrough() {
        SqlAnalysisResult result =
                degraded("DELETE FROM Z#####T WHERE CURRENT OF CUR1 AND");

        assertEquals(SqlStatementKind.DELETE, result.statementKind());
        assertEquals(List.of("Z#####T"), result.tableNames());
        assertEquals("CUR1", result.cursorName());
        assertEquals("CUR1", result.facts().positionedCursor().orElseThrow());
        assertEquals(Map.of("Z#####T", "D"), result.facts().tableAccess());
    }

    /** OPTIMIZE FOR n ROWS plans rows; it does not write them, so it is no row count. */
    @Test
    void anOptimizeForRowsIsNotARowCount() {
        SqlAnalysisResult result =
                degraded("INSERT INTO T1 (A) SELECT A FROM T2 OPTIMIZE FOR 100 ROWS AND (");

        assertEquals(SqlStatementKind.INSERT, result.statementKind());
        assertTrue(result.facts().rowsetSize().isEmpty(), "OPTIMIZE FOR is another clause");
    }

    /** An INSERT that reads the table it writes earns both letters, so occurrences are kept. */
    @Test
    void aTableReadAndWrittenByOneStatementCarriesBothLetters() {
        SqlAnalysisResult result = degraded("INSERT INTO T1 (A) SELECT A FROM T1 WHERE (");

        assertEquals(List.of("T1"), result.tableNames());
        assertEquals(Map.of("T1", "RC"), result.facts().tableAccess());
    }

    /** Only the INTO before the first FROM reads a row into host variables. */
    @Test
    void anIntoBehindTheFromLeavesASelectASelect() {
        SqlAnalysisResult result =
                degraded("SELECT A FROM T1 WHERE B IN (SELECT C INTO :WS-A FROM T2 AND (");

        assertEquals(SqlStatementKind.SELECT, result.statementKind());
    }

    /** The message a reader sees quotes the program's own data names, not the mangler's tokens. */
    @Test
    void theGrammarMessageSpeaksTheProgramsOwnNames() {
        SqlAnalysisResult result = degraded(
                "INSERT INTO SYKDB.MEISAI (A) VALUES (:WS-GYOBAN) FOR 3 ROWS");

        assertTrue(result.diagnostic().contains(":WS-GYOBAN"), result.diagnostic());
        assertFalse(result.diagnostic().contains(":HV1"), result.diagnostic());
        assertEquals(3, result.facts().rowsetSize().orElseThrow(),
                "the row count of a multi-row INSERT is read on this path or nowhere");
    }

    // ---- one keyword of the table scan at a time ----

    @Test
    void aJoinNamesBothOfItsTables() {
        SqlAnalysisResult result =
                degraded("SELECT A FROM T1 JOIN T2 ON T1.K = T2.K WHERE (");

        assertEquals(SqlStatementKind.SELECT, result.statementKind());
        assertEquals(List.of("T1", "T2"), result.tableNames());
    }

    @Test
    void anInsertNamesTheTableItInsertsInto() {
        SqlAnalysisResult result = degraded("INSERT INTO SYKDB.MEISAI (A) VALUES (");

        assertEquals(SqlStatementKind.INSERT, result.statementKind());
        assertEquals(List.of("SYKDB.MEISAI"), result.tableNames());
    }

    @Test
    void aMergeNamesItsTargetTable() {
        SqlAnalysisResult result = degraded("MERGE INTO SYKDB.ZAIKOM AS Z USING (");

        assertEquals(List.of("SYKDB.ZAIKOM"), result.tableNames());
    }

    /** The source of a MERGE is a table as readily as a subquery, and the merge reads it. */
    @Test
    void aMergeNamesTheTableItReadsItsRowsFrom() {
        SqlAnalysisResult result = degraded(
                "MERGE INTO CSQDB.ZAIKO AS T USING CSQDB.ZAIKO_WK AS S ON (T.K = S.K) WHEN (");

        assertEquals(List.of("CSQDB.ZAIKO", "CSQDB.ZAIKO_WK"), result.tableNames());
        assertEquals(Map.of("CSQDB.ZAIKO", "CU", "CSQDB.ZAIKO_WK", "R"),
                result.facts().tableAccess(), "a merge whose branches are unreadable writes rows");
    }

    /** A merge books what its branches do: one that only updates creates no row. */
    @Test
    void aMergeWithOnlyAnUpdateBranchCreatesNoRow() {
        SqlAnalysisResult result = degraded("MERGE INTO T1 USING (VALUES (:WS-K)) AS S (K)"
                + " ON T1.K = S.K WHEN MATCHED THEN UPDATE SET V = S.V AND (");

        assertEquals(Map.of("T1", "U"), result.facts().tableAccess());
    }

    @Test
    void aLockTableNamesTheTableItLocks() {
        SqlAnalysisResult result = degraded("LOCK TABLE SYKDB.ZAIKOM IN (");

        assertEquals(List.of("SYKDB.ZAIKOM"), result.tableNames());
    }

    /** A SHARE lock keeps the table still to read it; it changes no row. */
    @Test
    void aLockTableInShareModeOnlyReadsTheTable() {
        SqlAnalysisResult result = degraded("LOCK TABLE SYKDB.ZAIKOM IN SHARE MODE AND (");

        assertEquals(Map.of("SYKDB.ZAIKOM", "R"), result.facts().tableAccess());
    }

    @Test
    void anOpenNamesItsCursor() {
        SqlAnalysisResult result = degraded("OPEN CSR-KEIYAKU USING (");

        assertEquals(SqlStatementKind.OPEN, result.statementKind());
        assertEquals("CSR-KEIYAKU", result.cursorName());
        assertEquals(List.of(), result.tableNames());
    }

    @Test
    void aCloseNamesItsCursor() {
        SqlAnalysisResult result = degraded("CLOSE CSR-KEIYAKU WHERE (");

        assertEquals(SqlStatementKind.CLOSE, result.statementKind());
        assertEquals("CSR-KEIYAKU", result.cursorName());
    }

    // ---- what the scan must not mistake for a name ----

    @Test
    void theColumnOfAForUpdateIsNotATable() {
        SqlAnalysisResult result = degraded("SELECT A FROM T1 FOR UPDATE OF COL1 AND (");

        assertEquals(List.of("T1"), result.tableNames());
    }

    @Test
    void theIsolationOfAForUpdateWithIsNotATable() {
        SqlAnalysisResult result = degraded("SELECT A FROM T1 FOR UPDATE WITH RS AND (");

        assertEquals(List.of("T1"), result.tableNames());
    }

    @Test
    void aPositionedFetchNamesItsCursorAndNoTable() {
        SqlAnalysisResult result =
                degraded("FETCH NEXT FROM CSR-KEIYAKU INTO :WS-A, :WS-B AND (");

        assertEquals(SqlStatementKind.FETCH, result.statementKind());
        assertEquals("CSR-KEIYAKU", result.cursorName());
        assertEquals(List.of(), result.tableNames());
    }

    @Test
    void aHyphenatedCursorNameIsKeptWhole() {
        SqlAnalysisResult result =
                degraded("DECLARE CSR-KEIYAKU CURSOR FOR SELECT A FROM T1 WHERE (");

        assertEquals(SqlStatementKind.DECLARE_CURSOR, result.statementKind());
        assertEquals("CSR-KEIYAKU", result.cursorName());
        assertEquals(List.of("T1"), result.tableNames());
    }

    @Test
    void aKeywordInsideAStringLiteralIsNotATable() {
        SqlAnalysisResult result =
                degraded("SELECT A FROM T1 WHERE MEMO = 'FROM SHIPPING' AND (");

        assertEquals(List.of("T1"), result.tableNames());
    }

    @Test
    void everyTableOfACommaSeparatedFromIsNamed() {
        SqlAnalysisResult result = degraded("SELECT A FROM T1, T2 WHERE (");

        assertEquals(List.of("T1", "T2"), result.tableNames());
    }

    /** The older z/OS join writes a correlation after each table of the list. */
    @Test
    void aCorrelationDoesNotHideTheTablesBehindIt() {
        SqlAnalysisResult result =
                degraded("SELECT A FROM FLDB.KEIYAKU K, FLDB.KOKYAKU AS C WHERE (");

        assertEquals(List.of("FLDB.KEIYAKU", "FLDB.KOKYAKU"), result.tableNames());
    }

    /** Nor may it swallow the keyword that names the next table. */
    @Test
    void aCorrelationDoesNotSwallowTheJoinAfterIt() {
        SqlAnalysisResult result = degraded("SELECT A FROM T1 X JOIN T2 ON X.K = T2.K WHERE (");

        assertEquals(List.of("T1", "T2"), result.tableNames());
    }

    @Test
    void aDelimitedTableNameIsNamed() {
        SqlAnalysisResult result = degraded("SELECT A FROM \"MY TABLE\" WHERE (");

        assertEquals(List.of("\"MY TABLE\""), result.tableNames());
    }

    @Test
    void aCommaInsideADelimitedTableNameDoesNotSplitIt() {
        SqlAnalysisResult result = degraded("SELECT A FROM \"A, B\" WHERE (");

        assertEquals(List.of("\"A, B\""), result.tableNames());
    }

    @Test
    void theFetchFirstOfAQueryIsNotACursorFetch() {
        SqlAnalysisResult result = degraded("SELECT A FROM T1 FETCH FIRST 10 ROWS ONLY AND (");

        assertEquals(SqlStatementKind.SELECT, result.statementKind());
        assertEquals(List.of("T1"), result.tableNames());
        assertNull(result.cursorName(), () -> "no cursor: " + result.cursorName());
    }
}
