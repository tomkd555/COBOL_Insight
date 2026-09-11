package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
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
            RuleMeta.named("R030", "JCL ステップ間の COND パラメーター未指定", "JCL制御")
            .summary("ジョブの 2 番目以降のステップで COND パラメーターを持たないものを"
                    + "検出します。")
            .rationale("先行ステップが異常終了しても後続が実行され、"
                    + "不完全なデータのまま処理が進みます。")
            .detection("ジョブの EXEC ステップのうち、COND パラメーターを持たず、"
                    + "IF/THEN/ELSE/ENDIF にも囲まれていないものを検出します。"
                    + "先行ステップのない先頭のステップは対象外です。"
                    + "PROC の中のステップは PROC の中で検査し、EXEC に付けた COND は"
                    + "その PROC のすべてのステップに及ぶものとして扱います。"
                    + "COND のない EXEC で呼び出した PROC については、その EXEC だけを報告します。")
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
                    findings.add(finding(step));
                }
            }
        }
        return findings;
    }

    private static Finding finding(JclStep step) {
        return Finding.of(META.id(), META.defaultSeverity().toLevel(),
                step.name() + " に COND パラメーターがありません。"
                        + "先行ステップの異常終了後も実行されます。",
                step.position());
    }
}
