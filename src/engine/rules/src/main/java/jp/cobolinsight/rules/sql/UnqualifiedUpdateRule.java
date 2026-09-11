package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * R054 An UPDATE or DELETE with no WHERE clause. Every row of the table is written, which is
 * almost never what a batch program means and is not undone by rerunning the step. A positioned
 * statement ({@code WHERE CURRENT OF}) names its row through the cursor and is not reported; a
 * DELETE of a declared global temporary table is, because the statement reads the same either way
 * and a table the session creates is still emptied by it.
 *
 * <p>A degraded statement is skipped: a keyword scan reads no clause, so {@code hasWhere} is false
 * whether the source carries a WHERE or not.
 */
public final class UnqualifiedUpdateRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R054", "WHERE 句のない UPDATE・DELETE", "SQL")
            .summary("WHERE 句を持たない UPDATE 文・DELETE 文を検出します。")
            .rationale("表のすべての行が対象になり、"
                    + "再実行しても元の値には戻りません。")
            .detection("UPDATE 文・DELETE 文のうち WHERE 句を持たないものを検出します。"
                    + "WHERE CURRENT OF でカーソルの行を指す文は対象外です。"
                    + "COBOL の埋込みSQLと、SQL スクリプトの文の双方を対象とします。"
                    + "完全に解析できなかった文も対象外です。")
            .remedy("鍵の列で行を絞る WHERE 句を書いてください。"
                    + "表全体を空にする意図であれば、TRUNCATE を使ってください。")
            .example("""
                    EXEC SQL UPDATE SYKDB.ZAIKOM SET HIKIATE_SU = 0 END-EXEC.
                    """, """
                    EXEC SQL UPDATE SYKDB.ZAIKOM SET HIKIATE_SU = 0
                             WHERE SHOHIN_CD = :HOST-商品コード END-EXEC.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.SQL)
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
            if (!statement.isFullyAnalysed() || statement.hasWhere()
                    || statement.positionedCursor().isPresent()) {
                continue;
            }
            if (statement.kind() != SqlStatementKind.UPDATE
                    && statement.kind() != SqlStatementKind.DELETE) {
                continue;
            }
            boolean deletes = statement.kind() == SqlStatementKind.DELETE;
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    (deletes ? "DELETE" : "UPDATE") + " 文に WHERE 句がありません。"
                            + SqlAdviceSupport.tableOf(statement) + "のすべての行を"
                            + (deletes ? "削除します。" : "更新します。"),
                    SqlAdviceSupport.location(statement)));
        }
        return findings;
    }
}
