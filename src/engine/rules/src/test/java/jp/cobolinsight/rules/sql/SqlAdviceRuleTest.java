package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL指摘3ルール(S001・S002・S004)の検出。S001 と S002 が陽性になる文は samples に無いため、
 * 合成SQLで検証する。S004 は samples SYK006 のカーソル宣言 DECLARE SYKZAIKOCUR で陽性になる。
 * SqlStatementModel は本番と同じ SqlParser SPI で組む({@link SqlAdviceFixtures})。
 *
 * <p>S002 は V2 の計測で S003(インデックス列への関数適用)を畳んだため、左辺を式で包む述語・
 * 先頭 % の LIKE と、いずれかの辺が列を関数・CAST で包む比較の両方を出す。
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
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "算術で列を包む述語を1件指摘すること: " + findings);
        assertTrue(findings.get(0).message().contains("非SARGableな述語がある"),
                findings.get(0).message());
    }

    @Test
    void s002SilentOnSargablePredicate() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = 'X'", 20));
        assertTrue(new NonSargablePredicateRule().evaluate(ctx).isEmpty());
    }

    /** 旧 S003。右辺の関数は左辺だけを見る非SARGable判定に当たらないため、別の文言で出る。 */
    @Test
    void s002FiresOnFunctionAppliedToColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 'X' = UPPER(SHOHIN_NM)", 30));
        List<Finding> findings = new NonSargablePredicateRule().evaluate(ctx);
        assertEquals(1, findings.size(), () -> "列への関数適用を1件指摘すること: " + findings);
        assertEquals("S002", findings.get(0).ruleId());
        assertEquals(30, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("関数・CAST を適用している"),
                findings.get(0).message());
    }

    @Test
    void s002FiresOnCastAppliedToColumn() {
        AnalysisContext ctx = SqlAdviceFixtures.context(SqlAdviceFixtures.model(
                "SELECT SHOHIN_CD FROM SYKDB.ZAIKOM WHERE 30 = CAST(HIKIATE_SU AS INTEGER)", 35));
        assertEquals(1, new NonSargablePredicateRule().evaluate(ctx).size());
    }

    /**
     * 左辺の関数適用は非SARGable判定と関数適用判定の両方に当たる。S003 を畳んだ後も同じ箇所を
     * 二度報告しないことを固める(畳んだ理由そのもの)。
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

    // ---- samples SYK006 / SYK007 での発火 ----

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
