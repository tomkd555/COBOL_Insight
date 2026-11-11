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
import java.util.Set;

/**
 * R067 A DD that names a catalogued data set and writes no DISP. The default is
 * {@code (NEW,DELETE)}: the system tries to create the data set rather than open it. With the
 * name already catalogued the allocation fails; where it does not, the data set is deleted when
 * the step ends. A DD that really creates a data set says how large it is, so a DD with neither
 * DISP nor a space request is read as a reference with its DISP left out.
 */
public final class DispOmittedRule implements Rule {

    /** The parameters that say the DD allocates something new, or is no data set at all. */
    private static final Set<String> ALLOCATION = Set.of("SPACE", "DATACLAS", "LIKE", "REFDD",
            "PATH", "SUBSYS", "SYSOUT", "AMP", "LABEL", "DDNAME");

    private static final RuleMeta META = RuleMeta
            .named("R067", "既存データセットを指す DD の DISP 省略", RuleCategory.ABEND)
            .technology(Technology.JCL)
            .symptom("割り当てエラー（重複名）・ステップ終了時のデータセット削除")
            .summary("データセット名を持つ DD のうち、DISP も新規割り当ての指定も持たないものを"
                    + "検出します。")
            .rationale("DISP を省略すると (NEW,DELETE) になります。既存のデータセットを新規に"
                    + "作ろうとして割り当てが失敗するか、ステップの終了時にデータセットが"
                    + "削除されます。")
            .detection("DD 文のうち、DSN を持ち、DISP がなく、SPACE・DATACLAS・LIKE など"
                    + "新規割り当てを示すパラメーターもないものを検出します。"
                    + "一時データセット、DUMMY、SYSOUT、ストリーム内データ、PATH・SUBSYS の DD は"
                    + "対象外です。")
            .remedy("読むだけなら DISP=SHR、排他で更新するなら DISP=OLD を指定してください。")
            .example("""
                    //INFILE  DD DSN=PROD.URIAGE.DAILY
                    """, """
                    //INFILE  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
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
            for (JclStep step : JclSupport.programSteps(job)) {
                for (JclDdStatement dd : step.ddStatements()) {
                    Optional<JclDataset> dataset = JclSupport.dataset(dd)
                            .filter(d -> !d.temporary());
                    if (dataset.isEmpty() || dd.dispositionText().isPresent()
                            || dd.sysout().isPresent() || !dd.inStreamData().isEmpty()
                            || ALLOCATION.stream().anyMatch(key -> JclSupport.has(dd, key))) {
                        continue;
                    }
                    report.add(dd.position(), step.name() + " の DD " + dd.ddName() + " は "
                            + dataset.get().name() + " を指していますが、DISP がありません。"
                            + "(NEW,DELETE) として扱われ、割り当てが失敗するか、"
                            + "ステップの終了時にデータセットが削除されます。");
                }
            }
        }
        return findings;
    }
}
