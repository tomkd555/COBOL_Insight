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
 * R066 A data set name the system will not accept: a qualifier longer than eight characters or
 * starting with a digit, a name longer than 44 characters, a GDG base longer than 35, a member
 * name longer than eight. Each is a JCL error. They arrive with a renaming — a new prefix, a date
 * qualifier added to every name — that pushes a name which was valid past a limit.
 */
public final class DatasetNameInvalidRule implements Rule {

    private static final java.util.regex.Pattern QUALIFIER =
            java.util.regex.Pattern.compile("[A-Z@#$][A-Z0-9@#$-]{0,7}");

    private static final int NAME_LIMIT = 44;
    private static final int GDG_BASE_LIMIT = 35;

    private static final RuleMeta META = RuleMeta
            .named("R066", "規則に合わないデータセット名", RuleCategory.ABEND)
            .technology(Technology.JCL)
            .symptom("JCL エラー（データセット名の形式）")
            .summary("修飾子が 8 文字を超える、数字で始まる、全体が 44 文字を超えるなど、"
                    + "命名規則に合わないデータセット名を検出します。")
            .rationale("JCL エラーになり、ジョブが実行されません。接頭辞の変更や日付の修飾子の"
                    + "追加で、それまで正しかった名前が上限を超えます。")
            .detection("DD 文の DSN について、各修飾子が英字か @・#・$ で始まる 8 文字以内で"
                    + "あること、全体が 44 文字以内（GDG のベース名は 35 文字以内）であること、"
                    + "メンバー名が 8 文字以内であることを確かめます。一時データセット、"
                    + "記号やスケジューラー変数が残る名前、後方参照は対象外です。")
            .remedy("修飾子を 8 文字以内、全体を 44 文字以内に収めてください。")
            .example("""
                    //OUTFILE DD DSN=PROD.URIAGESYUKEI.D2025,DISP=SHR
                    """, """
                    //OUTFILE DD DSN=PROD.URISYUKE.D2025,DISP=SHR
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
                    JclSupport.dataset(dd).filter(dataset -> !dataset.temporary())
                            .flatMap(DatasetNameInvalidRule::problemOf)
                            .ifPresent(problem -> report.add(dd.position(), step.name()
                                    + " の DD " + dd.ddName() + " のデータセット名 "
                                    + dd.datasetName().orElse("") + " は" + problem
                                    + "JCL エラーになります。"));
                }
            }
        }
        return findings;
    }

    /** What is wrong with the name, as the middle of a sentence; empty when it is acceptable. */
    static Optional<String> problemOf(JclDataset dataset) {
        String name = JclSupport.upper(dataset.name());
        if (name.startsWith("'") || name.contains("/") || name.contains("(")) {
            // A quoted name or a path is not a name this rule reads.
            return Optional.empty();
        }
        for (String qualifier : name.split("\\.", -1)) {
            if (qualifier.length() > 8) {
                return Optional.of("、修飾子 " + qualifier + " が 8 文字を超えています。");
            }
            if (!QUALIFIER.matcher(qualifier).matches()) {
                return Optional.of("、修飾子 " + (qualifier.isEmpty() ? "（空）" : qualifier)
                        + " が命名規則に合いません。");
            }
        }
        if (name.length() > NAME_LIMIT) {
            return Optional.of("、全体が " + name.length() + " 文字で 44 文字を超えています。");
        }
        if (dataset.gdgRelative().isPresent() && name.length() > GDG_BASE_LIMIT) {
            return Optional.of("、GDG のベース名が " + name.length()
                    + " 文字で 35 文字を超えています。");
        }
        if (dataset.member().filter(member -> member.length() > 8).isPresent()) {
            return Optional.of("、メンバー名が 8 文字を超えています。");
        }
        return Optional.empty();
    }
}
