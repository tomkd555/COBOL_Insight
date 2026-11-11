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
 * R078 A CREATE, ALTER or DROP run from an application program. Data definition takes exclusive
 * locks on the Db2 catalogue and invalidates every package that depends on the object, so a
 * program that runs it stalls the binds and the dynamic SQL of the whole subsystem, and needs
 * authorities no application plan should hold. A declared global temporary table is the exception
 * the language provides: it lives in the session and touches no catalogue table.
 */
public final class DdlInProgramRule implements Rule {

    private static final Pattern DDL = Pattern.compile("^(CREATE|ALTER|DROP)\\s+(\\S+)");

    private static final RuleMeta META = RuleMeta
            .named("R078", "アプリケーションプログラムからの DDL 実行", RuleCategory.OPERATION)
            .technology(Technology.DB2)
            .symptom("カタログのロック競合・パッケージの無効化")
            .summary("プログラムの埋込みSQLにある CREATE・ALTER・DROP を検出します。")
            .rationale("DDL は Db2 カタログを排他でロックし、対象に依存するパッケージを"
                    + "無効にします。サブシステム全体の BIND と動的 SQL を待たせ、"
                    + "アプリケーションのプランに本来不要な権限を必要とします。")
            .detection("COBOL と Easytrieve の SQL 文のうち、CREATE・ALTER・DROP で始まるものを"
                    + "検出します。セッションの中だけに存在する DECLARE GLOBAL TEMPORARY TABLE と、"
                    + "SQL スクリプトの文は対象外です。")
            .remedy("表や索引はあらかじめ定義しておいてください。作業用の表が必要なら、"
                    + "DECLARE GLOBAL TEMPORARY TABLE を使ってください。")
            .example("""
                    EXEC SQL CREATE TABLE WORKDB.URI_WORK (URI_NO CHAR(8)) END-EXEC.
                    """, """
                    EXEC SQL DECLARE GLOBAL TEMPORARY TABLE SESSION.URI_WORK
                        (URI_NO CHAR(8)) ON COMMIT PRESERVE ROWS END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.EASYTRIEVE)
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
            Matcher ddl = DDL.matcher(SqlText.of(statement));
            if (ddl.find()) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "プログラムが " + ddl.group(1) + " " + ddl.group(2) + " を実行しています。"
                                + "Db2 カタログを排他でロックし、依存するパッケージを無効にします。",
                        SqlAdviceSupport.location(statement)));
            }
        }
        return findings;
    }
}
