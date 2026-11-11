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
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * S007 UNION where UNION ALL would do. UNION removes duplicate rows, which costs a sort of the
 * whole result; when the branches cannot produce the same row — they read different tables, or
 * ranges that do not overlap — the sort removes nothing. Whether they can is a fact about the
 * data, so this is advice rather than a defect.
 */
public final class UnionWithoutAllRule implements Rule {

    private static final Pattern UNION = Pattern.compile("\\bUNION\\b(?!\\s+ALL\\b)");

    private static final RuleMeta META = RuleMeta
            .named("S007", "重複排除を伴う UNION", RuleCategory.PERFORMANCE)
            .technology(Technology.DB2)
            .symptom("結果全体の並べ替え")
            .summary("ALL を付けない UNION を検出します。")
            .rationale("UNION は重複する行を除くために結果全体を並べ替えます。枝どうしが同じ行を"
                    + "返さない問い合わせでは、何も除かれない並べ替えだけが実行されます。")
            .detection("SQL 文の UNION のうち ALL を伴わないものを検出します。重複の排除が"
                    + "必要かどうかはデータの内容によるため、助言として報告します。")
            .remedy("枝どうしが同じ行を返さないなら UNION ALL にしてください。")
            .example("""
                    SELECT URI_NO FROM URIAGE_TOKYO UNION SELECT URI_NO FROM URIAGE_OSAKA
                    """, """
                    SELECT URI_NO FROM URIAGE_TOKYO UNION ALL SELECT URI_NO FROM URIAGE_OSAKA
                    """)
            .severity(Severity.ADVISORY)
            .commands(Command.SQL_LINT)
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
            if (statement.isFullyAnalysed() && UNION.matcher(SqlText.of(statement)).find()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "UNION が重複を除くために結果全体を並べ替えます。"
                                + "枝どうしが同じ行を返さないなら UNION ALL で足ります。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
