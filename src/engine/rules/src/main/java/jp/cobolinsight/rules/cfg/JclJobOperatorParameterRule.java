package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * R053 A one-off operator parameter left on the JOB card. {@code RESTART=} skips every step up to
 * the one it names; {@code TYPRUN=SCAN} ends the job after the syntax check, and {@code HOLD} or
 * {@code JCLHOLD} leaves it waiting on the queue instead of running. Both keywords are written for
 * a single run, and both are silent when they survive into the production member: none of the work
 * the member describes is done.
 *
 * <p>Reported at the job's own position, which is its JOB card — not the first line of the file,
 * because a member may hold more than one job.
 */
public final class JclJobOperatorParameterRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R053", "JOB 文に残った RESTART・TYPRUN", "JCL制御")
            .summary("JOB 文に RESTART・TYPRUN が指定されているものを検出します。")
            .rationale("一時的な運用のための指定がそのまま残ると、"
                    + "先頭のステップが飛ばされたり、業務処理を実行しません。")
            .detection("JOB 文の RESTART・TYPRUN を検出します。"
                    + "どちらも 1 回の実行のために書く指定です。")
            .remedy("運用が終わったら JOB 文から削除してください。")
            .example("""
                    //SYKD010  JOB  (SYK1234),'SYK01',CLASS=A,RESTART=STEP020
                    """, """
                    //SYKD010  JOB  (SYK1234),'SYK01',CLASS=A
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
        for (JclJobModel job : context.jclJobs()) {
            add(findings, job, "RESTART", "指定したステップより前のステップが実行されず、"
                    + "前の実行の出力をそのまま使います。");
            add(findings, job, "TYPRUN", typrunConsequence(job.parameters().get("TYPRUN")));
        }
        return findings;
    }

    /**
     * What the value does. SCAN ends the job after the syntax check; HOLD and JCLHOLD leave it on
     * the queue until the operator releases it, which is not a normal end at all.
     */
    private static String typrunConsequence(String value) {
        String upper = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("HOLD") || upper.equals("JCLHOLD")) {
            return "ジョブが待ち状態のまま実行されず、"
                    + "解放するまで業務処理が進みません。";
        }
        if (upper.equals("SCAN")) {
            return "構文の検査だけで終わり、"
                    + "業務処理を実行しないまま正常終了します。";
        }
        return "ジョブがそのままでは実行されず、"
                + "業務処理が進みません。";
    }

    private static void add(List<Finding> findings, JclJobModel job, String keyword,
            String consequence) {
        String value = job.parameters().get(keyword);
        if (value == null) {
            return;
        }
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                job.jobName() + " の JOB 文に " + keyword
                        + (value.isBlank() ? "" : "=" + value) + " が残っています。" + consequence,
                job.position()));
    }
}
