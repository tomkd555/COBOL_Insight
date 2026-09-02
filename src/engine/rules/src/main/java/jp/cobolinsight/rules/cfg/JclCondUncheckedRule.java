package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R030 JCL preceding-step result unchecked. Flags a job's second and later steps that lack a
 * COND clause even though they can depend on a preceding step's outcome. Without one, later
 * steps still run even after a preceding step ends abnormally.
 */
public final class JclCondUncheckedRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R030", "JCL ステップ間の COND パラメーター未指定", "JCL制御")
            .summary("ジョブの 2 番目以降のステップで COND パラメーターを持たないものを"
                    + "検出します。")
            .rationale("先行ステップが異常終了しても後続が実行され、"
                    + "不完全なデータのまま処理が進みます。")
            .detection("ジョブの EXEC ステップのうち、COND パラメーターを持たないものを"
                    + "検出します。先行ステップのない先頭のステップは対象外です。")
            .remedy("COND パラメーターを付けるか、IF/THEN/ELSE で先行ステップの戻りコードを"
                    + "検査してから実行してください。")
            .example("""
                    //STEP02 EXEC PGM=SYK002
                    """, """
                    //STEP02 EXEC PGM=SYK002,COND=(4,LT,STEP01)
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.JCL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (JclJobModel job : context.jclJobs()) {
            List<JclStep> steps = job.steps();
            for (int i = 1; i < steps.size(); i++) {
                JclStep step = steps.get(i);
                if (step.condition().isEmpty()) {
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            step.name() + " に COND パラメーターがありません。"
                                    + "先行ステップの異常終了後も実行されます。",
                            step.position()));
                }
            }
        }
        return findings;
    }
}
