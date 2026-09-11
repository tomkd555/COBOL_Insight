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
 * R059 An INSERT that names no columns. The values are then matched to the table's columns by
 * position, so adding a column to the table, or reordering two of the same type, moves every value
 * one place along without any message. An {@code INSERT ... SELECT} whose select list names its
 * columns is not reported: the names are written, just on the other side of the statement.
 *
 * <p>A degraded statement is skipped: the keyword scan reads no column list, so its absence from
 * the model says nothing about the source.
 */
public final class InsertWithoutColumnListRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R059", "列名を書かない INSERT", "可読性・保守性")
            .summary("挿入する列の一覧を書かない INSERT 文を検出します。")
            .rationale("値が表の列の順番で対応づけられるため、"
                    + "表に列を足しただけで別の列に値が入ります。")
            .detection("INSERT 文のうち、挿入する列の一覧を書かないものを検出します。"
                    + "列名を並べた SELECT から挿入する INSERT は対象外です。"
                    + "COBOL の埋込みSQLと、SQL スクリプトの文の双方を対象とします。"
                    + "完全に解析できなかった文も対象外です。")
            .remedy("挿入する列名を並べて書いてください。")
            .example("""
                    EXEC SQL INSERT INTO SYKDB.ZAIKOM
                             VALUES (:HOST-商品コード, :HOST-倉庫コード, 0, 0) END-EXEC.
                    """, """
                    EXEC SQL INSERT INTO SYKDB.ZAIKOM
                             (SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU)
                             VALUES (:HOST-商品コード, :HOST-倉庫コード, 0, 0) END-EXEC.
                    """)
            .severity(Severity.LOW)
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
            if (!statement.isFullyAnalysed() || statement.kind() != SqlStatementKind.INSERT
                    || !statement.insertColumns().isEmpty() || namesItsColumns(statement)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    SqlAdviceSupport.tableOf(statement) + "への INSERT に列名がありません。"
                            + "値が列の順番で対応づけられ、表に列を足すと別の列に入ります。",
                    SqlAdviceSupport.location(statement)));
        }
        return findings;
    }

    /** An INSERT ... SELECT whose select list names its columns rather than writing {@code *}. */
    private static boolean namesItsColumns(SqlStatementModel statement) {
        return !statement.selectList().isEmpty()
                && statement.selectList().stream().noneMatch(entry -> entry.strip().equals("*"));
    }
}
