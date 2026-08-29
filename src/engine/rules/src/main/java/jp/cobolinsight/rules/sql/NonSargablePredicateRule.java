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
 * S002 非SARGableな述語。WHERE 句の左辺が列を関数・演算式で包む述語や、先頭 % の LIKE を指摘する。
 * インデックスによる絞り込みができず全表走査を招く。sql-frontend が算出した nonSargablePredicates
 * から判定し、該当箇所は原データ名へ復元済みのテキストで示す。
 */
public final class NonSargablePredicateRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("S002", "非SARGableな述語の検出", "性能")
            .summary("左辺が列を式で包む述語や、先頭が % の LIKE を指摘します。")
            .rationale("索引による絞り込みができず全表走査になるため、"
                    + "処理時間が表の件数に比例して伸びます。")
            .detection("sql-frontend が算出した nonSargablePredicates から判定し、"
                    + "該当箇所は原データ名へ復元したテキストで示します。")
            .remedy("列を式で包まない形へ書き換えます。前方一致で足りる検索は先頭の % を外します。")
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
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "非SARGableな述語がある: " + String.join(" / ", predicates)
                                + "。インデックスで絞り込めず全表走査を招く。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
