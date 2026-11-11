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

/**
 * R065 A PARM longer than 100 characters. The EXEC statement passes at most 100 characters to the
 * program; a longer value is a JCL error, and the job never starts. The value grows past the limit
 * one option at a time, typically in the run-time options or the Db2 parameters of a long-lived
 * job, and the check that would catch it runs only on the machine.
 */
public final class ParmTooLongRule implements Rule {

    private static final int LIMIT = 100;

    private static final RuleMeta META = RuleMeta
            .named("R065", "100 文字を超える PARM", RuleCategory.ABEND)
            .technology(Technology.JCL)
            .symptom("JCL エラー（IEF642I EXCESSIVE PARAMETER LENGTH）")
            .summary("EXEC 文の PARM の値が 100 文字を超えるものを検出します。")
            .rationale("PARM で渡せるのは 100 文字までです。超えると JCL エラーになり、"
                    + "ジョブが実行されません。")
            .detection("EXEC 文の PARM の値から外側のアポストロフィと括弧を除いた長さが"
                    + "100 文字を超えるものを検出します。記号が残っていて長さの決まらない値は"
                    + "対象外です。")
            .remedy("値を短くするか、PARMDD で DD から渡してください。")
            .severity(Severity.HIGH)
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
            for (JclStep step : JclSupport.programSteps(job)) {
                String parm = step.parm().orElse("");
                if (parm.startsWith("(") && parm.endsWith(")")) {
                    parm = parm.substring(1, parm.length() - 1);
                }
                if (parm.length() > LIMIT && !parm.contains("&")) {
                    report.add(step.position(), step.name() + " の PARM は " + parm.length()
                            + " 文字あります。100 文字を超えると JCL エラーになり、"
                            + "ジョブが実行されません。");
                }
            }
        }
        return findings;
    }
}
