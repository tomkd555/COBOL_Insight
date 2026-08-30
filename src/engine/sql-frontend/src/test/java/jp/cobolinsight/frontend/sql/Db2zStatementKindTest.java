package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.CursorSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One statement kind per test, written in Db2 for z/OS syntax, over the MAPA grammar.
 * The kinds the S rules and the persistence layer read have to come out of the parse tree,
 * and the statements that carry no signals have to be accepted rather than rejected.
 */
class Db2zStatementKindTest {

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    private SqlAnalysisResult analyze(String sql, SqlBlockKind kind) {
        SqlAnalysisResult result = analyzer.analyze(new SqlBlock(sql, kind,
                new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(AnalysisStatus.ANALYZED, result.status(),
                "the grammar has to accept: " + sql + " -> " + result.statusReason());
        return result;
    }

    private SqlAnalysisResult analyze(String sql) {
        return analyze(sql, SqlBlockKind.EXECUTABLE);
    }

    @Test
    void selectIntoBindsEveryHostVariableAndKeepsTheIntoTargetsInOrder() {
        SqlAnalysisResult result =
                analyze("SELECT A, B INTO :H1, :H2 FROM T WHERE C = :H3");

        assertEquals(SqlStatementKind.SELECT_INTO, result.statementKind());
        assertEquals(List.of("H1", "H2"), result.intoTargets());
        assertEquals(List.of("H1", "H2", "H3"),
                result.hostVariables().stream().map(HostVariableReference::dataName).toList());
        assertEquals(Set.of("T"), Set.copyOf(result.tableNames()));
    }

    @Test
    void plainSelectIsTakenApartFromSelectInto() {
        SqlAnalysisResult result = analyze("SELECT A FROM SCHEMA1.T WHERE C = :H1");

        assertEquals(SqlStatementKind.SELECT, result.statementKind());
        assertEquals(List.of(), result.intoTargets());
        assertEquals(Set.of("SCHEMA1.T"), Set.copyOf(result.tableNames()));
    }

    @Test
    void insertUpdateAndDeleteReportTheirTargetTable() {
        assertEquals(SqlStatementKind.INSERT,
                analyze("INSERT INTO T (A, B) VALUES (:H1, 0)").statementKind());
        assertEquals(SqlStatementKind.UPDATE,
                analyze("UPDATE T SET A = :H1 WHERE B = :H2").statementKind());
        SqlAnalysisResult delete = analyze("DELETE FROM T WHERE B = :H1");
        assertEquals(SqlStatementKind.DELETE, delete.statementKind());
        assertEquals(Set.of("T"), Set.copyOf(delete.tableNames()));
    }

    @Test
    void declareCursorWithHoldCarriesTheFetchOnlyAndOptimizeForClauses() {
        SqlAnalysisResult result = analyze(
                "DECLARE C1 CURSOR WITH HOLD FOR SELECT A, B FROM T WHERE C = :H1"
                        + " FOR FETCH ONLY OPTIMIZE FOR 10 ROWS",
                SqlBlockKind.DECLARE_CURSOR);

        assertEquals(SqlStatementKind.DECLARE_CURSOR, result.statementKind());
        assertEquals("C1", result.cursorName());
        CursorSignals cursor = result.structureSignals().cursor().orElseThrow();
        assertTrue(cursor.forFetchOnly());
        assertFalse(cursor.forReadOnly());
        assertFalse(cursor.forUpdate());
        assertTrue(result.structureSignals().hasOptimizeFor());
        assertFalse(result.structureSignals().hasFetchFirst());
    }

    @Test
    void openFetchAndCloseCarryTheCursorName() {
        assertEquals("C1", analyze("OPEN C1").cursorName());
        assertEquals("C1", analyze("CLOSE C1").cursorName());
        SqlAnalysisResult fetch = analyze("FETCH C1 INTO :H1, :H2");
        assertEquals(SqlStatementKind.FETCH, fetch.statementKind());
        assertEquals("C1", fetch.cursorName());
        assertEquals(List.of("H1", "H2"), fetch.intoTargets());
    }

    @Test
    void fetchFirstOneRowOnlyIsSeenOnASelect() {
        SqlAnalysisResult result =
                analyze("SELECT A INTO :H1 FROM T WHERE C = :H2 FETCH FIRST 1 ROW ONLY");

        assertTrue(result.structureSignals().hasFetchFirst());
        assertFalse(result.structureSignals().hasOptimizeFor());
    }

    @Test
    void dynamicSqlStatementsAreAcceptedAsOther() {
        assertEquals(SqlStatementKind.OTHER, analyze("EXECUTE IMMEDIATE :STMT").statementKind());
        assertEquals(SqlStatementKind.OTHER, analyze("PREPARE S1 FROM :STMT").statementKind());
        assertEquals(SqlStatementKind.OTHER, analyze("COMMIT").statementKind());
    }

    @Test
    void nonExecutableDirectivesAreAcceptedRatherThanRejected() {
        assertEquals(SqlStatementKind.OTHER,
                analyze("WHENEVER SQLERROR GOTO ERR-EXIT", SqlBlockKind.WHENEVER).statementKind());
        assertEquals(SqlStatementKind.OTHER,
                analyze("WHENEVER SQLERROR CONTINUE", SqlBlockKind.WHENEVER).statementKind());
        assertEquals(SqlStatementKind.OTHER,
                analyze("INCLUDE SQLCA", SqlBlockKind.INCLUDE).statementKind());
        assertEquals(SqlStatementKind.OTHER, analyze("BEGIN DECLARE SECTION",
                SqlBlockKind.BEGIN_DECLARE_SECTION).statementKind());
        assertEquals(SqlStatementKind.OTHER, analyze("END DECLARE SECTION",
                SqlBlockKind.END_DECLARE_SECTION).statementKind());
    }

    @Test
    void aStatementTheGrammarRejectsIsReportedAsNotAnalyzable() {
        SqlAnalysisResult result = analyzer.analyze(new SqlBlock("SELECT FROM WHERE",
                SqlBlockKind.EXECUTABLE, new SourcePosition(1, 12), new SourcePosition(1, 20)));

        assertEquals(AnalysisStatus.NOT_ANALYZABLE, result.status());
        assertTrue(result.statusReason().contains("Db2z"), result.statusReason());
    }
}
