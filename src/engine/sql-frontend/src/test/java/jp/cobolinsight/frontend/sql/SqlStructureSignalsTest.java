package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the structure signals for SQL findings S001-S006 come up correctly for synthetic SQL. */
class SqlStructureSignalsTest {

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    private SqlStructureSignals signalsOf(String sqlText, SqlBlockKind kind) {
        SqlAnalysisResult result = analyzer.analyze(
                new SqlBlock(sqlText, kind, new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(AnalysisStatus.ANALYZED, result.status(), "解析対象になること: " + sqlText);
        return result.structureSignals();
    }

    private SqlStructureSignals select(String sqlText) {
        return signalsOf(sqlText, SqlBlockKind.EXECUTABLE);
    }

    // ---- S001 SELECT * ----

    @Test
    void selectStarを検出する() {
        SqlStructureSignals s = select("SELECT * FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :HOST-商品コード");
        assertTrue(s.selectStar());
        assertTrue(s.nonSargablePredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().isEmpty());
    }

    @Test
    void 明示列のselectはselectStarにならない() {
        SqlStructureSignals s = select(
                "SELECT SHOHIN_CD, ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :HOST-商品コード");
        assertFalse(s.selectStar());
        assertTrue(s.nonSargablePredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().isEmpty());
    }

    // ---- S002 non-SARGable predicates ----

    @Test
    void where左辺の関数適用は非sargableかつ列への関数適用() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SUBSTR(SHOHIN_CD,1,3) = 'ABC'");
        assertFalse(s.nonSargablePredicates().isEmpty());
        assertTrue(s.nonSargablePredicates().get(0).contains("SUBSTR"));
        assertFalse(s.functionOnColumnPredicates().isEmpty());
    }

    @Test
    void 先頭パーセントのlikeは非sargable() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD LIKE '%ABC'");
        assertFalse(s.nonSargablePredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().isEmpty());
    }

    @Test
    void 末尾パーセントのlikeは非sargableにならない() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD LIKE 'ABC%'");
        assertTrue(s.nonSargablePredicates().isEmpty());
    }

    @Test
    void where左辺の算術式は非sargable() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE ZAIKO_SU + 1 = 100");
        assertFalse(s.nonSargablePredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().isEmpty());
    }

    @Test
    void 単純比較は非sargableにならない() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :HOST-商品コード");
        assertTrue(s.nonSargablePredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().isEmpty());
    }

    // ---- S003 function applied to a column / CAST ----

    @Test
    void 列への関数適用を日本語ホスト変数付きで検出し原名へ復元する() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE UPPER(SHOHIN_CD) = :HOST-商品コード");
        assertFalse(s.functionOnColumnPredicates().isEmpty());
        String predicate = s.functionOnColumnPredicates().get(0);
        assertTrue(predicate.contains("UPPER"));
        assertTrue(predicate.contains("商品コード"), "原データ名へ復元されること: " + predicate);
        assertFalse(s.nonSargablePredicates().isEmpty());
    }

    @Test
    void castによる列包みを検出する() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE CAST(ZAIKO_SU AS INTEGER) = 100");
        assertFalse(s.functionOnColumnPredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().get(0).contains("CAST"));
        assertTrue(s.nonSargablePredicates().isEmpty(), "CASTはS002ではなくS003で扱う");
    }

    @Test
    void join条件の列への関数適用を検出する() {
        SqlStructureSignals s = select(
                "SELECT Z.ZAIKO_SU FROM SYKDB.ZAIKOM Z JOIN SYKDB.SOKOM S "
                + "ON UPPER(Z.SOKO_CD) = S.SOKO_CD WHERE Z.SHOHIN_CD = :HOST-商品コード");
        assertFalse(s.functionOnColumnPredicates().isEmpty());
        assertTrue(s.functionOnColumnPredicates().get(0).contains("UPPER"));
        assertTrue(s.nonSargablePredicates().isEmpty());
    }

    // ---- S005 FETCH FIRST / S006 OPTIMIZE FOR / reference: WITH UR ----

    @Test
    void fetchFirstを検出する() {
        SqlStructureSignals s = select("SELECT ZAIKO_SU FROM SYKDB.ZAIKOM FETCH FIRST 10 ROWS ONLY");
        assertTrue(s.hasFetchFirst());
        assertFalse(s.hasOptimizeFor());
    }

    @Test
    void optimizeForを検出する() {
        SqlStructureSignals s = select("SELECT ZAIKO_SU FROM SYKDB.ZAIKOM OPTIMIZE FOR 100 ROWS");
        assertTrue(s.hasOptimizeFor());
        assertFalse(s.hasFetchFirst());
    }

    @Test
    void withUrを検出する() {
        SqlStructureSignals s = select("SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WITH UR");
        assertTrue(s.hasWithUr());
    }

    @Test
    void 句が無いselectはs005s006が立たない() {
        SqlStructureSignals s = select(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :HOST-商品コード");
        assertFalse(s.hasFetchFirst());
        assertFalse(s.hasOptimizeFor());
        assertFalse(s.hasWithUr());
    }

    // ---- S004 cursor declaration ----

    @Test
    void forReadOnlyのカーソルを検出する() {
        SqlStructureSignals s = signalsOf(
                "DECLARE SYKZAIKOCUR CURSOR FOR "
                + "SELECT SHOHIN_CD, ZAIKO_SU FROM SYKDB.ZAIKOM FOR READ ONLY",
                SqlBlockKind.DECLARE_CURSOR);
        CursorSignals cursor = s.cursor().orElseThrow();
        assertEquals("SYKZAIKOCUR", cursor.cursorName());
        assertTrue(cursor.forReadOnly());
        assertFalse(cursor.forFetchOnly());
        assertFalse(cursor.forUpdate());
        assertTrue(cursor.forUpdateColumns().isEmpty());
        assertFalse(s.selectStar());
    }

    @Test
    void for句が無いカーソルはread_onlyもupdateも立たない() {
        SqlStructureSignals s = signalsOf(
                "DECLARE SYKZAIKOCUR CURSOR FOR "
                + "SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU FROM SYKDB.ZAIKOM",
                SqlBlockKind.DECLARE_CURSOR);
        CursorSignals cursor = s.cursor().orElseThrow();
        assertFalse(cursor.forReadOnly());
        assertFalse(cursor.forFetchOnly());
        assertFalse(cursor.forUpdate());
        assertFalse(s.hasOptimizeFor());
    }

    @Test
    void forUpdateOfの対象列を検出する() {
        SqlStructureSignals s = signalsOf(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD, ZAIKO_SU FROM SYKDB.ZAIKOM "
                + "FOR UPDATE OF ZAIKO_SU, HIKIATE_SU",
                SqlBlockKind.DECLARE_CURSOR);
        CursorSignals cursor = s.cursor().orElseThrow();
        assertTrue(cursor.forUpdate());
        assertEquals(List.of("ZAIKO_SU", "HIKIATE_SU"), cursor.forUpdateColumns());
        assertFalse(cursor.forReadOnly());
    }

    @Test
    void selectStarのカーソルを検出する() {
        SqlStructureSignals s = signalsOf(
                "DECLARE C2 CURSOR FOR SELECT * FROM SYKDB.ZAIKOM",
                SqlBlockKind.DECLARE_CURSOR);
        assertTrue(s.selectStar());
        assertTrue(s.cursor().isPresent());
    }

    @Test
    void selectでないカーソルなし文はカーソル情報を持たない() {
        SqlStructureSignals s = signalsOf(
                "INSERT INTO SYKDB.ZAIKOM (SHOHIN_CD, ZAIKO_SU) VALUES (:HOST-商品コード, 0)",
                SqlBlockKind.EXECUTABLE);
        assertTrue(s.cursor().isEmpty());
        assertFalse(s.selectStar());
    }
}
