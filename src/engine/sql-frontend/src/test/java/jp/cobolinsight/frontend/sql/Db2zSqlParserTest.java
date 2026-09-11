package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.sql.HostVariableBinding;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlColumnRef;
import jp.cobolinsight.core.sql.SqlDeclaredColumn;
import jp.cobolinsight.core.sql.SqlStatementModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the conversion to engine-api's SqlParser SPI. */
class Db2zSqlParserTest {

    private final Db2zSqlParser parser = new Db2zSqlParser();

    private static EmbeddedBlock sqlBlock(String text) {
        SourcePosition start = new SourcePosition("SYK006.cbl", 100, 12, -1);
        SourcePosition end = new SourcePosition("SYK006.cbl", 103, 20, -1);
        return new EmbeddedBlock(EmbeddedBlockKind.SQL, text, Map.of(),
                new SourceRange(start, end));
    }

    @Test
    void selectIntoWithHyphenatedHostVariablesIsMapped() {
        EmbeddedBlock block = sqlBlock(
                "SELECT STK_QTY INTO :WS-STK-QTY FROM STOCK WHERE ITEM_CD = :WS-ITEM-CD");

        ParseOutcome<SqlStatementModel> outcome = parser.parse(block);

        SqlStatementModel model = outcome.value().orElseThrow();
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.SELECT_INTO, model.kind());
        assertEquals(block.text(), model.originalText());
        assertTrue(model.mangledText().contains(":HV1"));
        assertTrue(model.mangledText().contains(":HV2"));
        assertEquals(2, model.hostVariables().size());
        HostVariableBinding first = model.hostVariables().get(0);
        assertEquals("WS-STK-QTY", first.originalName());
        assertEquals("HV1", first.mangledName());
        assertTrue(first.indicatorName().isEmpty());
        assertEquals("WS-ITEM-CD", model.hostVariables().get(1).originalName());
        assertTrue(model.referencedTables().contains("STOCK"));
        assertEquals(block.range(), model.range());
    }

    @Test
    void indicatorVariableIsCarriedIntoTheBinding() {
        EmbeddedBlock block = sqlBlock(
                "UPDATE STOCK SET STK_QTY = :WS-QTY:WS-QTY-IND WHERE ITEM_CD = :WS-ITEM-CD");

        SqlStatementModel model = parser.parse(block).value().orElseThrow();

        HostVariableBinding first = model.hostVariables().get(0);
        assertEquals("WS-QTY", first.originalName());
        assertEquals("WS-QTY-IND", first.indicatorName().orElseThrow());
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.UPDATE, model.kind());
    }

    @Test
    void cursorStatementKindsAreMappedToEngineApiKinds() {
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.DECLARE_CURSOR,
                kindOf("DECLARE CUR1 CURSOR FOR SELECT ITEM_CD FROM STOCK"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.OPEN, kindOf("OPEN CUR1"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.FETCH,
                kindOf("FETCH CUR1 INTO :WS-ITEM-CD"));
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.CLOSE, kindOf("CLOSE CUR1"));
    }

    private jp.cobolinsight.core.sql.SqlStatementKind kindOf(String sql) {
        return model(sql).kind();
    }

    private SqlStatementModel model(String sql) {
        SqlStatementModel model = parser.parse(sqlBlock(sql)).value().orElseThrow();
        assertTrue(model.isFullyAnalysed(), () -> "文法が受け付けること: " + model.diagnostic());
        return model;
    }

    /** A fact belongs to the statement itself; the block nested inside it keeps its own. */
    @Test
    void aNestedBlockDoesNotLendItsFactsToTheStatement() {
        assertTrue(model("UPDATE STOCK SET STK_QTY = :WS-QTY WHERE ITEM_CD = :WS-ITEM-CD")
                .hasWhere(), "a searched UPDATE narrows its rows through its own WHERE");
        assertFalse(model("UPDATE STOCK SET STK_QTY = :WS-QTY").hasWhere(),
                "an UPDATE of every row narrows nothing");
        assertTrue(model("DELETE FROM STOCK WHERE ITEM_CD = :WS-ITEM-CD").hasWhere());
        assertFalse(model("DELETE FROM STOCK").hasWhere());

        SqlStatementModel subquery = model("SELECT ITEM_CD INTO :WS-ITEM-CD FROM STOCK"
                + " WHERE ITEM_CD IN (SELECT ITEM_CD FROM ORDERS WHERE QTY > 0)");
        assertTrue(subquery.hasWhere(), "the statement has a WHERE of its own here");
        assertFalse(model("SELECT ITEM_CD INTO :WS-ITEM-CD FROM STOCK"
                        + " ORDER BY ITEM_CD FETCH FIRST 1 ROW ONLY").hasWhere(),
                "no WHERE of its own");

        SqlStatementModel cte = model("WITH RECENT (ITEM_CD) AS"
                + " (SELECT ITEM_CD FROM ORDERS WHERE QTY > 0)"
                + " SELECT COUNT(*) INTO :WS-CNT FROM RECENT");
        assertEquals(List.of("COUNT(*)"), cte.selectList(),
                "the select list of a common table expression is not the statement's");
        assertFalse(cte.hasWhere(), "the WHERE of a common table expression is not the statement's");
        assertEquals(List.of("ORDERS"), cte.referencedTables(),
                "RECENT names the common table expression; the table behind it is ORDERS");
    }

    /** A name the statement defines itself is no table, and no table is a column's qualifier. */
    @Test
    void onlyARealTableIsBookedAsATable() {
        assertEquals(List.of("STOCK", "ORDERS"),
                model("SELECT S.ITEM_CD INTO :WS-ITEM-CD FROM STOCK S, ORDERS O"
                        + " WHERE S.ITEM_CD = O.ITEM_CD").referencedTables(),
                "S and O are correlation names, not tables");
        assertEquals(List.of("STOCK"),
                model("DECLARE C1 CURSOR FOR SELECT S.* FROM STOCK S").referencedTables(),
                "the qualifier of a star is a correlation, not a table");
    }

    /** A column reference stands in a clause that refers to one, and resolves in its own block. */
    @Test
    void aColumnReferenceIsReadInTheBlockThatDefinesItsQualifier() {
        assertEquals(List.of(), model("SET CURRENT PATH = SYSIBM").columnRefs(),
                "a schema on a special register is no column");

        List<SqlColumnRef> refs = model("SELECT A.ITEM_CD INTO :WS-ITEM-CD FROM STOCK A"
                + " WHERE EXISTS (SELECT 1 FROM ORDERS A WHERE A.ITEM_CD = :WS-ITEM-CD)")
                .columnRefs();
        assertEquals(List.of(new SqlColumnRef(Optional.of("STOCK"), "ITEM_CD"),
                        new SqlColumnRef(Optional.of("ORDERS"), "ITEM_CD")), refs,
                "the inner A is the ORDERS of the inner block");
    }

    /** The values of an INSERT line up with its columns, so only the first row is recorded. */
    @Test
    void anInsertRecordsTheFirstValuesRowOnly() {
        SqlStatementModel insert =
                model("INSERT INTO STOCK (ITEM_CD, STK_QTY) VALUES ('A', 1), ('B', 2)");

        assertEquals(List.of("ITEM_CD", "STK_QTY"), insert.insertColumns());
        assertEquals(List.of("'A'", "1"), insert.insertValues());
    }

    /** A table read and written by one statement carries both letters. */
    @Test
    void theAccessLettersOfOneTableAreMerged() {
        assertEquals(Map.of("STOCK", "RC"),
                model("INSERT INTO STOCK (ITEM_CD) SELECT ITEM_CD FROM STOCK").tableAccess());
        assertEquals(Map.of("STOCK", "RU", "ORDERS", "R"),
                model("DECLARE C1 CURSOR FOR SELECT S.ITEM_CD FROM STOCK S, ORDERS O"
                        + " WHERE S.ITEM_CD = O.ITEM_CD FOR UPDATE OF STK_QTY").tableAccess(),
                "only the table the cursor updates takes the U");
    }

    /** A multi-row INSERT says how many rows it writes, and its values line up with its columns. */
    @Test
    void aMultiRowInsertCarriesItsRowCountAndOneValuePerColumn() {
        SqlStatementModel insert = model("INSERT INTO STOCK (ITEM_CD, STK_QTY)"
                + " VALUES (:WS-ITEM-CD, :WS-QTY) FOR 3 ROWS ATOMIC");

        assertEquals(3, insert.rowsetSize().orElseThrow());
        assertEquals(List.of("ITEM_CD", "STK_QTY"), insert.insertColumns());
        assertEquals(List.of(":WS-ITEM-CD", ":WS-QTY"), insert.insertValues());
    }

    /** A row count is the one a FOR n ROWS names, not one a string literal happens to spell. */
    @Test
    void aRowCountInsideAStringLiteralIsData() {
        assertTrue(model("INSERT INTO STOCK (ITEM_CD) VALUES ('FOR 9 ROWS')")
                .rowsetSize().isEmpty());
    }

    /** A clause belongs to the statement, so a subquery of a SET or a WHERE lends it nothing. */
    @Test
    void aSubqueryOfAnUpdateOrDeleteIsNotTheStatementsQuery() {
        SqlStatementModel update =
                model("UPDATE STOCK SET STK_QTY = (SELECT MAX(QTY) FROM ORDERS)"
                        + " WHERE ITEM_CD = :WS-ITEM-CD");
        assertEquals(List.of(), update.selectList(), "the subquery's select list is not the UPDATE's");
        assertFalse(update.hasOrderBy());
        assertTrue(update.hasWhere());

        SqlStatementModel delete = model(
                "DELETE FROM STOCK WHERE ITEM_CD IN (SELECT ITEM_CD FROM ORDERS ORDER BY ITEM_CD)");
        assertEquals(List.of(), delete.selectList());
        assertFalse(delete.hasOrderBy(), "the subquery's ORDER BY is not the DELETE's");
        assertTrue(delete.hasWhere());
    }

    /** An INSERT reads its own SELECT's WHERE, and a set operation any branch's. */
    @Test
    void theWhereOfEveryOwnBranchCounts() {
        assertTrue(model("INSERT INTO STOCK (ITEM_CD) SELECT ITEM_CD FROM ORDERS WHERE QTY > 0")
                .hasWhere(), "the SELECT an INSERT reads from is the statement's own");
        assertFalse(model("INSERT INTO STOCK (ITEM_CD) SELECT ITEM_CD FROM ORDERS").hasWhere());
        assertTrue(model("DECLARE C1 CURSOR FOR SELECT ITEM_CD FROM STOCK"
                        + " EXCEPT SELECT ITEM_CD FROM ORDERS WHERE QTY > 0").hasWhere(),
                "the second branch of a set operation narrows the rows too");
    }

    /** A correlation that spells a real table must not erase the table. */
    @Test
    void aCorrelationSpelledLikeItsTableKeepsTheTable() {
        SqlStatementModel select = model("SELECT * FROM STOCK STOCK WHERE STOCK.A = 1");

        assertEquals(List.of("STOCK"), select.referencedTables());
        assertEquals(Map.of("STOCK", "R"), select.tableAccess());
    }

    /** A qualified FOR UPDATE OF column names which table of the cursor query is updated. */
    @Test
    void aQualifiedForUpdateOfNamesTheUpdatedTable() {
        SqlStatementModel cursor = model("DECLARE C1 CURSOR FOR"
                + " SELECT S.ITEM_CD FROM STOCK S, ORDERS O WHERE S.ITEM_CD = O.ITEM_CD"
                + " FOR UPDATE OF O.QTY");

        assertEquals(Map.of("STOCK", "R", "ORDERS", "RU"), cursor.tableAccess());
    }

    /** A MERGE refers to columns on its WHEN predicate and on its INSERT branch. */
    @Test
    void aMergeWhenPredicateAndInsertColumnListAreColumnReferences() {
        List<SqlColumnRef> refs = model("MERGE INTO STOCK AS T USING (VALUES (:WS-ITEM-CD, :WS-QTY))"
                + " AS S (K, Q) ON T.ITEM_CD = S.K"
                + " WHEN MATCHED AND T.STK_QTY > 0 THEN UPDATE SET STK_QTY = S.Q"
                + " WHEN NOT MATCHED THEN INSERT (ITEM_CD, STK_QTY) VALUES (S.K, S.Q)").columnRefs();

        assertTrue(refs.contains(new SqlColumnRef(Optional.of("STOCK"), "STK_QTY")),
                () -> "the WHEN predicate refers to a column: " + refs);
        assertTrue(refs.contains(new SqlColumnRef(Optional.empty(), "ITEM_CD")),
                () -> "the INSERT branch column list refers to columns: " + refs);
    }

    /**
     * A COBOL comment line standing inside the block belongs to the program. Its words would
     * otherwise reach the grammar as identifiers, and an asterisk in column 7 as a multiplication.
     */
    @Test
    void aCommentLineInsideTheBlockIsNotStatementText() {
        SqlStatementModel plain = model("SELECT ITEM_CD, STK_QTY\n"
                + "               FROM STOCK\n"
                + "              WHERE ITEM_CD = :WS-ITEM-CD");
        SqlStatementModel commented = model("SELECT ITEM_CD, STK_QTY\n"
                + "      *    FROM ORDERS WHERE 絞り込み条件は在庫区分です\n"
                + "               FROM STOCK\n"
                + "              WHERE ITEM_CD = :WS-ITEM-CD");

        assertEquals(plain.selectList(), commented.selectList());
        assertEquals(plain.columnRefs(), commented.columnRefs());
        assertEquals(plain.referencedTables(), commented.referencedTables());
        assertEquals(plain.tableAccess(), commented.tableAccess());
    }

    /**
     * A statement laid out in no columns at all — the shape the SQL script asset kind hands in —
     * keeps every line, because a leading asterisk there is the select list and not an indicator.
     */
    @Test
    void aScriptStatementWithNoCobolColumnsKeepsEveryLine() {
        SqlStatementModel script = parser.parse(scriptBlock("SELECT\n*\nFROM STOCK"))
                .value().orElseThrow();

        assertTrue(script.isFullyAnalysed(), () -> "" + script.diagnostic());
        assertEquals(List.of("*"), script.selectList());
        assertEquals(List.of("STOCK"), script.referencedTables());
    }

    /** A block that starts in column 1, the way a bare script statement does. */
    private static EmbeddedBlock scriptBlock(String text) {
        SourcePosition start = new SourcePosition("STOCK.sql", 1, 1, -1);
        SourcePosition end = new SourcePosition("STOCK.sql", 3, 1, -1);
        return new EmbeddedBlock(EmbeddedBlockKind.SQL, text, Map.of(),
                new SourceRange(start, end));
    }

    /** A column a WITH clause defines belongs to that query, not to the table behind it. */
    @Test
    void aColumnOfACommonTableExpressionIsAttributedToIt() {
        SqlStatementModel cte = model("WITH RECENT (ITEM_CD, TOTAL) AS"
                + " (SELECT ITEM_CD, SUM(QTY) FROM ORDERS WHERE QTY > 0 GROUP BY ITEM_CD)"
                + " SELECT ITEM_CD, TOTAL INTO :WS-ITEM-CD, :WS-QTY FROM RECENT");

        assertEquals(List.of("RECENT"), cte.cteNames());
        assertEquals(List.of("ORDERS"), cte.referencedTables());
        assertTrue(cte.columnRefs().contains(new SqlColumnRef(Optional.of("RECENT"), "TOTAL")),
                () -> "the outer TOTAL comes from RECENT: " + cte.columnRefs());
        assertTrue(cte.columnRefs().contains(new SqlColumnRef(Optional.empty(), "QTY")),
                () -> "a column of the base table the WITH reads stays unqualified: "
                        + cte.columnRefs());
    }

    /**
     * A built-in function's keyword argument is not a column, however the grammar reads it: the
     * date format of CHAR, the unit of a labelled duration, the end TRIM works from.
     */
    @Test
    void aFunctionsKeywordArgumentIsNotAColumn() {
        assertEquals(List.of(new SqlColumnRef(Optional.empty(), "KOSHIN_YMD"),
                        new SqlColumnRef(Optional.empty(), "ITEM_CD")),
                model("UPDATE STOCK SET KOSHIN_YMD = REPLACE(CHAR(CURRENT DATE, ISO), '-', '')"
                        + " WHERE ITEM_CD = :WS-ITEM-CD").columnRefs(),
                "ISO names the date format of CHAR, not a column");

        assertEquals(List.of(new SqlColumnRef(Optional.empty(), "ITEM_CD"),
                        new SqlColumnRef(Optional.empty(), "KOSHIN_YMD")),
                model("SELECT ITEM_CD INTO :WS-ITEM-CD FROM STOCK"
                        + " WHERE KOSHIN_YMD < CURRENT DATE + 1 MONTH").columnRefs(),
                "MONTH is the unit of the duration, not a column");

        assertEquals(List.of(new SqlColumnRef(Optional.empty(), "ITEM_NM")),
                model("SELECT TRIM(LEADING ' ' FROM ITEM_NM) INTO :WS-NM FROM STOCK").columnRefs(),
                "LEADING is the end TRIM works from, not a column");
    }

    /** The word list holds only inside a function; a column really called MONTH is a column. */
    @Test
    void aColumnNamedLikeAKeywordArgumentIsStillAColumn() {
        assertEquals(List.of(new SqlColumnRef(Optional.empty(), "MONTH"),
                        new SqlColumnRef(Optional.empty(), "DAY")),
                model("SELECT MONTH INTO :WS-MONTH FROM STOCK WHERE DAY = 1").columnRefs());
        assertEquals(List.of(new SqlColumnRef(Optional.of("STOCK"), "MONTH")),
                model("SELECT MAX(S.MONTH) INTO :WS-MONTH FROM STOCK S").columnRefs(),
                "a qualified name is a column even inside a function");
    }

    /** A DECLARE TABLE and a CREATE TABLE name the table they declare. */
    @Test
    void aDeclarationNamesTheTableItDeclares() {
        assertEquals("SYKDB.ZAIKOM",
                model("DECLARE SYKDB.ZAIKOM TABLE (ITEM_CD CHAR(8) NOT NULL)")
                        .declaredTable().orElseThrow());
        assertEquals("STOCK", model("CREATE TABLE STOCK (ITEM_CD CHAR(8) NOT NULL)")
                .declaredTable().orElseThrow());
        assertTrue(model("SELECT ITEM_CD INTO :WS-ITEM-CD FROM STOCK").declaredTable().isEmpty());
    }

    /**
     * The facts no fixture under corpus-constructs reaches, so the snapshots cannot pin them.
     * INCLUDE is one of them for good: Che4z expands an EXEC SQL INCLUDE while it reads the
     * program, so no block of that kind ever reaches the pipeline.
     */
    @Test
    void theFactsNoFixtureReachesAlsoComeOffTheParseTree() {
        SqlStatementModel include = model("INCLUDE SQLCA");
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.INCLUDE, include.kind());
        assertEquals("SQLCA", include.includeMember().orElseThrow());

        SqlStatementModel create =
                model("CREATE TABLE STOCK (ITEM_CD CHAR(8) NOT NULL, QTY INTEGER)");
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.DDL, create.kind());
        assertEquals(List.of(new SqlDeclaredColumn("ITEM_CD", "CHAR(8)", false),
                new SqlDeclaredColumn("QTY", "INTEGER", true)), create.declaredColumns());
        assertEquals(Map.of(), create.tableAccess(), "宣言は行を読み書きしないこと");

        SqlStatementModel fetch =
                model("FETCH NEXT ROWSET FROM CUR1 FOR :WS-ROWS ROWS INTO :WS-QTY");
        assertEquals("WS-ROWS", fetch.rowsetHostVariable().orElseThrow());
        assertTrue(fetch.rowsetSize().isEmpty());

        SqlStatementModel merge = model("MERGE INTO STOCK AS T USING (VALUES (:WS-ITEM-CD))"
                + " AS S (K) ON T.ITEM_CD = S.K WHEN MATCHED THEN DELETE"
                + " WHEN NOT MATCHED THEN INSERT (ITEM_CD) VALUES (S.K)");
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.MERGE, merge.kind());
        assertEquals(Map.of("STOCK", "CD"), merge.tableAccess(),
                "no UPDATE branch, so the merge updates no row");
    }

    /** A merge books the letters its branches earn, not the ones a merge might carry. */
    @Test
    void aMergeBooksOnlyWhatItsBranchesDo() {
        assertEquals(Map.of("STOCK", "U"),
                model("MERGE INTO STOCK AS T USING (VALUES (:WS-ITEM-CD)) AS S (K)"
                        + " ON T.ITEM_CD = S.K WHEN MATCHED THEN UPDATE SET QTY = 0")
                        .tableAccess());
        assertEquals(Map.of("STOCK", "C"),
                model("MERGE INTO STOCK AS T USING (VALUES (:WS-ITEM-CD)) AS S (K)"
                        + " ON T.ITEM_CD = S.K WHEN NOT MATCHED THEN INSERT (ITEM_CD) VALUES (S.K)")
                        .tableAccess());
    }

    /** An EXCLUSIVE lock is taken to write the table; a SHARE lock reads it and changes no row. */
    @Test
    void aLockTableBooksTheAccessItsModeTakes() {
        assertEquals(Map.of("STOCK", "U"), model("LOCK TABLE STOCK IN EXCLUSIVE MODE")
                .tableAccess());
        assertEquals(Map.of("STOCK", "R"), model("LOCK TABLE STOCK IN SHARE MODE").tableAccess());
    }

    /** A column an ALTER TABLE adds belongs to the table as much as one the CREATE wrote. */
    @Test
    void anAlterTableThatAddsAColumnDeclaresIt() {
        SqlStatementModel added = model("ALTER TABLE SYKDB.ZAIKOM ADD COLUMN HIKIATE_SU INTEGER");
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.DDL, added.kind());
        assertEquals("SYKDB.ZAIKOM", added.declaredTable().orElseThrow());
        assertEquals(List.of(new SqlDeclaredColumn("HIKIATE_SU", "INTEGER", true)),
                added.declaredColumns());

        assertEquals(List.of(new SqlDeclaredColumn("MEMO", "CHAR(40)", false)),
                model("ALTER TABLE STOCK ADD MEMO CHAR(40) NOT NULL").declaredColumns(),
                "the word COLUMN is optional");

        assertTrue(model("ALTER TABLE STOCK DROP COLUMN MEMO RESTRICT").declaredTable().isEmpty(),
                "an alter that adds no column declares nothing");
    }

    @Test
    void execSqlWrapperIsStrippedBeforeAnalysis() {
        EmbeddedBlock block = sqlBlock("""
                EXEC SQL
                    SELECT STK_QTY INTO :WS-STK-QTY
                      FROM STOCK
                     WHERE ITEM_CD = :WS-ITEM-CD
                END-EXEC.""");

        SqlStatementModel model = parser.parse(block).value().orElseThrow();

        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.SELECT_INTO, model.kind());
        assertTrue(model.referencedTables().contains("STOCK"));
        assertEquals(block.text(), model.originalText(), "原文は抽出テキストのまま保持すること");
    }

    @Test
    void unparseableSqlBecomesADegradedModelRatherThanAFailure() {
        EmbeddedBlock block = sqlBlock("SELECT FROM WHERE");

        SqlStatementModel model = parser.parse(block).value().orElseThrow();

        assertEquals(SqlAnalysis.DEGRADED, model.analysis());
        assertEquals(jp.cobolinsight.core.sql.SqlStatementKind.SELECT, model.kind());
        assertFalse(model.diagnostic().orElseThrow().isBlank());
        assertEquals(100, model.range().start().line());
    }

    @Test
    void structureSignalsAreCarriedIntoTheModel() {
        SqlStatementModel star = parser.parse(sqlBlock("SELECT * FROM STOCK")).value().orElseThrow();
        assertTrue(star.structureSignals().selectStar());

        SqlStatementModel cursor = parser.parse(sqlBlock(
                "DECLARE CUR1 CURSOR FOR SELECT ITEM_CD FROM STOCK FOR READ ONLY"))
                .value().orElseThrow();
        assertTrue(cursor.structureSignals().cursor().orElseThrow().forReadOnly());
        assertEquals("CUR1", cursor.structureSignals().cursor().orElseThrow().cursorName());
    }
}
