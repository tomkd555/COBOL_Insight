package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
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
 * R069 A job or step with no CPU time limit. {@code TIME=1440}, {@code NOLIMIT} and
 * {@code MAXIMUM} switch off the limit that ends a looping program with S322. The parameter is
 * usually added to get one long run through and then stays, and the loop it later hides runs until
 * someone notices the job has not ended.
 */
public final class TimeUnlimitedRule implements Rule {

    private static final Set<String> UNLIMITED = Set.of("1440", "NOLIMIT", "MAXIMUM");

    private static final RuleMeta META = RuleMeta
            .named("R069", "CPU 時間の上限を外す TIME 指定", RuleCategory.PERFORMANCE)
            .technology(Technology.JCL)
            .symptom("ループしたジョブが S322 で止まらない")
            .summary("JOB 文・EXEC 文の TIME=1440・NOLIMIT・MAXIMUM を検出します。")
            .rationale("プログラムがループしても CPU 時間超過（S322）で止まらず、"
                    + "誰かが気づくまで CPU を使い続けます。")
            .detection("JOB 文と EXEC 文の TIME パラメーターの値が 1440・NOLIMIT・MAXIMUM の"
                    + "ものを検出します。TIME=(1440) のように括弧で囲んだ値も対象です。")
            .remedy("処理に必要な CPU 時間に余裕を見た値を、分と秒で指定してください。")
            .example("""
                    //STEP010 EXEC PGM=URI010,TIME=1440
                    """, """
                    //STEP010 EXEC PGM=URI010,TIME=(30,0)
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        JclSupport.Findings report = new JclSupport.Findings(META, findings);
        for (JclJobModel job : context.jclJobs()) {
            job.parameters().forEach((key, value) -> {
                if (key.equalsIgnoreCase("TIME") && unlimited(value)) {
                    report.add(job.position(), "ジョブ " + job.jobName() + " の JOB 文に TIME="
                            + value + " があります。ループしても CPU 時間超過で止まりません。");
                }
            });
            for (JclStep step : job.steps()) {
                JclSupport.parameter(step, "TIME").filter(TimeUnlimitedRule::unlimited)
                        .ifPresent(value -> report.add(step.position(), step.name()
                                + " の EXEC 文に TIME=" + value + " があります。"
                                + "ループしても CPU 時間超過で止まりません。"));
            }
        }
        return findings;
    }

    private static boolean unlimited(String value) {
        String text = JclSupport.upper(value).replace("(", "").replace(")", "").strip();
        return UNLIMITED.contains(text);
    }
}
