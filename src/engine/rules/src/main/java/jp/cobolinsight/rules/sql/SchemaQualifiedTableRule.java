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
 * R058 A table named with its schema qualifier. {@code SCHEMA.TABLE} written into the statement
 * ties the program to one Db2 subsystem: moving it between test and production then means editing
 * and recompiling it, where a table written unqualified follows the CURRENT SQLID or the
 * QUALIFIER of the BIND. Whether that matters is a site convention, which is why the rule ships
 * disabled; a site that qualifies on purpose leaves it off.
 *
 * <p>A DECLARE TABLE is not reported: the DCLGEN member is generated with the qualifier and is
 * where the qualifier belongs. One finding per statement, listing the names it qualifies. A
 * degraded statement is read too — a keyword scan recovers the table names.
 */
public final class SchemaQualifiedTableRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R058", "スキーマ名で修飾した表の参照", "可読性・保守性")
            .summary("DECLARE TABLE 以外の SQL 文で、"
                    + "スキーマ名を付けて表を参照しているものを検出します。")
            .rationale("プログラムが 1 つの Db2 サブシステムに固定され、"
                    + "試験環境と本番環境で同じ原始プログラムを使えません。")
            .detection("DECLARE TABLE 以外の SQL 文が参照する表名のうち、"
                    + "スキーマ名で修飾されたものを検出します。"
                    + "COBOL の埋込みSQLと、SQL スクリプトの文の双方を対象とします。"
                    + "現場の規約によるため、この規則は既定で無効です。")
            .remedy("表名からスキーマ名を外し、"
                    + "BIND の QUALIFIER か CURRENT SQLID で修飾してください。")
            .example("""
                    EXEC SQL SELECT ZAIKO_SU INTO :HOST-在庫数量
                             FROM SYKDB.ZAIKOM END-EXEC.
                    """, """
                    EXEC SQL SELECT ZAIKO_SU INTO :HOST-在庫数量
                             FROM ZAIKOM END-EXEC.
                    """)
            .severity(Severity.LOW)
            .defaultEnabled(false)
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
            if (statement.kind() == SqlStatementKind.DECLARE_TABLE) {
                continue;
            }
            List<String> qualified = new ArrayList<>();
            for (String table : statement.referencedTables()) {
                if (table.indexOf('.') > 0 && !qualified.contains(table)) {
                    qualified.add(table);
                }
            }
            if (!qualified.isEmpty()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        String.join("、", qualified) + " はスキーマ名で修飾されています。"
                                + "接続先の Db2 を変えるたびに書き換えが必要になります。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
