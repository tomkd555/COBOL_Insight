package jp.cobolinsight.rules.sql;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

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
