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
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * R082 NOT IN over a subquery. If the subquery returns a single NULL, the predicate is unknown
 * for every row and the statement selects nothing — no error, no warning, an empty result. It
 * works until the first row with a NULL in that column is inserted.
 */
public final class NotInSubqueryRule implements Rule {

    private static final Pattern NOT_IN_SUBQUERY =
            Pattern.compile("\\bNOT\\s+IN\\s*\\(\\s*SELECT\\b");

    private static final RuleMeta META = RuleMeta
            .named("R082", "副問い合わせに対する NOT IN", RuleCategory.INTEGRITY)
            .technology(Technology.DB2)
            .symptom("NULL による結果 0 件")
            .summary("NOT IN (SELECT …) を検出します。")
            .rationale("副問い合わせが NULL を 1 行でも返すと、NOT IN はすべての行で真にならず、"
                    + "結果が 0 件になります。エラーにも警告にもなりません。")
            .detection("SQL 文の中の NOT IN の直後が副問い合わせのものを検出します。"
                    + "定数の並びに対する NOT IN は対象外です。完全に解析できなかった文も"
                    + "対象外です。")
            .remedy("NOT EXISTS の相関副問い合わせに書き換えるか、副問い合わせに"
                    + "IS NOT NULL の条件を加えてください。")
            .example("""
                    SELECT TOKUI_CD FROM TOKUISAKI
                     WHERE TOKUI_CD NOT IN (SELECT TOKUI_CD FROM JUCHU)
                    """, """
                    SELECT T.TOKUI_CD FROM TOKUISAKI T
                     WHERE NOT EXISTS (SELECT 1 FROM JUCHU J WHERE J.TOKUI_CD = T.TOKUI_CD)
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.EASYTRIEVE, AssetKind.SQL)
            .needs(Needs.SQL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (statement.isFullyAnalysed()
                    && NOT_IN_SUBQUERY.matcher(SqlText.of(statement)).find()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "副問い合わせに NOT IN を使っています。副問い合わせが NULL を返すと、"
                                + "結果が 0 件になります。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
