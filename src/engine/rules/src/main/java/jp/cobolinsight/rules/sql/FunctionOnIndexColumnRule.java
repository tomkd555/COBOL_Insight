package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S003 インデックス列への関数適用。WHERE 句・JOIN 条件の比較で、片側が列を引数とする関数呼出し
 * または CAST(col AS ..) の述語を指摘する。オプティマイザがインデックスを使用できなくなる。
 * sql-frontend が算出した functionOnColumnPredicates から判定する。
 */
public final class FunctionOnIndexColumnRule implements Rule {

    @Override
    public String id() {
        return "S003";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("インデックス列への関数適用の検出", "性能")
                .summary("WHERE 句・JOIN 条件で、列を関数や CAST で包んだ述語を指摘します。")
                .rationale("列の値そのものと索引を突き合わせられなくなり、"
                        + "索引を使えず全表走査へ落ちます。")
                .detection("sql-frontend が算出した functionOnColumnPredicates から、"
                        + "片側が列を引数とする関数呼出しまたは CAST であるものを指摘します。")
                .remedy("関数を列側から外し、比較する値の側で変換します。")
                .example("""
                        WHERE SUBSTR(CUST_ID, 1, 3) = '100'
                        """, """
                        WHERE CUST_ID LIKE '100%'
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
            List<String> predicates = statement.structureSignals().functionOnColumnPredicates();
            if (!predicates.isEmpty()) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "比較でインデックス列に関数・CAST を適用している: "
                                + String.join(" / ", predicates)
                                + "。オプティマイザがインデックスを使用できない。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
