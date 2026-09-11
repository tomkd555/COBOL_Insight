package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection for the three SQL advisory rules (S001, S002, S004). Since samples contains no
 * statement that triggers S001 or S002, those are verified with synthetic SQL. S004 fires on
 * the cursor declaration DECLARE SYKZAIKOCUR in samples SYK006. SqlStatementModel is built
 * with the same SqlParser SPI as production ({@link SqlAdviceFixtures}).
 *
 * <p>V2's measurements folded S003 (function applied to an indexed column) into S002, so it
 * now fires both on a predicate that wraps the left side in an expression, a leading-% LIKE,
 * and a comparison where either side wraps a column in a function or CAST.
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

    /** SELECT INTO is a kind of its own since C3, and both rules still read its signals. */
    @Test
    void s001FiresOnSelectStarOfASelectInto() {
        SqlStatementModel model = SqlAdviceFixtures.model(
                "SELECT * INTO :WS-REC FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :WS-CD", 11);
        assertEquals(SqlStatementKind.SELECT_INTO, model.kind());

        List<Finding> findings = new SelectStarRule().evaluate(SqlAdviceFixtures.context(model));
        assertEquals(1, findings.size(), () -> "SELECT INTO の * を1件指摘すること: " + findings);
        assertEquals(11, findings.get(0).location().line());
    }

    @Test
    void s001SilentOnExplicitColumns() {
        AnalysisContext ctx = SqlAdviceFixtures.context(
                SqlAdviceFixtures.model("SELECT SHOHIN_CD, SOKO_CD FROM SYKDB.ZAIKOM", 10));
        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty());
    }

    // ---- S002 Non-sargable predicates ----

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
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "算術で列を包む述語を1件指摘すること: " + findings);
        assertTrue(findings.get(0).message().contains("は索引で絞り込めません。全表走査になります。"),
                findings.get(0).message());
    }

    @Test
    void s002FiresOnASelectIntoWithALeadingWildcardLike() {
        SqlStatementModel model = SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD INTO :WS-CD FROM SYKDB.ZAIKOM WHERE SHOHIN_NM LIKE '%TEST'", 21);
        assertEquals(SqlStatementKind.SELECT_INTO, model.kind());

        List<Finding> findings =
                new NonSargablePredicateRule().evaluate(SqlAdviceFixtures.context(model));
        assertEquals(1, findings.size(),
                () -> "SELECT INTO の先頭%のLIKEを1件指摘すること: " + findings);
        assertEquals(21, findings.get(0).location().line());
    }

    @Test
    void s002SilentOnSargablePredicate() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = 'X'", 20));
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    /** Formerly S003. Since the non-sargable check only looks at the left side, a function
     * on the right side triggers a different message. */
    @Test
    void s002FiresOnFunctionAppliedToColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 'X' = UPPER(SHOHIN_NM)", 30));
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "列への関数適用を1件指摘すること: " + findings);
        assertEquals("S002", findings.get(0).ruleId());
        assertEquals(30, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("は列を関数・CAST で包んでいます。"),
                findings.get(0).message());
    }

    @Test
    void s002FiresOnCastAppliedToColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 30 = CAST(HIKIATE_SU AS INTEGER)", 35));
        assertEquals(1, new NonSargablePredicateRule().evaluate(ctx).size());
    }

    /**
     * A function applied to the left side triggers both the non-sargable check and the
     * function-applied check. Pins down that the same location is not reported twice even
     * after folding in S003 (this is the very reason S003 was folded in).
     */
    @Test
    void s002ReportsALeftHandFunctionOnlyOnce() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE UPPER(SHOHIN_NM) = 'X'", 40));
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "同じ述語を1件だけ指摘すること: " + findings);
    }

    @Test
    void s002SilentOnPlainColumnComparison() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_NM = 'X'", 30));
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    // ---- S004 Cursor declaration and cleanup ----

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
    void s004SilentOnDegradedCursorDeclaration() {
        // The grammar stops at the unbalanced parenthesis, so the FOR clause was never read;
        // its absence from the model is not evidence that the source is missing one.
        SqlStatementModel degraded = SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE (", 65);
        assertEquals(SqlAnalysis.DEGRADED, degraded.analysis());
        assertEquals(SqlStatementKind.DECLARE_CURSOR, degraded.kind());
        assertTrue(new CursorDeclarationRule()
                .evaluate(SqlAdviceFixtures.context(degraded)).isEmpty());
    }

    @Test
    void s001AndS002AreSilentOnADegradedStatement() {
        // The signals are the ones a full parse of this statement would carry, so what keeps the
        // two rules silent is the DEGRADED analysis and nothing else.
        SqlStatementModel degraded = SqlAdviceFixtures.degradedWithSignals(
                "SELECT * FROM SYKDB.ZAIKOM WHERE UPPER(SHOHIN_NM) = 'X' AND", 70,
                new SqlStructureSignals(true, List.of("UPPER(SHOHIN_NM) = 'X'"),
                        List.of("UPPER(SHOHIN_NM) = 'X'"), Optional.empty(), false, false, false));
        assertEquals(SqlAnalysis.DEGRADED, degraded.analysis());
        assertTrue(degraded.structureSignals().selectStar());
        AnalysisContext ctx = SqlAdviceFixtures.context(degraded);
        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty());
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    @Test
    void s004SilentOnUpdatingCursor() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "DECLARE C1 CURSOR FOR SELECT SHOHIN_CD FROM SYKDB.ZAIKOM FOR UPDATE OF SHOHIN_CD", 60));
        assertTrue(new CursorDeclarationRule().evaluate(ctx).isEmpty());
    }

    // ---- Firing on samples SYK006 / SYK007 ----

    @Test
    void samplesSyk006MatchesPrediction() {
        List<SqlStatementModel> models = SqlAdviceFixtures.samplesSqlModels("SYK006.cbl");
        AnalysisContext ctx = SqlAdviceFixtures.samplesContext("SYK006.cbl");

        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty(), "S001は陰性");
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty(), "S002は陰性");

        int cursorLine = firstStartLine(models, SqlStatementKind.DECLARE_CURSOR);
        List<Finding> s004 = new CursorDeclarationRule().evaluate(ctx);
        assertEquals(1, s004.size(), () -> "S004: " + s004);
        assertEquals(cursorLine, s004.get(0).location().line());
    }

    @Test
    void samplesSyk007MatchesPrediction() {
        AnalysisContext ctx = SqlAdviceFixtures.samplesContext("SYK007.cbl");

        assertTrue(new SelectStarRule().evaluate(ctx).isEmpty(), "S001は陰性");
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty(), "S002は陰性");
        assertTrue(new CursorDeclarationRule().evaluate(ctx).isEmpty(), "S004は陰性(カーソル宣言なし)");
    }

    private static int firstStartLine(List<SqlStatementModel> models, SqlStatementKind kind) {
        return models.stream().filter(m -> m.kind() == kind)
                .map(m -> m.range().start().line()).findFirst().orElseThrow();
    }
}
