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
import java.util.Set;

/**
 * R061 A fixed-name data set created {@code NEW,CATLG} that nothing in the estate ever deletes.
 * The first run catalogues it; the second finds the name taken. Under SMS the allocation fails and
 * the job stops with a JCL error; without SMS the step runs and ends with NOT CATLGD 2, leaving an
 * uncatalogued copy behind.
 *
 * <p>The whole walk is asked, not the job alone: a clean-up step commonly stands at the head of
 * the same job, and as commonly in another job. A generation data set, a temporary data set, a PDS
 * member and a name with a symbol in it are a new data set on every run and are not reported.
 */
public final class NewDatasetNeverDeletedRule implements Rule {

    private static final Set<String> KEPT = Set.of("CATLG", "KEEP");

    private static final RuleMeta META = RuleMeta
            .named("R061", "どこからも削除されない固定名の新規データセット", RuleCategory.OPERATION)
            .technology(Technology.JCL)
            .symptom("NOT CATLGD 2・重複名による割り当てエラー（IGD17101I）")
            .summary("DISP=(NEW,CATLG) で作成する固定名のデータセットのうち、解析対象のどの"
                    + "ジョブも削除しないものを検出します。")
            .rationale("2 回目の実行で同じ名前のデータセットがすでにカタログされており、"
                    + "割り当てエラーでジョブが止まるか、NOT CATLGD 2 でカタログされない"
                    + "データセットが残ります。")
            .detection("DISP の状態が NEW（省略を含む）で正常終了時の処分が CATLG・KEEP の"
                    + "データセットについて、解析対象のすべてのジョブから、同じ名前を"
                    + "DISP の DELETE で削除する DD、IDCAMS・TSO の DELETE を探し、"
                    + "いずれもないものを検出します。GDG の世代、一時データセット、"
                    + "区分データセットのメンバー、記号やスケジューラー変数が残る名前は対象外です。"
                    + "データセット名 1 つにつき報告は 1 件です。")
            .remedy("ジョブの先頭で IEFBR14 の DISP=(MOD,DELETE) か IDCAMS の DELETE により"
                    + "前回のデータセットを削除するか、GDG にしてください。")
            .example("""
                    //STEP010 EXEC PGM=URI010
                    //OUTFILE DD DSN=PROD.URIAGE.SYUKEI,DISP=(NEW,CATLG,DELETE),
                    //           UNIT=SYSDA,SPACE=(CYL,(5,5))
                    """, """
                    //DELSTEP EXEC PGM=IEFBR14
                    //DELDD   DD DSN=PROD.URIAGE.SYUKEI,DISP=(MOD,DELETE,DELETE),
                    //           UNIT=SYSDA,SPACE=(TRK,0)
                    //STEP010 EXEC PGM=URI010
                    //OUTFILE DD DSN=PROD.URIAGE.SYUKEI,DISP=(NEW,CATLG,DELETE),
                    //           UNIT=SYSDA,SPACE=(CYL,(5,5))
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
        Set<String> deleted = new java.util.HashSet<>();
        List<String> deletedPrefixes = new ArrayList<>();
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : JclSupport.programSteps(job)) {
                for (JclDdStatement dd : step.ddStatements()) {
                    if (JclSupport.normal(dd).equals("DELETE")) {
                        JclSupport.dataset(dd).ifPresent(dataset ->
                                deleted.add(JclSupport.upper(dataset.name())));
                    }
                }
                step.utility().ifPresent(facts -> facts.datasetUses().forEach(use -> {
                    if (use.access() != jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess
                            .DELETE) {
                        return;
                    }
                    String name = JclSupport.upper(use.dataset());
                    int star = name.indexOf('*');
                    if (star >= 0) {
                        // A generic name: PROD.URIAGE.* deletes everything under the prefix.
                        deletedPrefixes.add(name.substring(0, star));
                    } else {
                        deleted.add(name);
                    }
                }));
            }
        }
        List<Finding> findings = new ArrayList<>();
        Map<String, JclDdStatement> firstCreation = new LinkedHashMap<>();
        Map<String, JclStep> stepOf = new HashMap<>();
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : JclSupport.programSteps(job)) {
                for (JclDdStatement dd : step.ddStatements()) {
                    JclDataset dataset = JclSupport.dataset(dd).orElse(null);
                    if (dataset == null || dataset.temporary() || dataset.member().isPresent()
                            || dataset.gdgRelative().isPresent()
                            || !JclSupport.status(dd).equals("NEW")
                            || !KEPT.contains(JclSupport.normal(dd))) {
                        continue;
                    }
                    String name = JclSupport.upper(dataset.name());
                    if (deleted.contains(name)
                            || deletedPrefixes.stream().anyMatch(name::startsWith)) {
                        continue;
                    }
                    if (firstCreation.putIfAbsent(name, dd) == null) {
                        stepOf.put(name, step);
                    }
                }
            }
        }
        firstCreation.forEach((name, dd) -> findings.add(Finding.of(META.id(),
                META.defaultSeverity().toLevel(),
                stepOf.get(name).name() + " の DD " + dd.ddName() + " が DISP="
                        + dd.dispositionText().orElse("(NEW,CATLG)") + " で作成する " + name
                        + " を、解析対象のどのジョブも削除していません。"
                        + "2 回目の実行は同じ名前の重複で失敗します。",
                dd.position())));
        return findings;
    }
}
