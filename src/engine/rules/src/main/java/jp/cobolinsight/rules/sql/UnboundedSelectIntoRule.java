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
import java.util.regex.Pattern;

/**
 * R056 A SELECT INTO that nothing narrows to one row. A single-row SELECT fails with SQLCODE -811
 * as soon as the table holds a second matching row, and the host variables are left unset. Three
 * things narrow it, and the statement is reported only when it has none of them: a WHERE clause,
 * an aggregate anywhere in the select list (which always returns one row), and a FETCH FIRST clause,
 * whose row count the structure signal does not carry, so any FETCH FIRST exempts the statement. A
 * read of SYSIBM.SYSDUMMY1 alone is left out as well: that table holds one row by definition, and
 * every read of a special register or a sequence goes through it.
 *
 * <p>A degraded statement is skipped: the keyword scan reads neither the clauses nor the select
 * list, so their absence from the model says nothing about the source.
 */
public final class UnboundedSelectIntoRule implements Rule {

    /**
     * A column function anywhere in the select-list entry, not only at its start: {@code
     * COALESCE(SUM(X), 0)} and {@code 1 + MAX(A)} return one row just as {@code SUM(X)} does. Every
     * Db2 column function of an ungrouped query returns exactly one row, so the statistical ones
     * stand beside the five a COBOL program usually writes.
     */
    private static final Pattern AGGREGATE = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}$#@_])(COUNT_BIG|COUNT|SUM|MIN|MAX|AVG"
                    + "|STDDEV_SAMP|STDDEV|VARIANCE_SAMP|VARIANCE|VAR_SAMP|VAR|MEDIAN"
                    + "|CORRELATION|CORR|COVARIANCE_SAMP|COVARIANCE|COVAR_SAMP|COVAR"
                    + "|LISTAGG|XMLAGG)\\s*\\(");

    /** The Db2 catalogue table that holds exactly one row, which every special-register read uses. */
    private static final String ONE_ROW_TABLE = "SYSDUMMY1";

    private static final RuleMeta META =
            RuleMeta.named("R056", "1 行に絞られない SELECT INTO", "SQL")
            .summary("WHERE 句も集約関数も FETCH FIRST … ROWS ONLY も持たない "
                    + "SELECT INTO を検出します。")
            .rationale("該当する行が 2 行以上あると SQLCODE -811 で失敗し、"
                    + "ホスト変数は設定されないまま残ります。")
            .detection("SELECT INTO のうち、WHERE 句を持たず、"
                    + "選択列に COUNT・SUM・MIN・MAX・AVG などの集約関数がなく、"
                    + "FETCH FIRST … ROWS ONLY も持たないものを検出します。"
                    + "1 行しかない SYSIBM.SYSDUMMY1 だけを読む文は対象外です。"
                    + "完全に解析できなかった文は対象外です。")
            .remedy("鍵の列で行を絞るか、FETCH FIRST 1 ROW ONLY を付けてください。"
                    + "複数行を読む意図であれば、カーソルに書き換えてください。")
            .example("""
                    EXEC SQL SELECT SOKO_NM INTO :HOST-倉庫名
                             FROM SYKDB.SOKOM END-EXEC.
                    """, """
                    EXEC SQL SELECT SOKO_NM INTO :HOST-倉庫名
                             FROM SYKDB.SOKOM
                             WHERE SOKO_CD = :HOST-倉庫コード END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
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
            if (!statement.isFullyAnalysed()
                    || statement.kind() != SqlStatementKind.SELECT_INTO
                    || statement.hasWhere()
                    || statement.structureSignals().hasFetchFirst()
                    || readsTheOneRowTable(statement)
                    || statement.selectList().stream()
                            .anyMatch(entry -> AGGREGATE.matcher(entry.strip()).find())) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    "SELECT INTO が 1 行に絞られていません。"
                            + SqlAdviceSupport.rowsOf(statement)
                            + "が 2 行以上あると、SQLCODE -811 で失敗します。",
                    SqlAdviceSupport.location(statement)));
        }
        return findings;
    }

    /**
     * Whether the statement reads SYSIBM.SYSDUMMY1 and nothing else. That table holds one row by
     * definition, so a special-register or sequence read from it needs no clause to narrow it.
     */
    private static boolean readsTheOneRowTable(SqlStatementModel statement) {
        return statement.referencedTables().size() == 1
                && Db2Schema.unqualified(statement.referencedTables().get(0))
                        .equals(ONE_ROW_TABLE);
    }
}
