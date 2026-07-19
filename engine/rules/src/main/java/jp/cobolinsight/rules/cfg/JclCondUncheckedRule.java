package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * R030 JCLの先行ステップ結果未検査。ジョブの2番目以降のステップが、先行ステップの結果に依存し
 * 得るにもかかわらず COND 句を持たない場合を検出する。先行ステップの異常終了後も後続が実行される。
 */
public final class JclCondUncheckedRule implements Rule {

    @Override
    public String id() {
        return "R030";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (JclJobModel job : context.jclJobs()) {
            List<JclStep> steps = job.steps();
            for (int i = 1; i < steps.size(); i++) {
                JclStep step = steps.get(i);
                if (step.condition().isEmpty()) {
                    findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                            "ステップ " + step.name() + " は先行ステップに依存し得るが COND 句を"
                                    + "持たず、先行ステップの異常終了後も実行される。",
                            step.position()));
                }
            }
        }
        return findings;
    }
}
