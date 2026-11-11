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

/**
 * R101 A GET that is not followed by an end-of-file test. GET does not stop the activity at the
 * end of the file: it leaves the record area as it was and sets the file's EOF condition. A
 * program that goes on to use the record processes the last record again, on every pass, for as
 * long as the activity runs.
 */
public final class GetWithoutEofRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R101", "ファイルの終わりを判定しない GET", RuleCategory.INTEGRITY)
            .technology(Technology.EASYTRIEVE)
            .symptom("最終レコードの二重処理・無限ループ")
            .summary("GET 文の直後に、そのファイルの EOF を判定していないものを検出します。")
            .rationale("GET はファイルの終わりでも処理を止めず、レコード域を前のままにして"
                    + "EOF 条件を立てるだけです。判定しないと最後のレコードを繰り返し処理します。")
            .detection("GET 文の次の文が、そのファイル名を含む IF・DO・CASE でないものを"
                    + "検出します。GET が DO … END-DO の最後の文で、その DO の条件が"
                    + "そのファイル名を含む場合は、繰り返しの条件で判定しているとみなして"
                    + "対象外です。")
            .remedy("GET の直後に IF EOF ファイル名 で終わりを判定し、STOP か繰り返しの終了へ"
                    + "進んでください。")
            .example("""
                    GET URIAGE
                    W-GOKEI = W-GOKEI + U-KINGAKU
                    """, """
                    GET URIAGE
                    IF EOF URIAGE
                       STOP
                    END-IF
                    W-GOKEI = W-GOKEI + U-KINGAKU
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
                    EasytrieveStatement get = statements.get(i);
                    if (!get.is("GET") || get.words().size() < 2) {
                        continue;
                    }
                    String file = get.word(1);
                    if (!tested(statements, i, file)) {
                        findings.add(EasytrieveSupport.finding(META, EasytrieveSupport.at(get),
                                "GET " + file + " の直後にファイルの終わりを判定していません。"
                                        + "ファイルの終わりでは、前のレコードをもう一度"
                                        + "処理します。"));
                    }
                }
            }
        }
        return findings;
    }

    private static boolean tested(List<EasytrieveStatement> statements, int index, String file) {
        if (index + 1 >= statements.size()) {
            return false;
        }
        EasytrieveStatement next = statements.get(index + 1);
        if (next.is("IF", "DO", "CASE") && next.mentions(file)) {
            return true;
        }
        return next.is("END-DO") && EasytrieveSupport.openingDo(statements, index + 1)
                .filter(opening -> opening.mentions(file)).isPresent();
    }
}
