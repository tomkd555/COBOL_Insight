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
 * S001 SELECT * の回避。SELECT 句に * が出現する問い合わせを指摘する。テーブル構造の変更に弱く、
 * 不要な列の転送で I/O を増やす。sql-frontend が算出した selectStar シグナルから判定する。
 */
public final class SelectStarRule implements Rule {

    @Override
    public String id() {
        return "S001";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (statement.structureSignals().selectStar()) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "SELECT * を用いている。テーブル構造の変更に弱く、不要な列の転送で I/O を"
                                + "増やす。必要な列を明示する。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
