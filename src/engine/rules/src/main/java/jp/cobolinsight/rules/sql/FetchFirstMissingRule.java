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
 * S005 FETCH FIRST 句の未使用。SELECT 文・カーソル宣言に FETCH FIRST n ROWS ONLY 句が無い箇所を
 * 指摘する。取得件数が既知なら必要以上の行取得を避けられる。判定は構文一律で、FETCH FIRST 句の
 * 有無のみを見る。
 */
public final class FetchFirstMissingRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("S005", "FETCH FIRST句によるフェッチ件数制限の検討", "性能")
                    .summary("FETCH FIRST n ROWS ONLY 句を持たない"
                            + "SELECT 文・カーソル宣言を指摘します。")
                    .rationale("必要な件数が決まっている場合でも全件を取りに行き、"
                            + "無駄な入出力と転送が生じます。")
                    .detection("SELECT 文・カーソル宣言に FETCH FIRST 句があるかどうかだけを見る、"
                            + "構文一律の判定です。全件が要る問い合わせでは対処は不要になります。")
                    .remedy("取得件数が決まっている問い合わせへ FETCH FIRST n ROWS ONLY を付けます。")
                    .example("""
                            SELECT ID, NAME FROM CUSTOMER ORDER BY ID
                            """, """
                            SELECT ID, NAME FROM CUSTOMER ORDER BY ID
                                FETCH FIRST 100 ROWS ONLY
                            """)
                    .severity(Severity.LOW)
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
            if (statement.kind() != SqlStatementKind.SELECT
                    && statement.kind() != SqlStatementKind.DECLARE_CURSOR) {
                continue;
            }
            if (!statement.structureSignals().hasFetchFirst()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "FETCH FIRST n ROWS ONLY 句が無い。取得件数が既知なら、必要以上の行取得を"
                                + "避けるため付与を検討する。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
