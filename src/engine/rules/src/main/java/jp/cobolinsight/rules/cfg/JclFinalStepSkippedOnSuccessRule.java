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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R046 The last step of a job carries its own COND parameter (not an enclosing IF) that bypasses
 * the step whenever every step it names ends with return code 0, so the step never runs on a
 * fully successful pass through the job.
 *
 * <p>A COND test {@code (code,operator)} is true, and the step bypassed, when
 * {@code code operator return-code} holds. A COND parameter may hold several such tests and the
 * step is bypassed if any one of them is true, so a job's last step is flagged when any of its
 * tests holds at return code 0. That depends only on the operator and the literal, not on which
 * step the test names: EQ and LE hold when the literal is 0, NE when it is not 0, GT when it is
 * positive, GE always (a JCL literal is never negative), and LT never.
 */
public final class JclFinalStepSkippedOnSuccessRule implements Rule {

    private static final Pattern COND_TEST = Pattern.compile(
            "\\(\\s*(\\d+)\\s*,\\s*(EQ|NE|LT|LE|GT|GE)\\s*(?:,[^)]*)?\\)");

    private static final RuleMeta META =
            RuleMeta.named("R046", "正常終了時に実行されない最終ステップ", "JCL制御")
            .summary("ジョブの最終ステップの COND パラメーターのうち、先行ステップの"
                    + "戻りコードがすべて 0 のときに真になるものを検出します。")
            .rationale("先行ステップがすべて正常終了しても最終ステップが読み飛ばされ、"
                    + "そのステップの処理が一度も実行されません。")
            .detection("ジョブの最終ステップが自分自身の COND パラメーターを持ち"
                    + "（囲む IF は対象外）、そのいずれかの条件が戻りコード 0 で真になるもの"
                    + "を検出します。条件は「数値 演算子 戻りコード」が成り立つとき真になります。"
                    + "戻りコード 0 で真になる組み合わせは、EQ・LE と 0、NE と 0 以外、"
                    + "GT と正の数、GE とすべての数値です。"
                    + "LT は真になりません。ステップが 1 つだけのジョブ、IF に囲まれた"
                    + "最終ステップ、ジョブレベルの COND パラメーターは対象外です。")
            .remedy("最終ステップを読み飛ばしてよい戻りコードの範囲に COND パラメーターを"
                    + "見直すか、IF/THEN で処理を明示的に分けてください。")
            .example("""
                    //STEP020  EXEC PGM=FLB010,COND=(0,EQ)
                    """, """
                    //STEP020  EXEC PGM=FLB010,COND=(0,NE)
                    """)
            .severity(Severity.ADVISORY)
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
            List<JclStep> steps = job.steps();
            if (steps.size() < 2) {
                continue;
            }
            JclStep last = steps.get(steps.size() - 1);
            String condition = last.condition().orElse(null);
            if (condition == null || !condition.startsWith("COND=")
                    || !bypassedOnSuccess(condition)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    last.name() + " は、先行ステップが正常終了すると読み飛ばされます。"
                            + "このステップの処理が一度も実行されません。",
                    last.position()));
        }
        return findings;
    }

    /** Whether any test of the COND text holds when every step it names has RC 0. */
    private static boolean bypassedOnSuccess(String condition) {
        Matcher matcher = COND_TEST.matcher(condition);
        while (matcher.find()) {
            int literal = Integer.parseInt(matcher.group(1));
            if (holdsAtZero(matcher.group(2), literal)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code literal operator 0} holds. */
    private static boolean holdsAtZero(String operator, int literal) {
        return switch (operator) {
            case "EQ", "LE" -> literal == 0;
            case "NE" -> literal != 0;
            case "GT" -> literal > 0;
            case "GE" -> true;
            default -> false; // LT: a JCL literal is never below 0
        };
    }
}
