package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * S006 OPTIMIZE FOR 句の未使用。DECLARE CURSOR に OPTIMIZE FOR n ROWS 句が無い箇所を指摘する。
 * 少件数取得の用途では、この句によりオプティマイザが少件数取得に適したアクセスパスを選びやすくなる。
 */
public final class OptimizeForMissingRule implements Rule {

    @Override
    public String id() {
        return "S006";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("OPTIMIZE FOR句によるアクセスパス最適化の検討", "性能")
                .summary("OPTIMIZE FOR n ROWS 句を持たないカーソル宣言を指摘します。")
                .rationale("全件取得を前提としたアクセスパスが選ばれ、"
                        + "先頭の数件だけを使う用途では応答が遅くなります。")
                .detection("DECLARE CURSOR に OPTIMIZE FOR 句があるかどうかだけを見ます。"
                        + "全件を読み切るカーソルでは対処は不要になります。")
                .remedy("少件数だけを取り出すカーソルへ OPTIMIZE FOR n ROWS を付けます。")
                .example("""
                        DECLARE CUR-CUST CURSOR FOR
                            SELECT ID FROM CUSTOMER
                        """, """
                        DECLARE CUR-CUST CURSOR FOR
                            SELECT ID FROM CUSTOMER OPTIMIZE FOR 20 ROWS
                        """)
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.LOW;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (SqlStatementModel statement : context.sqlStatements()) {
            if (statement.kind() != SqlStatementKind.DECLARE_CURSOR) {
                continue;
            }
            if (!statement.structureSignals().hasOptimizeFor()) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "OPTIMIZE FOR n ROWS 句が無い。少件数取得の用途なら、アクセスパス最適化の"
                                + "ため付与を検討する。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
