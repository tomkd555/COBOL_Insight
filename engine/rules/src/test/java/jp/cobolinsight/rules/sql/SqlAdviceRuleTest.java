package jp.cobolinsight.rules.sql;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.sql.SqlStatementKind;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL助言6ルール(S001〜S006)の検出。S001〜S003 の陽性は samples に無いため合成SQLで検証し、
 * S004/S006 は samples SYK006 の DECLARE SYKZAIKOCUR で陽性、S005 は構文一律で該当文に発火する。
 * SqlStatementModel は本番と同じ SqlParser SPI で組む({@link SqlAdviceFixtures})。
 */
class SqlAdviceRuleTest {

    // ---- S001 SELECT * ----

    @Test
    void s001FiresOnSelectStar() {
        AnalysisContext ctx = SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT * FROM SYKDB.ZAIKOM", 10));
        List<Finding> findings = new SelectStarRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "SELECT * を1件指摘すること: " + findings);
        assertEquals("S001", findings.get(0).ruleId());
        assertEquals(10, findings.get(0).location().line());
    }

    @Test
    void s001SilentOnExplicitColumns() {
        AnalysisContext ctx = SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT SHOHIN_CD, SOKO_CD FROM SYKDB.ZAIKOM", 10));
        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty());
    }

    // ---- S002 非SARGableな述語 ----

    @Test
    void s002FiresOnLeadingWildcardLike() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_NM LIKE '%TEST'", 20));
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "先頭%のLIKEを1件指摘すること: " + findings);
        assertEquals(20, findings.get(0).location().line());
    }

    @Test
    void s002FiresOnColumnWrappedInArithmetic() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE ZAIKO_SU - 5 = 10", 20));
        assertEquals(1, new NonSargablePredicateRule().evaluate(ctx).size());
        // 算術で列を包む述語はS003(関数・CAST)には当たらない
        assertTrue(new FunctionOnIndexColumnRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s002SilentOnSargablePredicate() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = 'X'", 20));
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    // ---- S003 インデックス列への関数適用 ----

    @Test
    void s003FiresOnFunctionOnColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 'X' = UPPER(SHOHIN_NM)", 30));
        List<Finding> findings = new FunctionOnIndexColumnRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "列への関数適用を1件指摘すること: " + findings);
        assertEquals(30, findings.get(0).location().line());
        // 右辺の関数なので、左辺のみを見るS002には当たらない
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s003FiresOnCastOnColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 30 = CAST(HIKIATE_SU AS INTEGER)", 35));
        assertEquals(1, new FunctionOnIndexColumnRule().evaluate(ctx).size());
    }

    @Test
    void s003SilentOnPlainColumnComparison() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_NM = 'X'", 30));
        assertTrue(new FunctionOnIndexColumnRule().evaluate(ctx).isEmpty());
    }

    // ---- S004 カーソルの宣言・後始末 ----

    @Test
    void s004FiresOnReadOnlyCursorMissingForClause() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM", 50));
        List<Finding> findings = new CursorDeclarationRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "FOR句欠如の読取専用カーソルを1件指摘すること: " + findings);
        assertEquals(50, findings.get(0).location().line());
    }

    @Test
    void s004SilentWhenForReadOnly() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM FOR READ ONLY", 55));
        assertTrue(new CursorDeclarationRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s004SilentOnUpdatingCursor() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM FOR UPDATE OF SHOHIN_CD", 60));
        assertTrue(new CursorDeclarationRule().evaluate(ctx).isEmpty());
    }

    // ---- S005 FETCH FIRST 句の未使用 ----

    @Test
    void s005FiresOnSelectAndCursorWithoutFetchFirst() {
        AnalysisContext ctx = SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT SHOHIN_CD FROM SYKDB.ZAIKOM", 70),
                SqlAdviceFixtures.model(
                        "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM", 75));
        Set<Integer> lines = new FetchFirstMissingRule().evaluate(ctx).stream()
                .map(f -> f.location().line()).collect(Collectors.toSet());
        assertEquals(Set.of(70, 75), lines);
    }

    @Test
    void s005SilentWhenFetchFirstPresent() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM FETCH FIRST 1 ROWS ONLY", 70));
        assertTrue(new FetchFirstMissingRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s005SilentOnNonSelectStatement() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = 0 WHERE SHOHIN_CD = 'X'", 70));
        assertTrue(new FetchFirstMissingRule().evaluate(ctx).isEmpty());
    }

    // ---- S006 OPTIMIZE FOR 句の未使用 ----

    @Test
    void s006FiresOnCursorWithoutOptimizeFor() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM", 80));
        List<Finding> findings = new OptimizeForMissingRule().evaluate(ctx);
        assertEquals(1, findings.size());
        assertEquals(80, findings.get(0).location().line());
    }

    @Test
    void s006SilentWhenOptimizeForPresent() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM OPTIMIZE FOR 1 ROWS", 85));
        assertTrue(new OptimizeForMissingRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s006SilentOnPlainSelect() {
        AnalysisContext ctx = SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT SHOHIN_CD FROM SYKDB.ZAIKOM", 80));
        assertTrue(new OptimizeForMissingRule().evaluate(ctx).isEmpty());
    }

    // ---- samples SYK006 / SYK007 の発火(m5-spec §5.2 予測との突合) ----

    @Test
    void samplesSyk006MatchesPrediction() {
        List<SqlStatementModel> models = SqlAdviceFixtures.samplesSqlModels("SYK006.cbl");
        AnalysisContext ctx = SqlAdviceFixtures.samplesContext("SYK006.cbl");

        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty(), "S001は陰性");
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty(), "S002は陰性");
        assertTrue(new FunctionOnIndexColumnRule().evaluate(ctx).isEmpty(), "S003は陰性");

        int cursorLine = firstStartLine(models, SqlStatementKind.DECLARE_CURSOR);
        List<Finding> s004 = new CursorDeclarationRule().evaluate(ctx);
        assertEquals(1, s004.size(), () -> "S004: " + s004);
        assertEquals(cursorLine, s004.get(0).location().line());

        List<Finding> s006 = new OptimizeForMissingRule().evaluate(ctx);
        assertEquals(1, s006.size(), () -> "S006: " + s006);
        assertEquals(cursorLine, s006.get(0).location().line());

        Set<Integer> s005Lines = new FetchFirstMissingRule().evaluate(ctx).stream()
                .map(f -> f.location().line()).collect(Collectors.toSet());
        assertEquals(startLines(models, SqlStatementKind.SELECT, SqlStatementKind.DECLARE_CURSOR),
                s005Lines, "S005はSELECT(INTO含む)とカーソル宣言に一律発火");
    }

    @Test
    void samplesSyk007MatchesPrediction() {
        List<SqlStatementModel> models = SqlAdviceFixtures.samplesSqlModels("SYK007.cbl");
        AnalysisContext ctx = SqlAdviceFixtures.samplesContext("SYK007.cbl");

        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty(), "S001は陰性");
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty(), "S002は陰性");
        assertTrue(new FunctionOnIndexColumnRule().evaluate(ctx).isEmpty(), "S003は陰性");
        assertTrue(new CursorDeclarationRule().evaluate(ctx).isEmpty(), "S004は陰性(カーソル宣言なし)");
        assertTrue(new OptimizeForMissingRule().evaluate(ctx).isEmpty(), "S006は陰性(カーソル宣言なし)");

        Set<Integer> s005Lines = new FetchFirstMissingRule().evaluate(ctx).stream()
                .map(f -> f.location().line()).collect(Collectors.toSet());
        assertEquals(startLines(models, SqlStatementKind.SELECT), s005Lines,
                "S005はSELECT INTOに発火");
    }

    private static int firstStartLine(List<SqlStatementModel> models, SqlStatementKind kind) {
        return models.stream().filter(m -> m.kind() == kind)
                .map(m -> m.range().start().line()).findFirst().orElseThrow();
    }

    private static Set<Integer> startLines(List<SqlStatementModel> models,
            SqlStatementKind... kinds) {
        Set<SqlStatementKind> selected = Set.of(kinds);
        return models.stream().filter(m -> selected.contains(m.kind()))
                .map(m -> m.range().start().line()).collect(Collectors.toSet());
    }
}
