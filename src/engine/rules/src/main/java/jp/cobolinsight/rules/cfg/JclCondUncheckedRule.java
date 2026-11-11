package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R030 JCL preceding-step result unchecked. Flags a job's second and later steps that lack a
 * COND clause even though they can depend on a preceding step's outcome. Without one, later
 * steps still run even after a preceding step ends abnormally.
 *
 * <p>A step inside IF/THEN/ELSE/ENDIF carries the IF as its condition, so it is not reported. The
 * steps a PROC call expands to are judged inside the PROC: the first of them is the call itself,
 * a later one is reported only when neither it nor any enclosing call carries a condition, since
 * a COND on the EXEC of a PROC applies to every step of the PROC. A call reported for its own
 * missing COND stands for every step it expands to, and a step expanded from a PROC two jobs
 * call is reported once, at its line in the PROC member.
 */
public final class JclCondUncheckedRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R030", "戻りコードを判定しないステップ", RuleCategory.OPERATION)
            .technology(Technology.JCL)
            .symptom("異常な戻りコードの後の続行")
            .summary("ジョブの 2 番目以降のステップのうち、COND パラメーターも IF も"
                    + "持たないものを、ジョブごとにまとめて検出します。")
            .rationale("先行ステップが 8 や 12 の戻りコードで終わっても、異常終了でない限り"
                    + "後続のステップは実行され、不完全なデータのまま処理が進みます。")
            .detection("ジョブの EXEC ステップのうち、COND パラメーターを持たず、"
                    + "IF/THEN/ELSE/ENDIF にも囲まれていないものを検出します。"
                    + "先行ステップのない先頭のステップは対象外です。"
                    + "PROC の中のステップは PROC の中で検査し、EXEC に付けた COND は"
                    + "その PROC のすべてのステップに及ぶものとして扱います。"
                    + "COND のない EXEC で呼び出した PROC については、その EXEC だけを数えます。"
                    + "報告はジョブにつき 1 件で、該当するステップを本文に挙げます。")
            .remedy("COND パラメーターを付けるか、IF/THEN/ELSE で先行ステップの戻りコードを"
                    + "検査してから実行してください。")
            .example("""
                    //STEP02 EXEC PGM=SYK002
                    """, """
                    //STEP02 EXEC PGM=SYK002,COND=(4,LT,STEP01)
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
        Set<String> reportedAt = new HashSet<>();
        for (JclJobModel job : context.jclJobs()) {
            // One finding stands for the job: a job that judges no return code at all would
            // otherwise report every one of its steps, and the reader has one decision to make.
            List<JclStep> unguarded = new ArrayList<>();
            // PROC calls seen so far, by step name; a step named call.xxx was expanded from call.
            Map<String, JclStep> calls = new LinkedHashMap<>();
            Set<String> reportedCalls = new HashSet<>();
            Set<String> callsWithAStep = new HashSet<>();
            List<JclStep> steps = job.steps();
            for (int i = 0; i < steps.size(); i++) {
                JclStep step = steps.get(i);
                List<JclStep> enclosing = calls.values().stream()
                        .filter(call -> step.name().startsWith(call.name() + "."))
                        .toList();
                if (step.execKind() == JclExecKind.PROC) {
                    calls.put(step.name(), step);
                }
                boolean reported;
                if (enclosing.isEmpty()) {
                    reported = i > 0 && step.condition().isEmpty();
                } else {
                    JclStep innermost = enclosing.stream()
                            .max(Comparator.comparingInt(call -> call.name().length()))
                            .orElseThrow();
                    boolean firstOfCall = callsWithAStep.add(innermost.name());
                    boolean guarded = step.condition().isPresent() || enclosing.stream()
                            .anyMatch(call -> call.condition().isPresent()
                                    || reportedCalls.contains(call.name()));
                    reported = !firstOfCall && !guarded;
                }
                if (!reported) {
                    continue;
                }
                reportedCalls.add(step.name());
                if (reportedAt.add(step.position().file() + ":" + step.position().line())) {
                    unguarded.add(step);
                }
            }
            if (!unguarded.isEmpty()) {
                findings.add(finding(job, unguarded));
            }
        }
        return findings;
    }

    /** How many step names a message spells out before it says how many more there are. */
    private static final int NAMED_STEPS = 5;

    private static Finding finding(JclJobModel job, List<JclStep> steps) {
        String names = String.join("・", steps.stream().limit(NAMED_STEPS)
                .map(JclStep::name).toList());
        if (steps.size() > NAMED_STEPS) {
            names += " ほか " + (steps.size() - NAMED_STEPS) + " ステップ";
        }
        return Finding.of(META.id(), META.defaultSeverity().toLevel(),
                "ジョブ " + job.jobName() + " の " + names + " は COND パラメーターも IF も"
                        + "持ちません。先行ステップが異常な戻りコードで終わっても実行されます。",
                steps.get(0).position());
    }
}
