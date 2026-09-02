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
 * S001 Avoid SELECT *. Flags queries where a * appears in the SELECT clause. This is fragile
 * against changes to the table structure and increases I/O by transferring unneeded columns.
 * Judged from the selectStar signal computed by sql-frontend.
 */
public final class SelectStarRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("S001", "SELECT * の使用", "可読性・保守性")
            .summary("SELECT 句に * を使う問い合わせを検出する。")
            .rationale("表に列を足しただけで転送量と受け側の構造が変わる。"
                    + "必要のない列まで読むため入出力も増える。")
            .detection("sql-frontend が算出した selectStar シグナルから検出する。")
            .remedy("必要な列を明示して並べる。")
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
                        "SELECT * を用いている。表の構造の変更に弱く、不要な列の転送で入出力を"
                                + "増やす。必要な列を明示する。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
