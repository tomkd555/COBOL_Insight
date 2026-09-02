package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S002 Non-SARGable predicates. Flags predicates that an index cannot narrow down, split
 * into two messages: (a) a predicate whose WHERE-clause left side wraps a column in an
 * expression, plus a LIKE with a leading %. (b) a comparison in a WHERE clause or JOIN
 * condition where either side wraps a column in a function call or CAST. Both are judged
 * from the predicate lists computed by sql-frontend, and the offending location is shown
 * with text restored to the original data names. (b) used to be a separate rule, S003, up
 * through V2, but it is the same finding for the same reason — wrapping a column in a
 * function defeats the index — and a function applied on the left side hit both lists,
 * reporting the same location twice.
 */
public final class NonSargablePredicateRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("S002", "索引を使えない述語", "性能")
            .summary("列を式・関数・CAST で包んだ述語や、先頭が % の LIKE を検出します。")
            .rationale("索引による絞り込みができず全表走査になるため、"
                    + "処理時間が表の件数に比例して伸びます。")
            .detection("WHERE 句の左辺が列を式で包む述語、先頭が % の LIKE、"
                    + "WHERE 句・JOIN 条件でいずれかの辺が列を関数・CAST で包む比較を検出し、"
                    + "該当箇所は原データ名に復元したテキストで示します。"
                    + "双方に当てはまる述語は一件だけ報告します。")
            .remedy("列を式で包まない形に書き換え、関数は列側から外して比較する値の側で"
                    + "変換してください。前方一致で足りる検索は先頭の % を外してください。")
            .example("""
                    WHERE CUST_NAME LIKE '%商事'
                    """, """
                    WHERE CUST_NAME LIKE '商事%'
                    """)
            .severity(Severity.HIGH)
            .commands(Command.SQL_LINT)
            .targets(AssetKind.COBOL)
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
            List<String> predicates = statement.structureSignals().nonSargablePredicates();
            if (!predicates.isEmpty()) {
                findings.add(finding(statement, String.join(" / ", predicates)
                        + " は索引で絞り込めません。全表走査になります。"));
            }
            // A function applied on the left side appears in both lists. To avoid reporting
            // the same location twice, a predicate already listed in the other set is skipped here.
            List<String> functions = statement.structureSignals().functionOnColumnPredicates()
                    .stream().filter(predicate -> !predicates.contains(predicate)).toList();
            if (!functions.isEmpty()) {
                findings.add(finding(statement, String.join(" / ", functions)
                        + " は列を関数・CAST で包んでいます。索引で絞り込めません。"));
            }
        }
        return findings;
    }

    private static Finding finding(SqlStatementModel statement, String message) {
        return Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                SqlAdviceSupport.location(statement));
    }
}
