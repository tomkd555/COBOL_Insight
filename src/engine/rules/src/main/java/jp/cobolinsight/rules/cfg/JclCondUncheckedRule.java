package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;

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
                .summary("ジョブの2番目以降のステップで COND 句を持たないものを検出します。")
                .rationale("先行ステップが異常終了しても後続が実行され、"
                        + "不完全なデータのまま処理が進みます。")
                .detection("ジョブの2番目以降の EXEC ステップのうち、COND 句を持たないものを検出します。")
                .remedy("COND 句を付けるか、IF/THEN/ELSE で先行ステップの戻り値を"
                        + "判定してから実行します。")
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
