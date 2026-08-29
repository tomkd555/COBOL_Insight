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
 * S001 SELECT * の回避。SELECT 句に * が出現する問い合わせを指摘する。テーブル構造の変更に弱く、
 * 不要な列の転送で I/O を増やす。sql-frontend が算出した selectStar シグナルから判定する。
 */
public final class SelectStarRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("S001", "SELECT * の回避", "可読性・保守性")
            .summary("SELECT 句に * を使う問い合わせを指摘します。")
            .rationale("表へ列を足しただけで転送量と受け側の構造が変わります。"
                    + "必要のない列まで読むため入出力も増えます。")
            .detection("sql-frontend が算出した selectStar シグナルから判定します。")
            .remedy("必要な列を明示して並べます。")
            .example("""
                    SELECT * FROM CUSTOMER WHERE ID = :WS-ID
                    """, """
                    SELECT ID, NAME, ADDR FROM CUSTOMER WHERE ID = :WS-ID
                    """)
            .severity(Severity.MEDIUM)
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
            if (statement.structureSignals().selectStar()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "SELECT * を用いている。テーブル構造の変更に弱く、不要な列の転送で I/O を"
                                + "増やす。必要な列を明示する。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
