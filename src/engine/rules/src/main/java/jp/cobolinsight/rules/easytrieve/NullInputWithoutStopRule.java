package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveActivity;
import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
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
 * R097 A {@code JOB INPUT NULL} with no STOP. A JOB activity repeats until its automatic input is
 * exhausted, and with {@code INPUT NULL} there is no automatic input: the activity repeats until
 * a STOP is executed. An activity that reads its files itself with GET and never issues STOP —
 * or issues it only on a path the data may not take — runs until the CPU time limit ends it.
 */
public final class NullInputWithoutStopRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R097", "STOP のない JOB INPUT NULL", RuleCategory.ABEND)
            .technology(Technology.EASYTRIEVE)
            .symptom("無限ループ（S322）")
            .summary("JOB INPUT NULL のアクティビティのうち、STOP 文を 1 つも持たないものを"
                    + "検出します。")
            .rationale("INPUT NULL の JOB は自動入力がなく、STOP を実行するまで繰り返されます。"
                    + "STOP がなければ CPU 時間超過で打ち切られるまで終わりません。")
            .detection("JOB INPUT NULL のアクティビティと、そのアクティビティの PROC の文に、"
                    + "STOP も TRANSFER もないものを検出します。条件の中に STOP があれば、"
                    + "実行される経路の有無は問いません。")
            .remedy("入力の終わりを判定した箇所で STOP を実行してください。")
            .example("""
                    JOB INPUT NULL
                      GET URIAGE
                      PUT SYUKEI
                    """, """
                    JOB INPUT NULL
                      GET URIAGE
                      IF EOF URIAGE
                         STOP
                      END-IF
                      PUT SYUKEI
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
            for (EasytrieveActivity activity : program.activities()) {
                if (activity.isNullInputJob() && activity.executableStatements().stream()
                        .noneMatch(statement -> statement.is("STOP", "TRANSFER"))) {
                    findings.add(EasytrieveSupport.finding(META, activity.position(),
                            "JOB INPUT NULL のアクティビティに STOP がありません。"
                                    + "自動入力がないため、CPU 時間超過で打ち切られるまで"
                                    + "繰り返されます。"));
                }
            }
        }
        return findings;
    }
}
