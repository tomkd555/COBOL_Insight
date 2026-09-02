package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S004 Cursor declaration and cleanup. Flags a DECLARE CURSOR that has no FOR UPDATE
 * (i.e. does not perform updates) but also specifies neither FOR READ ONLY nor FOR FETCH
 * ONLY. Such a cursor is treated as updatable, which causes lock contention. Matching
 * OPEN against CLOSE is R019's responsibility and is not handled by this rule.
 */
public final class CursorDeclarationRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("S004", "読み取り専用指定のないカーソル宣言", "性能")
                    .summary("更新を伴わないのに FOR READ ONLY・FOR FETCH ONLY の"
                            + "いずれも指定しないカーソル宣言を検出する。")
                    .rationale("更新可能カーソルとして扱われるため、"
                            + "必要のない行ロックを取ってロック競合を招く。")
                    .detection("DECLARE CURSOR のうち FOR UPDATE を持たず、"
                            + "FOR READ ONLY・FOR FETCH ONLY のいずれも持たないものを検出する。"
                            + "OPEN と CLOSE の突き合わせは R019 が担う。")
                    .remedy("参照だけのカーソルに FOR READ ONLY を付ける。")
                    .example("""
                            EXEC SQL DECLARE CUR-CUST CURSOR FOR
                                SELECT ID, NAME FROM CUSTOMER END-EXEC.
                            """, """
                            EXEC SQL DECLARE CUR-CUST CURSOR FOR
                                SELECT ID, NAME FROM CUSTOMER FOR READ ONLY END-EXEC.
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
            if (statement.kind() != SqlStatementKind.DECLARE_CURSOR) {
                continue;
            }
            CursorSignals cursor = statement.structureSignals().cursor().orElse(null);
            if (cursor == null) {
                continue;
            }
            if (!cursor.forUpdate() && !cursor.forReadOnly() && !cursor.forFetchOnly()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "カーソル " + cursor.cursorName()
                                + " に FOR READ ONLY（または FOR FETCH ONLY）の指定がない。"
                                + "更新可能カーソルとして扱われ、ロック競合の原因になる。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
