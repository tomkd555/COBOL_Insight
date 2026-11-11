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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R062 A temporary data set referred to by a step it was never passed to. A {@code &&NAME} data
 * set lives from the step that creates it for as long as each step hands it on with
 * {@code PASS}; a step that keeps it with the default disposition, or deletes it, ends its life.
 * A later {@code DISP=OLD} or {@code SHR} reference then finds nothing, and the job stops with a
 * JCL error at that step — after the steps before it have already run.
 */
public final class TempDatasetNotPassedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R062", "PASS されていない一時データセットの参照", RuleCategory.ABEND)
            .technology(Technology.JCL)
            .symptom("JCL エラー（IEF212I DATA SET NOT FOUND）")
            .summary("先行ステップが PASS していない一時データセット（&&名前）を、"
                    + "DISP=OLD・SHR で参照する DD を検出します。")
            .rationale("一時データセットは、ステップが PASS で引き継がない限りそのステップの"
                    + "終了時に削除されます。後続ステップの割り当てが失敗し、先行ステップが"
                    + "実行された後でジョブが止まります。")
            .detection("ジョブのステップを順にたどり、一時データセットごとに、直前の参照が"
                    + "PASS で引き継いだかを追います。DISP の状態が OLD・SHR の参照のうち、"
                    + "同じジョブの先行ステップが作成していないか、PASS していないものを"
                    + "検出します。状態が NEW・MOD の DD は作成として扱います。")
            .remedy("作成するステップと、途中で参照するすべてのステップの DISP に PASS を"
                    + "指定してください。")
            .example("""
                    //STEP010 EXEC PGM=SORT
                    //SORTOUT DD DSN=&&SORTED,DISP=(NEW,DELETE),UNIT=SYSDA,SPACE=(CYL,(5,5))
                    //STEP020 EXEC PGM=URI020
                    //INFILE  DD DSN=&&SORTED,DISP=(OLD,DELETE)
                    """, """
                    //STEP010 EXEC PGM=SORT
                    //SORTOUT DD DSN=&&SORTED,DISP=(NEW,PASS),UNIT=SYSDA,SPACE=(CYL,(5,5))
                    //STEP020 EXEC PGM=URI020
                    //INFILE  DD DSN=&&SORTED,DISP=(OLD,DELETE)
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
            // Temporary data set to whether the last step that named it passed it on.
            Map<String, Boolean> passed = new HashMap<>();
            for (JclStep step : JclSupport.programSteps(job)) {
                Map<String, Boolean> afterStep = new LinkedHashMap<>();
                for (JclDdStatement dd : step.ddStatements()) {
                    JclDataset dataset = JclSupport.dataset(dd)
                            .filter(JclDataset::temporary).orElse(null);
                    if (dataset == null) {
                        continue;
                    }
                    String name = JclSupport.upper(dataset.name());
                    String status = JclSupport.status(dd);
                    boolean refers = status.equals("OLD") || status.equals("SHR");
                    if (refers && !passed.getOrDefault(name, false)
                            && !afterStep.containsKey(name)) {
                        report.add(dd.position(), name, step.name() + " の DD " + dd.ddName()
                                + " が参照する一時データセット " + dataset.name() + " は、"
                                + (passed.containsKey(name)
                                        ? "先行ステップが PASS していないため、すでに削除されています。"
                                        : "同じジョブの先行ステップで作成されていません。")
                                + "このステップの割り当てが失敗します。");
                    }
                    afterStep.put(name, JclSupport.normal(dd).equals("PASS"));
                }
                passed.putAll(afterStep);
            }
        }
        return findings;
    }
}
