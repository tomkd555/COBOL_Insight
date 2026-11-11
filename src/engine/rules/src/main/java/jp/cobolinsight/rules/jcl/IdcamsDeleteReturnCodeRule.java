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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R073 An IDCAMS delete-and-define that leaves the return code of the DELETE standing. On the
 * first run, and after any run that already cleaned up, the entry is not there: DELETE ends with
 * condition code 8, IDCAMS carries on, and the step ends with 8 although everything it was meant
 * to do worked. The steps behind it are then skipped by their COND, or the scheduler marks the job
 * as failed. {@code SET MAXCC = 0} after the DELETE, or an {@code IF LASTCC} around it, is what
 * says the missing entry is expected.
 *
 * <p>Only a DELETE whose entry is created again — by a DEFINE in the same SYSIN, or by a later
 * step of the job — is reported: a DELETE of something that must exist is right to fail.
 */
public final class IdcamsDeleteReturnCodeRule implements Rule {

    private static final Pattern DELETE = Pattern.compile(
            "^DEL(?:ETE)?\\s+\\(?\\s*([^)\\s]+(?:\\s+[A-Z0-9.@#$']+)*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINE_NAME =
            Pattern.compile("\\bNAME\\s*\\(\\s*'?([A-Z0-9.@#$]+)'?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITION_CODE = Pattern.compile(
            "\\b(SET\\s+(MAXCC|LASTCC)|IF\\s+(MAXCC|LASTCC))\\b", Pattern.CASE_INSENSITIVE);

    private static final RuleMeta META = RuleMeta
            .named("R073", "戻りコードを戻さない IDCAMS の削除と再定義", RuleCategory.OPERATION)
            .technology(Technology.UTILITY)
            .symptom("初回実行・再実行時の戻りコード 8")
            .summary("IDCAMS で DELETE した後に作り直すエントリーについて、SET MAXCC も"
                    + "IF LASTCC も書いていないものを検出します。")
            .rationale("エントリーが存在しない初回の実行や再実行で DELETE が条件コード 8 を返し、"
                    + "処理は成功しているのにステップが 8 で終わります。後続ステップが COND で"
                    + "実行されないか、ジョブが異常として扱われます。")
            .detection("IDCAMS のストリーム内の制御文を読み、DELETE の対象が、同じ SYSIN の"
                    + "後ろの DEFINE か、同じジョブの後続ステップの新規割り当てで作り直されるものを"
                    + "対象にします。その DELETE より後ろに SET MAXCC・SET LASTCC・IF MAXCC・"
                    + "IF LASTCC のいずれもないものを検出します。制御文がデータセットにある"
                    + "ステップは読めないため対象外です。")
            .remedy("DELETE の直後に SET MAXCC = 0 を書くか、IF LASTCC = 8 THEN SET MAXCC = 0 で"
                    + "存在しない場合だけを正常として扱ってください。")
            .example("""
                    //SYSIN DD *
                      DELETE PROD.URIAGE.KSDS CLUSTER PURGE
                      DEFINE CLUSTER (NAME(PROD.URIAGE.KSDS) INDEXED KEYS(8 0))
                    """, """
                    //SYSIN DD *
                      DELETE PROD.URIAGE.KSDS CLUSTER PURGE
                      IF LASTCC = 8 THEN SET MAXCC = 0
                      DEFINE CLUSTER (NAME(PROD.URIAGE.KSDS) INDEXED KEYS(8 0))
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
            List<JclStep> steps = JclSupport.programSteps(job);
            for (int i = 0; i < steps.size(); i++) {
                JclStep step = steps.get(i);
                if (!step.target().equalsIgnoreCase("IDCAMS")) {
                    continue;
                }
                List<String> commands = commands(JclSupport.cards(step, "SYSIN"));
                Set<String> createdLater = createdAfter(steps, i);
                for (int at = 0; at < commands.size(); at++) {
                    Matcher delete = DELETE.matcher(commands.get(at));
                    if (!delete.find()) {
                        continue;
                    }
                    List<String> rest = commands.subList(at + 1, commands.size());
                    if (rest.stream().anyMatch(c -> CONDITION_CODE.matcher(c).find())) {
                        continue;
                    }
                    for (String entry : delete.group(1).split("\\s+")) {
                        String name = JclSupport.upper(entry.replace("'", ""));
                        if (!name.contains(".")) {
                            // CLUSTER, PURGE and the other keywords of the command.
                            continue;
                        }
                        boolean redefined = rest.stream().anyMatch(c -> definedNames(c)
                                .contains(name));
                        if (redefined || createdLater.contains(name)) {
                            report.add(step.position(), name, step.name() + " の IDCAMS は "
                                    + name + " を DELETE した後、戻りコードを戻していません。"
                                    + "エントリーが存在しない実行では条件コード 8 が残り、"
                                    + "ステップが 8 で終わります。");
                        }
                    }
                }
            }
        }
        return findings;
    }

    /** The data sets the steps after {@code index} allocate new. */
    private static Set<String> createdAfter(List<JclStep> steps, int index) {
        Set<String> created = new java.util.HashSet<>();
        for (JclStep later : steps.subList(index + 1, steps.size())) {
            for (JclDdStatement dd : later.ddStatements()) {
                if (dd.dispositionText().isPresent() && JclSupport.status(dd).equals("NEW")) {
                    JclSupport.dataset(dd).map(JclDataset::name).map(JclSupport::upper)
                            .ifPresent(created::add);
                }
            }
        }
        return created;
    }

    private static Set<String> definedNames(String command) {
        Set<String> names = new java.util.HashSet<>();
        if (!command.regionMatches(true, 0, "DEF", 0, 3)) {
            return names;
        }
        Matcher name = DEFINE_NAME.matcher(command);
        while (name.find()) {
            names.add(JclSupport.upper(name.group(1)));
        }
        return names;
    }

    /** The commands of a SYSIN: a line ending in {@code -} or {@code +} continues on the next. */
    private static List<String> commands(List<String> lines) {
        List<String> commands = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : lines) {
            String line = (raw.length() > 72 ? raw.substring(0, 72) : raw).strip();
            if (line.isEmpty() || line.startsWith("/*")) {
                continue;
            }
            boolean continued = line.endsWith("-") || line.endsWith("+");
            current.append(continued ? line.substring(0, line.length() - 1) : line).append(' ');
            if (!continued) {
                commands.add(current.toString().strip());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            commands.add(current.toString().strip());
        }
        return commands;
    }
}
