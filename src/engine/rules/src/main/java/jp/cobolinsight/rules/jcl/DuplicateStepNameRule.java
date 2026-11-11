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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R070 Two steps of one job with the same name. {@code RESTART=}, {@code COND=(…,stepname)} and
 * an {@code IF stepname.RC} all find a step by its name and take the first one that matches, so
 * the second step of the name can be neither restarted from nor tested.
 */
public final class DuplicateStepNameRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R070", "ジョブ内のステップ名の重複", RuleCategory.OPERATION)
            .technology(Technology.JCL)
            .symptom("意図と違うステップからの再始動・戻りコード判定")
            .summary("同じジョブに同じ名前のステップが 2 つ以上あるものを検出します。")
            .rationale("RESTART、COND、IF は名前でステップを探し、最初に一致したものを使います。"
                    + "2 つ目のステップからは再始動できず、その戻りコードも判定できません。")
            .detection("ジョブのステップ名のうち、同じジョブの先行するステップと重複するものを"
                    + "検出します。PROC の中のステップは「呼び出し名.ステップ名」で比べるため、"
                    + "別々の呼び出しが展開した同名のステップは重複としません。")
            .remedy("ステップ名をジョブの中で一意にしてください。")
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
            Map<String, JclStep> seen = new HashMap<>();
            for (JclStep step : job.steps()) {
                JclStep first = seen.putIfAbsent(JclSupport.upper(step.name()), step);
                if (first != null) {
                    report.add(step.position(), "ジョブ " + job.jobName() + " にステップ名 "
                            + step.name() + " が 2 つあります（最初は "
                            + first.position().line() + " 行目）。RESTART・COND・IF は"
                            + "最初のステップを指します。");
                }
            }
        }
        return findings;
    }
}
