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
 * S002 非SARGableな述語。索引で絞り込めない述語を、2つの文言に分けて指摘する。
 * (a) WHERE 句の左辺が列を演算式で包む述語、および先頭 % の LIKE。
 * (b) WHERE 句・JOIN 条件の比較で、いずれかの辺が列を関数呼出し・CAST で包む述語。
 * どちらも sql-frontend が算出した述語一覧から判定し、該当箇所は原データ名へ復元済みのテキストで
 * 示す。(b) は V2 まで S003 という別ルールだったが、列を関数で包めば索引が効かないという同じ理由の
 * 同じ指摘であり、左辺の関数適用は両方に当たって同じ箇所を二重に報告していた。
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
            // 左辺の関数適用は両方の一覧に載る。同じ箇所を二度報告しないよう、片方に載っている
            // 述語はここでは扱わない。
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
