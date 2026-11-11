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
 * R084 A CREATE TABLE with no IN clause. Db2 then creates a table space of its own in the default
 * database, with a generated name and default attributes: no one chose its size, its buffer pool
 * or its lock size, and the backup and reorganisation jobs, which work from a list of table
 * spaces, do not know it exists.
 */
public final class ImplicitTableSpaceRule implements Rule {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("^CREATE\\s+TABLE\\s+(\\S+)");
    private static final Pattern IN_CLAUSE =
            Pattern.compile("\\)\\s*(?:[A-Z ]*\\s)?IN\\s+(?:DATABASE\\s+)?[A-Z0-9_#@$.\"]+");

    private static final RuleMeta META = RuleMeta
            .named("R084", "表スペースを指定しない CREATE TABLE", RuleCategory.OPERATION)
            .technology(Technology.DB2)
            .symptom("既定データベース（DSNDB04）への暗黙の表スペース作成")
            .summary("IN 句で表スペースもデータベースも指定しない CREATE TABLE を検出します。")
            .rationale("Db2 が既定のデータベースに表スペースを自動で作成します。大きさ・"
                    + "バッファプール・ロックの単位が既定値のままになり、表スペースの一覧で"
                    + "運用するバックアップや再編成の対象から漏れます。")
            .detection("SQL スクリプトの CREATE TABLE のうち、列定義の後ろに IN 句がないものを"
                    + "検出します。CREATE GLOBAL TEMPORARY TABLE、CREATE AUXILIARY TABLE、"
                    + "LIKE・AS で他の表から作る CREATE TABLE は対象外です。")
            .remedy("IN データベース名.表スペース名 を指定してください。")
            .example("""
                    CREATE TABLE URIDB.URIAGE (URI_NO CHAR(8) NOT NULL, KINGAKU DECIMAL(11,0));
                    """, """
                    CREATE TABLE URIDB.URIAGE (URI_NO CHAR(8) NOT NULL, KINGAKU DECIMAL(11,0))
                        IN URIDB01.URITS01;
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.SQL)
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
            String text = SqlText.of(statement);
            Matcher create = CREATE_TABLE.matcher(text);
            if (!create.find() || !text.contains("(") || IN_CLAUSE.matcher(text).find()
                    || text.matches("^CREATE\\s+TABLE\\s+\\S+\\s+(LIKE|AS)\\b.*")) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    create.group(1) + " の CREATE TABLE に IN 句がありません。"
                            + "Db2 が既定のデータベースに表スペースを自動で作成します。",
                    SqlAdviceSupport.location(statement)));
        }
        return findings;
    }
}
