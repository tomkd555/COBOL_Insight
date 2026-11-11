package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;

/**
 * R079 A LOCK TABLE … IN EXCLUSIVE MODE in a program. The lock shuts every other program out of
 * the table, readers included, until the program commits — and a batch program that takes it
 * usually commits at the end. What was written to make one job faster is what times out the
 * online transactions that share the table.
 */
public final class LockTableExclusiveRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R079", "表全体の排他ロック（LOCK TABLE IN EXCLUSIVE MODE）",
                    RuleCategory.PERFORMANCE)
            .technology(Technology.DB2)
            .symptom("他プログラムのロック待ちタイムアウト（SQLCODE -911・-913）")
            .summary("LOCK TABLE … IN EXCLUSIVE MODE を検出します。")
            .rationale("COMMIT するまで、参照だけのプログラムを含めて他のすべてのプログラムが"
                    + "その表を使えません。同じ表を使うオンライン処理がロック待ちで"
                    + "タイムアウトします。")
            .detection("SQL 文の LOCK TABLE のうち IN EXCLUSIVE MODE を指定したものを検出します。"
                    + "IN SHARE MODE は他の参照を妨げないため対象外です。")
            .remedy("行やページのロックで足りるかを確かめてください。排他が必要な場合は、"
                    + "オンライン処理と重ならない時間帯に実行し、短い間隔で COMMIT してください。")
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.EASYTRIEVE, AssetKind.SQL)
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
            if (statement.kind() == SqlStatementKind.LOCK_TABLE
                    && SqlText.of(statement).contains("EXCLUSIVE")) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        SqlAdviceSupport.tableOf(statement).strip() + " を排他でロックしています。"
                                + "COMMIT するまで、他のプログラムはこの表を参照も更新も"
                                + "できません。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
