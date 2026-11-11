package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R080 Two or more tables in one FROM clause with nothing to join them: no WHERE, no ON. Every
 * row of each table is paired with every row of the others, so the result is the product of the
 * row counts. It returns quickly against test data and runs for hours, or fills the work files,
 * against production volumes.
 */
public final class CartesianJoinRule implements Rule {

    /** A FROM clause up to the keyword that ends it. */
    private static final Pattern FROM = Pattern.compile(
            "\\bFROM\\s+(.+?)(?=\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bHAVING\\b"
                    + "|\\bFETCH\\s+FIRST\\b|\\bFOR\\s+(?:UPDATE|FETCH|READ)\\b|\\bWITH\\s+(?:UR|CS"
                    + "|RS|RR)\\b|\\bUNION\\b|\\)|$)");

    private static final RuleMeta META = RuleMeta
            .named("R080", "結合条件のない複数表の問い合わせ", RuleCategory.PERFORMANCE)
            .technology(Technology.DB2)
            .symptom("直積による長時間実行・作業ファイルの枯渇（SQLCODE -904）")
            .summary("FROM 句に複数の表をコンマで並べ、WHERE 句も ON 句も持たない問い合わせを"
                    + "検出します。")
            .rationale("各表のすべての行の組み合わせが結果になり、件数は行数の積になります。"
                    + "テストデータでは返っても、本番のデータ量では終わりません。")
            .detection("SELECT・SELECT INTO・カーソル宣言・INSERT の問い合わせのうち、最も外側の"
                    + "FROM 句がコンマで 2 つ以上の表を並べ、文のどこにも WHERE 句と JOIN の"
                    + "ON 句がないものを検出します。CROSS JOIN と明示した文、"
                    + "完全に解析できなかった文は対象外です。")
            .remedy("表を結び付ける条件を WHERE 句か JOIN … ON に書いてください。")
            .example("""
                    EXEC SQL DECLARE C1 CURSOR FOR
                        SELECT A.JUCHU_NO, B.SHOHIN_MEI FROM JUCHU A, SHOHIN B END-EXEC.
                    """, """
                    EXEC SQL DECLARE C1 CURSOR FOR
                        SELECT A.JUCHU_NO, B.SHOHIN_MEI FROM JUCHU A, SHOHIN B
                        WHERE A.SHOHIN_CD = B.SHOHIN_CD END-EXEC.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.EASYTRIEVE, AssetKind.SQL)
            .needs(Needs.SQL)
            .build();

    private static final Set<SqlStatementKind> QUERIES = Set.of(SqlStatementKind.SELECT,
            SqlStatementKind.SELECT_INTO, SqlStatementKind.DECLARE_CURSOR,
            SqlStatementKind.INSERT);

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (!QUERIES.contains(statement.kind()) || !statement.isFullyAnalysed()
                    || statement.hasWhere() || statement.referencedTables().size() < 2) {
                continue;
            }
            String text = SqlText.of(statement);
            if (text.contains(" WHERE ") || text.contains(" ON ") || text.contains(" JOIN ")) {
                continue;
            }
            Matcher from = FROM.matcher(text);
            if (from.find() && from.group(1).contains(",")) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        String.join("・", statement.referencedTables())
                                + " を結合条件なしで並べています。"
                                + "すべての行の組み合わせが結果になります。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
