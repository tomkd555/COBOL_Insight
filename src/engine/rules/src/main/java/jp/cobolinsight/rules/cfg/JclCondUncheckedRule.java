package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

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
    public RuleDoc doc() {
        return RuleDoc.named("JCLステップ間の条件コード(COND)未検査", "JCL制御")
                .summary("ジョブの2番目以降のステップで COND 句を持たないものを検出する。")
                .rationale("先行ステップが異常終了しても後続が実行され、"
                        + "不完全なデータのまま処理が進む。")
                .detection("ジョブの2番目以降の EXEC ステップのうち、COND 句を持たないものを検出する。")
                .remedy("COND 句を付けるか、IF/THEN/ELSE で先行ステップの戻り値を"
                        + "判定してから実行する。")
                .example("""
                        //STEP02 EXEC PGM=SYK002
                        """, """
                        //STEP02 EXEC PGM=SYK002,COND=(4,LT,STEP01)
                        """)
                .build();
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
