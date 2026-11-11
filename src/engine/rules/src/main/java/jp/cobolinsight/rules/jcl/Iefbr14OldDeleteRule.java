package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
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
import java.util.Optional;

/**
 * R063 A clean-up step that deletes with {@code DISP=(OLD,DELETE)}. {@code OLD} demands that the
 * data set exists: on the first run, and on every rerun after the clean-up has already worked, it
 * does not, and the job stops with a JCL error before any step runs. {@code MOD} allocates the
 * data set when it is missing and deletes it either way, which is what a clean-up step means.
 */
public final class Iefbr14OldDeleteRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R063", "IEFBR14 による DISP=(OLD,DELETE) の削除", RuleCategory.OPERATION)
            .technology(Technology.JCL)
            .symptom("JCL エラー（IEF212I DATA SET NOT FOUND）")
            .summary("IEFBR14 のステップで、DISP の状態を OLD・SHR にしてデータセットを"
                    + "削除する DD を検出します。")
            .rationale("データセットが存在しない初回の実行や、削除が済んだ後の再実行で、"
                    + "割り当てが失敗しジョブ全体が実行されません。")
            .detection("EXEC PGM=IEFBR14 のステップの DD のうち、DISP の状態が OLD・SHR で、"
                    + "正常終了時の処分が DELETE のものを検出します。一時データセットと、"
                    + "記号が残るデータセット名は対象外です。")
            .remedy("DISP=(MOD,DELETE,DELETE) にして、UNIT と SPACE を指定してください。"
                    + "存在しなければ割り当ててから削除するため、どちらの場合も正常に終わります。")
            .example("""
                    //DELSTEP EXEC PGM=IEFBR14
                    //DELDD   DD DSN=PROD.URIAGE.SYUKEI,DISP=(OLD,DELETE)
                    """, """
                    //DELSTEP EXEC PGM=IEFBR14
                    //DELDD   DD DSN=PROD.URIAGE.SYUKEI,DISP=(MOD,DELETE,DELETE),
                    //           UNIT=SYSDA,SPACE=(TRK,0)
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
            for (JclStep step : JclSupport.programSteps(job)) {
                if (!step.target().equalsIgnoreCase("IEFBR14")) {
                    continue;
                }
                for (JclDdStatement dd : step.ddStatements()) {
                    String status = JclSupport.status(dd);
                    Optional<JclDataset> dataset = JclSupport.dataset(dd)
                            .filter(d -> !d.temporary());
                    if (dataset.isPresent() && (status.equals("OLD") || status.equals("SHR"))
                            && JclSupport.normal(dd).equals("DELETE")) {
                        report.add(dd.position(), step.name() + " の DD " + dd.ddName()
                                + " は DISP=" + dd.dispositionText().orElse("") + " で "
                                + dataset.get().name() + " を削除します。データセットが"
                                + "存在しないと割り当てが失敗し、ジョブが実行されません。");
                    }
                }
            }
        }
        return findings;
    }
}
