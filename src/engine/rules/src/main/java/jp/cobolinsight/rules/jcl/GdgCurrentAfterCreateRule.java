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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R060 A generation created as {@code (+1)} and read back as {@code (0)} in the same job. The
 * relative generation numbers of a job are fixed when the job starts: the generation a step
 * creates stays {@code (+1)} until the job ends, and {@code (0)} keeps naming the generation that
 * was current before it. A later step that reads {@code (0)} therefore reads the previous run's
 * data, and nothing fails.
 *
 * <p>A step that names both {@code (0)} and {@code (+1)} is comparing or merging the two on
 * purpose and is not reported.
 */
public final class GdgCurrentAfterCreateRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R060", "同じジョブで作成した GDG 世代の (0) 参照", RuleCategory.INTEGRITY)
            .technology(Technology.JCL)
            .symptom("前回世代の読み込み")
            .summary("先行ステップが (+1) で作成した GDG の世代を、後続ステップが (0) で"
                    + "参照しているものを検出します。")
            .rationale("ジョブの中では相対世代番号は固定され、(0) はジョブ開始前の最新世代を"
                    + "指します。後続ステップは作成したばかりの世代ではなく前回の世代を読み、"
                    + "エラーにならないまま古いデータで処理が進みます。")
            .detection("ジョブのステップを順にたどり、DISP の状態が NEW の DD が (+n) で作成した"
                    + "GDG ベースを、それより後のステップが (0) で参照しているものを検出します。"
                    + "同じステップが (0) と (+n) の両方を参照している場合は、新旧の突き合わせと"
                    + "みなして対象外です。記号が残っているデータセット名も対象外です。")
            .remedy("同じジョブの中では、作成した世代を (+1) のまま参照してください。")
            .example("""
                    //STEP010 EXEC PGM=URI010
                    //OUTFILE DD DSN=PROD.URIAGE.GDG(+1),DISP=(NEW,CATLG,DELETE)
                    //STEP020 EXEC PGM=URI020
                    //INFILE  DD DSN=PROD.URIAGE.GDG(0),DISP=SHR
                    """, """
                    //STEP020 EXEC PGM=URI020
                    //INFILE  DD DSN=PROD.URIAGE.GDG(+1),DISP=SHR
                    """)
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
            // GDG base to the step that created its new generation.
            Map<String, JclStep> createdBy = new HashMap<>();
            for (JclStep step : JclSupport.programSteps(job)) {
                Set<String> newInThisStep = new java.util.HashSet<>();
                for (JclDdStatement dd : step.ddStatements()) {
                    generation(dd).filter(g -> g.gdgRelative().orElse(0) > 0)
                            .ifPresent(g -> newInThisStep.add(g.name()));
                }
                for (JclDdStatement dd : step.ddStatements()) {
                    JclDataset dataset = generation(dd).orElse(null);
                    if (dataset == null || dataset.gdgRelative().orElse(1) != 0
                            || JclSupport.status(dd).equals("NEW")) {
                        continue;
                    }
                    JclStep creator = createdBy.get(dataset.name());
                    if (creator != null && !newInThisStep.contains(dataset.name())) {
                        report.add(dd.position(), step.name() + " の DD " + dd.ddName() + " は "
                                + dataset.name() + "(0) を参照していますが、この GDG の新しい世代は"
                                + "同じジョブの " + creator.name() + " が (+1) で作成しています。"
                                + "ジョブの中では (0) は実行前の最新世代を指すため、"
                                + "前回の世代を読みます。");
                    }
                }
                for (JclDdStatement dd : step.ddStatements()) {
                    generation(dd).filter(g -> g.gdgRelative().orElse(0) > 0
                                    && !JclSupport.status(dd).equals("SHR")
                                    && !JclSupport.status(dd).equals("OLD"))
                            .ifPresent(g -> createdBy.putIfAbsent(g.name(), step));
                }
            }
        }
        return findings;
    }

    private static Optional<JclDataset> generation(JclDdStatement dd) {
        return JclSupport.dataset(dd).filter(dataset -> dataset.gdgRelative().isPresent());
    }
}
