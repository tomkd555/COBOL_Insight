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

    private static final RuleMeta META = RuleMeta.named("S002", "非SARGableな述語の検出", "性能")
            .summary("列を式・関数・CAST で包んだ述語や、先頭が % の LIKE を指摘します。")
            .rationale("索引による絞り込みができず全表走査になるため、"
                    + "処理時間が表の件数に比例して伸びます。")
            .detection("sql-frontend が算出した nonSargablePredicates(WHERE 句の左辺が列を式で包む"
                    + "述語・先頭 % の LIKE)と functionOnColumnPredicates(WHERE 句・JOIN 条件で"
                    + "いずれかの辺が列を関数・CAST で包む比較)から判定し、"
                    + "該当箇所は原データ名へ復元したテキストで示します。")
            .remedy("列を式で包まない形へ書き換えます。関数は列側から外し、比較する値の側で変換"
                    + "します。前方一致で足りる検索は先頭の % を外します。")
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
                findings.add(finding(statement, "非SARGableな述語がある: "
                        + String.join(" / ", predicates)
                        + "。インデックスで絞り込めず全表走査を招く。"));
            }
            // A function applied on the left side appears in both lists. To avoid reporting
            // the same location twice, a predicate already listed in the other set is skipped here.
            List<String> functions = statement.structureSignals().functionOnColumnPredicates()
                    .stream().filter(predicate -> !predicates.contains(predicate)).toList();
            if (!functions.isEmpty()) {
                findings.add(finding(statement, "比較でインデックス列に関数・CAST を適用している: "
                        + String.join(" / ", functions)
                        + "。オプティマイザがインデックスを使用できない。"));
            }
        }
        return findings;
    }

    private static Finding finding(SqlStatementModel statement, String message) {
        return Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                SqlAdviceSupport.location(statement));
    }
}
