package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five statement-level SQL rules (R054, R056, R057, R058, R059), each on synthetic SQL built
 * with the same SqlParser SPI as production.
 */
class SqlStatementRuleTest {

    private static final String DECLARE_TABLE =
            "DECLARE SYKDB.ZAIKOM TABLE (SHOHIN_CD CHAR(8) NOT NULL, "
                    + "ZAIKO_SU DECIMAL(7, 0) NOT NULL)";

    private static final String DECLARE_DATED_TABLE =
            "DECLARE SYKDB.JUCHUM TABLE (JUCHU_NO CHAR(10) NOT NULL, JUCHU_BI DATE NOT NULL)";

    // ---- R054 UPDATE/DELETE with no WHERE ----

    @Test
    void r054FiresOnAnUpdateWithNoWhere() {
        List<Finding> findings = new UnqualifiedUpdateRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = 0", 10)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R054", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(10, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("すべての行を更新します"),
                findings.get(0).message());
    }

    @Test
    void r054FiresOnADeleteOfEveryRow() {
        assertEquals(1, new UnqualifiedUpdateRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("DELETE FROM SESSION.TEMP_ZAIKO", 11))).size());
    }

    @Test
    void r054SilentOnAWhereAndOnAPositionedUpdate() {
        assertEquals(List.of(), new UnqualifiedUpdateRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = 0 WHERE SHOHIN_CD = :WS-CD", 12),
                SqlAdviceFixtures.model(
                        "UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = 0 WHERE CURRENT OF CSR1", 13))));
    }

    // ---- R056 SELECT INTO narrowed to one row ----

    @Test
    void r056FiresOnASelectIntoWithNothingNarrowingIt() {
        List<Finding> findings = new UnboundedSelectIntoRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM SYKDB.ZAIKOM", 20)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R056", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("SQLCODE -811"),
                findings.get(0).message());
    }

    @Test
    void r056SilentOnAnAggregateAndOnAWhere() {
        assertEquals(List.of(), new UnboundedSelectIntoRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT COUNT(*) INTO :WS-CNT FROM SYKDB.ZAIKOM", 21),
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :WS-CD",
                        22))));
    }

    /**
     * An aggregate returns one row wherever it stands in the entry, and a FETCH FIRST clause caps
     * the answer whatever its row count. Neither the wrapped aggregate nor the two-row cap can
     * raise the -811 the rule warns about.
     */
    @Test
    void r056SilentOnAWrappedAggregateAndOnAnyFetchFirst() {
        assertEquals(List.of(), new UnboundedSelectIntoRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "SELECT COALESCE(SUM(ZAIKO_SU), 0) INTO :WS-SU FROM SYKDB.ZAIKOM", 23),
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM SYKDB.ZAIKOM FETCH FIRST 2 ROWS ONLY",
                        24))));
    }

    /**
     * COUNT_BIG and the statistical column functions return one row for an ungrouped query exactly
     * as COUNT does, so no -811 can follow them either.
     */
    @Test
    void r056SilentOnTheOtherColumnFunctions() {
        assertEquals(List.of(), new UnboundedSelectIntoRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT COUNT_BIG(*) INTO :WS-CNT FROM SYKDB.ZAIKOM", 25),
                SqlAdviceFixtures.model(
                        "SELECT STDDEV(ZAIKO_SU) INTO :WS-SU FROM SYKDB.ZAIKOM", 26))));
    }

    // ---- R057 a column the declaration does not carry ----

    @Test
    void r057FiresOnAColumnOutsideTheDeclaration() {
        List<Finding> findings = new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(DECLARE_TABLE, 30),
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SUU INTO :WS-SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :WS-CD",
                        35)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R057", findings.get(0).ruleId());
        assertEquals(35, findings.get(0).location().line());
        assertTrue(findings.get(0).message().startsWith("ZAIKOM.ZAIKO_SUU"),
                findings.get(0).message());
    }

    /**
     * A CREATE TABLE drives the check the way a DECLARE TABLE does. R057 targets COBOL only, so the
     * pipeline drops a finding located in a script, but the script's CREATE TABLE still reaches the
     * rule through the statement list and is read as the declaration a program is measured against.
     */
    @Test
    void r057ReadsADdlCreateTableAsTheDeclaration() {
        SqlStatementModel create = SqlAdviceFixtures.model(
                "CREATE TABLE SYKDB.ZAIKOW (SHOHIN_CD CHAR(8) NOT NULL, "
                        + "ZAIKO_SU DECIMAL(7, 0) NOT NULL)", 70);
        assertEquals("SYKDB.ZAIKOW", create.declaredTable().orElseThrow());

        List<Finding> findings = new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                create,
                SqlAdviceFixtures.model("SELECT ZAIKO_SUU INTO :WS-SU FROM SYKDB.ZAIKOW "
                        + "WHERE SHOHIN_CD = :WS-CD", 75)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals(75, findings.get(0).location().line());
        assertTrue(findings.get(0).message().startsWith("ZAIKOW.ZAIKO_SUU"),
                findings.get(0).message());
    }

    /**
     * A DDL library keeps the base CREATE TABLE and the later ALTER TABLE ... ADD COLUMN as
     * separate members, so the columns of a table are the union over every statement that declares
     * any. The second declaration is built by hand: what an ALTER contributes is the frontend's to
     * fill in, and this pins the rule's side of it.
     */
    @Test
    void r057ReadsTheColumnsOfEveryDeclarationOfOneTable() {
        assertEquals(List.of(), new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "CREATE TABLE SYKDB.ZAIKOA (SHOHIN_CD CHAR(8) NOT NULL, "
                                + "ZAIKO_SU DECIMAL(7, 0) NOT NULL)", 80),
                declaring("ALTER TABLE SYKDB.ZAIKOA ADD COLUMN KOSHIN_BI DATE",
                        "SYKDB.ZAIKOA", 81, new SqlDeclaredColumn("KOSHIN_BI", "DATE", true)),
                SqlAdviceFixtures.model("SELECT KOSHIN_BI INTO :WS-BI FROM SYKDB.ZAIKOA "
                        + "WHERE SHOHIN_CD = :WS-CD", 85))));
    }

    /** A DDL statement carrying the table and the columns it declares, without a parse of its own. */
    private static SqlStatementModel declaring(String sql, String table, int line,
            SqlDeclaredColumn... columns) {
        SourcePosition at = new SourcePosition("synthetic.cbl", line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SqlStatementModel.Builder().kind(SqlStatementKind.DDL).declaredTable(table)
                .declaredColumns(List.of(columns))
                .build(sql, sql, List.of(), new SourceRange(at, at), SqlStructureSignals.empty(),
                        SqlAnalysis.FULL, Optional.empty());
    }

    /** A WITH clause defines a query, and a query has no declaration to measure its columns by. */
    @Test
    void r057SilentOnTheColumnsOfACommonTableExpression() {
        SqlStatementModel withClause = SqlAdviceFixtures.model(
                "WITH ZAIKO_GOKEI (SHOHIN_CD, GOKEI_SU) AS "
                        + "( SELECT SHOHIN_CD, SUM(ZAIKO_SU) FROM SYKDB.ZAIKOM "
                        + "GROUP BY SHOHIN_CD ) "
                        + "SELECT GOKEI_SU INTO :WS-SU FROM ZAIKO_GOKEI "
                        + "WHERE ZAIKO_GOKEI.SHOHIN_CD = :WS-CD", 38);
        assertTrue(withClause.cteNames().stream().anyMatch("ZAIKO_GOKEI"::equalsIgnoreCase),
                () -> "the model has to name the WITH clause: " + withClause.cteNames());

        assertEquals(List.of(), new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(DECLARE_TABLE, 30), withClause)));
    }

    @Test
    void r057SilentOnDeclaredColumnsAndOnATableWithNoDeclaration() {
        assertEquals(List.of(), new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(DECLARE_TABLE, 30),
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :WS-CD",
                        36),
                SqlAdviceFixtures.model(
                        "SELECT SOKO_NM INTO :WS-NM FROM SYKDB.SOKOM WHERE SOKO_CD = :WS-CD",
                        37))));
    }

    /**
     * F3: the format keyword of a Db2 built-in is a word of the call, not a column. F5: a name the
     * select list defines with AS is the result of the query, and an ORDER BY naming it names that
     * result. Neither can raise the SQLCODE -206 the rule warns about.
     */
    @Test
    void r057SilentOnAFormatKeywordAndOnASelectListAlias() {
        assertEquals(List.of(), new UndeclaredColumnRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(DECLARE_DATED_TABLE, 43),
                SqlAdviceFixtures.model("SELECT CHAR(JUCHU_BI, ISO) INTO :WS-BI "
                        + "FROM SYKDB.JUCHUM WHERE JUCHU_NO = :WS-NO", 44),
                SqlAdviceFixtures.model(DECLARE_TABLE, 45),
                SqlAdviceFixtures.model("SELECT SUM(ZAIKO_SU) AS GOKEI_SU INTO :WS-SU "
                        + "FROM SYKDB.ZAIKOM GROUP BY SHOHIN_CD ORDER BY GOKEI_SU", 46))));
    }

    // ---- R058 a table named with its schema ----

    @Test
    void r058FiresOnASchemaQualifiedTableAndShipsDisabled() {
        SchemaQualifiedTableRule rule = new SchemaQualifiedTableRule();
        assertEquals(false, rule.meta().defaultEnabled());
        List<Finding> findings = rule.evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :WS-CD",
                        40)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R058", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertTrue(findings.get(0).message().startsWith("SYKDB.ZAIKOM"),
                findings.get(0).message());
    }

    @Test
    void r058SilentOnAnUnqualifiedTableAndOnADeclareTable() {
        assertEquals(List.of(), new SchemaQualifiedTableRule().evaluate(SqlAdviceFixtures.context(
                SqlAdviceFixtures.model(
                        "SELECT ZAIKO_SU INTO :WS-SU FROM ZAIKOM WHERE SHOHIN_CD = :WS-CD", 41),
                SqlAdviceFixtures.model(DECLARE_TABLE, 42))));
    }

    // ---- R059 an INSERT that names no column ----

    @Test
    void r059FiresOnAnInsertWithNoColumnList() {
        List<Finding> findings = new InsertWithoutColumnListRule().evaluate(
                SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                        "INSERT INTO SYKDB.ZAIKOM VALUES (:WS-CD, :WS-SU)", 50)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R059", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertEquals(50, findings.get(0).location().line());
    }

    @Test
    void r059SilentOnAColumnListAndOnASelectThatNamesItsColumns() {
        assertEquals(List.of(), new InsertWithoutColumnListRule().evaluate(
                SqlAdviceFixtures.context(
                        SqlAdviceFixtures.model("INSERT INTO SYKDB.ZAIKOM (SHOHIN_CD, ZAIKO_SU) "
                                + "VALUES (:WS-CD, :WS-SU)", 51),
                        SqlAdviceFixtures.model("INSERT INTO SYKDB.ZAIKOM "
                                + "SELECT SHOHIN_CD, ZAIKO_SU FROM SYKDB.ZAIKOW", 52))));
    }

    /**
     * R054, R056, R057 and R059 read a clause whose absence is their evidence, and a degraded
     * statement carries no clause either way, so they skip it. R058 reads the table names, which a
     * keyword scan recovers from the same statement, so it still reports.
     */
    @Test
    void theFourClauseRulesSkipADegradedStatementAndR058ReadsItAnyway() {
        SqlStatementModel degraded =
                SqlAdviceFixtures.model("UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = (", 60);
        assertEquals(SqlAnalysis.DEGRADED, degraded.analysis());
        AnalysisContext context = SqlAdviceFixtures.context(degraded);
        assertEquals(List.of(), new UnqualifiedUpdateRule().evaluate(context));
        assertEquals(List.of(), new UnboundedSelectIntoRule().evaluate(context));
        assertEquals(List.of(), new UndeclaredColumnRule().evaluate(context));
        assertEquals(List.of(), new InsertWithoutColumnListRule().evaluate(context));

        List<Finding> findings = new SchemaQualifiedTableRule().evaluate(context);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals(60, findings.get(0).location().line());
    }
}
