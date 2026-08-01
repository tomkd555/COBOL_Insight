package jp.cobolinsight.rules.sql;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S002 非SARGableな述語。WHERE 句の左辺が列を関数・演算式で包む述語や、先頭 % の LIKE を指摘する。
 * インデックスによる絞り込みができず全表走査を招く。sql-frontend が算出した nonSargablePredicates
 * から判定し、該当箇所は原データ名へ復元済みのテキストで示す。
 */
public final class NonSargablePredicateRule implements Rule {

    @Override
    public String id() {
        return "S002";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("非SARGableな述語の検出", "性能")
                .summary("左辺が列を式で包む述語や、先頭が % の LIKE を指摘する。")
                .rationale("索引による絞り込みができず全表走査になるため、"
                        + "処理時間が表の件数に比例して伸びる。")
                .detection("sql-frontend が算出した nonSargablePredicates から判定し、"
                        + "該当箇所は原データ名へ復元したテキストで示す。")
                .remedy("列を式で包まない形へ書き換える。前方一致で足りる検索は先頭の % を外す。")
                .example("""
                        WHERE CUST_NAME LIKE '%商事'
                        """, """
                        WHERE CUST_NAME LIKE '商事%'
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            List<String> predicates = statement.structureSignals().nonSargablePredicates();
            if (!predicates.isEmpty()) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "非SARGableな述語がある: " + String.join(" / ", predicates)
                                + "。インデックスで絞り込めず全表走査を招く。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
