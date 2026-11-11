package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.easytrieve.EasytrieveStatement;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * R102 An SQL statement whose SQLCODE the program does not test. Outside a {@code JOB INPUT SQL},
 * which handles its own cursor, Easytrieve leaves the outcome of every SQL statement to the
 * program: a failed UPDATE or a FETCH past the last row returns to the next statement with only
 * SQLCODE to say so.
 */
public final class SqlcodeUncheckedRule implements Rule {

    /** The statements that are declarations, or that have no outcome a program acts on. */
    private static final Set<String> NOT_TESTED = Set.of("DECLARE", "INCLUDE", "WHENEVER");

    private static final RuleMeta META = RuleMeta
            .named("R102", "Easytrieve の SQL 文の SQLCODE 未検査", RuleCategory.INTEGRITY)
            .technology(Technology.EASYTRIEVE)
            .symptom("SQL エラーの見逃し")
            .summary("SQL 文の直後に SQLCODE を判定していないものを検出します。")
            .rationale("更新の失敗や行なしが SQLCODE だけで返り、判定しないと、"
                    + "失敗したまま後続の処理が進みます。")
            .detection("アクティビティと PROC の SQL 文のうち、次の文が SQLCODE を含む"
                    + "IF・DO・CASE でないものを検出します。SQL 文が DO … END-DO の最後の文で、"
                    + "その DO の条件が SQLCODE を含む場合は対象外です。"
                    + "DECLARE・INCLUDE・WHENEVER は対象外です。")
            .remedy("SQL 文の直後に IF SQLCODE NE 0 で結果を判定してください。")
            .example("""
                    SQL UPDATE CUSTDB.CUSTOMER SET ZANDAKA = :CUST-ZAN +
                        WHERE CUST_NO = :CUST-NO
                    PUT CUSTOUT
                    """, """
                    SQL UPDATE CUSTDB.CUSTOMER SET ZANDAKA = :CUST-ZAN +
                        WHERE CUST_NO = :CUST-NO
                    IF SQLCODE NE 0
                       DISPLAY 'UPDATE ERROR ' SQLCODE
                       STOP EXECUTE
                    END-IF
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.EASYTRIEVE, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            for (List<EasytrieveStatement> statements : EasytrieveSupport.sequences(program)) {
                for (int i = 0; i < statements.size(); i++) {
                    EasytrieveStatement sql = statements.get(i);
                    if (!sql.is("SQL") || NOT_TESTED.contains(sql.word(1))) {
                        continue;
                    }
                    EasytrieveStatement next =
                            i + 1 < statements.size() ? statements.get(i + 1) : null;
                    boolean tested = next != null && ((next.is("IF", "DO", "CASE")
                            && next.mentions("SQLCODE")) || (next.is("END-DO")
                            && EasytrieveSupport.openingDo(statements, i + 1)
                                    .filter(opening -> opening.mentions("SQLCODE")).isPresent()));
                    if (!tested) {
                        findings.add(EasytrieveSupport.finding(META, EasytrieveSupport.at(sql),
                                "SQL " + sql.word(1) + " の直後に SQLCODE を判定していません。"
                                        + "SQL 文の失敗に気づかないまま後続が進みます。"));
                    }
                }
            }
        }
        return findings;
    }
}
