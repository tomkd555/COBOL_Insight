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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R068 A copy or sort step whose input and output are the same data set. The utility opens the
 * output for writing while it still reads the input: a copy destroys what it has not yet read, and
 * a sort that fails part-way leaves neither the old data nor the new.
 */
public final class SameDatasetInOutRule implements Rule {

    private record Roles(java.util.regex.Pattern input, java.util.regex.Pattern output) {
    }

    private static final Roles COPY = new Roles(java.util.regex.Pattern.compile("SYSUT1"),
            java.util.regex.Pattern.compile("SYSUT2"));
    private static final Roles SORT = new Roles(
            java.util.regex.Pattern.compile("SORTIN(\\d\\d)?"),
            java.util.regex.Pattern.compile("SORTOUT|SORTOF\\w{1,2}"));

    private static final Map<String, Roles> BY_PROGRAM = Map.of("IEBGENER", COPY,
            "ICEGENER", COPY, "SORT", SORT, "ICEMAN", SORT, "DFSORT", SORT, "SYNCSORT", SORT);

    private static final RuleMeta META = RuleMeta
            .named("R068", "入力と出力に同じデータセットを指定した複写・並べ替え",
                    RuleCategory.INTEGRITY)
            .technology(Technology.UTILITY)
            .symptom("入力データの消失")
            .summary("IEBGENER・SORT のステップで、入力の DD と出力の DD が同じデータセットを"
                    + "指しているものを検出します。")
            .rationale("読み終える前の入力を出力が上書きします。途中で失敗すると、元のデータも"
                    + "結果も残りません。")
            .detection("IEBGENER・ICEGENER の SYSUT1 と SYSUT2、SORT・ICEMAN・DFSORT・SYNCSORT の"
                    + "SORTIN と SORTOUT・SORTOFxx について、データセット名・メンバー・相対世代が"
                    + "すべて一致するものを検出します。DUMMY と一時データセットは対象外です。")
            .remedy("出力を別のデータセットにし、後続のステップで名前を付け替えるか、"
                    + "GDG の新しい世代へ出力してください。")
            .example("""
                    //SORT010 EXEC PGM=SORT
                    //SORTIN  DD DSN=PROD.URIAGE.DAILY,DISP=OLD
                    //SORTOUT DD DSN=PROD.URIAGE.DAILY,DISP=OLD
                    """, """
                    //SORT010 EXEC PGM=SORT
                    //SORTIN  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
                    //SORTOUT DD DSN=PROD.URIAGE.SORTED,DISP=(NEW,CATLG,DELETE)
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
                Roles roles = BY_PROGRAM.get(JclSupport.upper(step.target()));
                if (roles == null) {
                    continue;
                }
                Map<JclDataset, String> inputs = new LinkedHashMap<>();
                for (JclDdStatement dd : step.ddStatements()) {
                    if (roles.input().matcher(JclSupport.upper(dd.ddName())).matches()) {
                        JclSupport.dataset(dd).filter(d -> !d.temporary())
                                .ifPresent(d -> inputs.putIfAbsent(identity(d), dd.ddName()));
                    }
                }
                for (JclDdStatement dd : step.ddStatements()) {
                    if (!roles.output().matcher(JclSupport.upper(dd.ddName())).matches()) {
                        continue;
                    }
                    JclSupport.dataset(dd).filter(d -> !d.temporary()).ifPresent(d -> {
                        String input = inputs.get(identity(d));
                        if (input != null) {
                            report.add(dd.position(), step.name() + " の出力 " + dd.ddName()
                                    + " は、入力 " + input + " と同じ "
                                    + dd.datasetName().orElse(d.name()) + " を指しています。"
                                    + "読み終える前の入力を上書きします。");
                        }
                    });
                }
            }
        }
        return findings;
    }

    /** The data set without the referback it was written as, so two spellings of one meet. */
    private static JclDataset identity(JclDataset dataset) {
        return new JclDataset(JclSupport.upper(dataset.name()),
                dataset.member().map(JclSupport::upper), dataset.gdgRelative(), false,
                Optional.empty());
    }
}
