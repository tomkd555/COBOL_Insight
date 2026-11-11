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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R083 A GRANT … TO PUBLIC. Every authorisation ID that can connect to the subsystem receives the
 * privilege, now and in the future, and no audit of who holds what will list them. It is written
 * to make a test work and reaches production with the DDL it sits in.
 */
public final class GrantToPublicRule implements Rule {

    private static final Pattern GRANT_PUBLIC =
            Pattern.compile("^GRANT\\s+(.+?)\\s+TO\\s+(?:.*,\\s*)?PUBLIC\\b");

    private static final RuleMeta META = RuleMeta
            .named("R083", "PUBLIC への権限付与", RuleCategory.SECURITY)
            .technology(Technology.DB2)
            .symptom("不特定の利用者によるデータの参照・更新")
            .summary("GRANT … TO PUBLIC を検出します。")
            .rationale("サブシステムに接続できるすべての認可 ID が、将来にわたってその権限を"
                    + "持ちます。誰が権限を持つかを一覧で確かめることもできません。")
            .detection("SQL スクリプトと埋込みSQLの GRANT 文のうち、付与先に PUBLIC を含むものを"
                    + "検出します。PUBLIC AT ALL LOCATIONS も対象です。")
            .remedy("権限は必要な認可 ID かロールに限って付与してください。")
            .example("""
                    GRANT SELECT, UPDATE ON TABLE URIDB.URIAGE TO PUBLIC;
                    """, """
                    GRANT SELECT, UPDATE ON TABLE URIDB.URIAGE TO URIBATCH;
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.SQL, AssetKind.COBOL, AssetKind.EASYTRIEVE)
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
            if (statement.kind() != SqlStatementKind.DDL) {
                continue;
            }
            Matcher grant = GRANT_PUBLIC.matcher(SqlText.of(statement));
            if (grant.find()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "権限 " + grant.group(1) + " を PUBLIC に付与しています。"
                                + "サブシステムに接続できるすべての認可 ID がこの権限を持ちます。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
