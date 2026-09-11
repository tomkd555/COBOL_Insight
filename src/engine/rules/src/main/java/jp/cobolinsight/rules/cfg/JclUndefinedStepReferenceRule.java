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
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R047 A step name written in a COND parameter's third value ({@code (c,op,STEP[.PROCSTEP])}) or
 * in an IF condition ({@code STEP.RC}, {@code STEP.RUN}, {@code STEP.ABEND}, ...) that no earlier
 * step of the same job defines.
 *
 * <p>A step expanded from a PROC is registered under both its qualified name
 * ({@code STEP030.STEP1}) and its own local name inside the PROC ({@code STEP1}), since a COND or
 * IF written inside the PROC body names the sibling step the way the PROC itself sees it, while
 * one written in the job that calls the PROC names it fully qualified.
 */
public final class JclUndefinedStepReferenceRule implements Rule {

    private static final Pattern COND_STEP_NAME = Pattern.compile(
            "\\(\\s*\\d+\\s*,\\s*(?:EQ|NE|LT|LE|GT|GE)\\s*,\\s*"
                    + "([A-Za-z0-9$#@]+(?:\\.[A-Za-z0-9$#@]+)?)\\s*\\)");
    private static final Pattern IF_STEP_NAME = Pattern.compile(
            "(?i)([A-Z0-9$#@]+)\\.(?:ABENDCC|ABEND|RUN|RC)\\b");

    private static final RuleMeta META =
            RuleMeta.named("R047", "未定義ステップを参照する COND・IF", "JCL制御")
            .summary("COND パラメーターや IF 条件式が参照するステップ名のうち、"
                    + "そのジョブの先行するステップに定義されていないものを検出します。")
            .rationale("参照先のステップが実在しないため、戻りコードの検査が働かず、"
                    + "ジョブが意図した実行順序になりません。")
            .detection("COND パラメーターの三つ目の値、または IF 条件式の "
                    + "STEP.RC・STEP.RUN・STEP.ABEND が参照するステップ名のうち、"
                    + "そのジョブの先行するステップが定義していないものを検出します。"
                    + "PROC の中のステップは、PROC の中で使う自身の名前と、呼び出し元の"
                    + "ジョブで使う「呼び出し名.PROC内の名前」の両方で定義済みとして扱います。")
            .remedy("COND パラメーターや IF 条件式のステップ名を、"
                    + "実在する先行ステップの名前に直してください。")
            .example("""
                    //STEP020  EXEC PGM=FLB010,COND=(4,LT,STEP999)
                    """, """
                    //STEP020  EXEC PGM=FLB010,COND=(4,LT,STEP010)
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
        Set<String> reportedAt = new HashSet<>();
        for (JclJobModel job : context.jclJobs()) {
            Set<String> defined = new HashSet<>();
            for (JclStep step : job.steps()) {
                String condition = step.condition().orElse(null);
                if (condition != null) {
                    for (String reference : referencedSteps(condition)) {
                        if (!defined.contains(reference)) {
                            if (reportedAt.add(step.position().file() + ":"
                                    + step.position().line())) {
                                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                        reference + " は、このジョブの先行するステップで"
                                                + "定義されていません。戻りコードの検査が"
                                                + "働きません。",
                                        step.position()));
                            }
                            break;
                        }
                    }
                }
                defined.add(step.name());
                defined.add(localName(step.name()));
            }
        }
        return findings;
    }

    private static List<String> referencedSteps(String condition) {
        if (condition.startsWith("COND=")) {
            List<String> names = new ArrayList<>();
            Matcher matcher = COND_STEP_NAME.matcher(condition);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
        if (condition.startsWith("IF")) {
            List<String> names = new ArrayList<>();
            Matcher matcher = IF_STEP_NAME.matcher(condition);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        }
        return List.of();
    }

    private static String localName(String stepName) {
        int dot = stepName.lastIndexOf('.');
        return dot < 0 ? stepName : stepName.substring(dot + 1);
    }
}
