package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclMemberMiss;
import jp.cobolinsight.core.jcl.JclOverrideMiss;
import jp.cobolinsight.core.jcl.JclReferbackMiss;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R050 A reference the job never answers. Five things the expansion could not resolve or could not
 * find, all of them carried by the job model and none of them a diagnostic of its own. The first
 * three are defects of the JCL and are reported as errors; the last two say only that the scan could
 * not read a member, which a run without {@code --proc-path} produces for every PROC of the shop, so
 * they are reported as notes the way R048 reports the same kind of gap:
 *
 * <ul>
 *   <li>{@code unresolvedSymbols}: an {@code &SYM} nothing in the job or its members gave a value
 *       to. The frontend has already taken out the system symbols and the scheduler tokens. A job
 *       whose INCLUDE member the walk could not read is reported at NOTE instead: a job-level
 *       INCLUDE puts the member's SET statements into the job's scope, so a member nobody read may
 *       hold the definition. An unread PROC member cannot: what a PROC defines stands inside its
 *       own expansion, which produced no step at all.</li>
 *   <li>{@code unresolvedOverrides}: a {@code //STEP.DD} or {@code PARM.STEP} override whose PROC
 *       carries no step or DD of that name.</li>
 *   <li>{@code unresolvedReferbacks}: a {@code *.step.dd} pointing at nothing.</li>
 *   <li>{@code missingMembers}: a PROC an EXEC calls, or a member an INCLUDE brings in, that the
 *       member library does not hold.</li>
 *   <li>A PROC call that left no body behind and that {@code missingMembers} does not name. An
 *       expanded call leaves its body as steps named {@code call.procstep}, so a PROC step with no
 *       such step after it was not expanded. The member library can hold the member and still not
 *       hold a PROC of that name, and the parser records nothing for that case, which is why this
 *       reading is kept beside {@code missingMembers} rather than replaced by it. It also catches
 *       the two cases the parser only writes a diagnostic for — a nesting too deep to follow and a
 *       PROC that calls itself — and a PROC that holds no step at all.</li>
 * </ul>
 */
public final class JclUndefinedReferenceRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R050", "解決できない JCL の参照", "JCL制御")
            .summary("値を与えられていない記号、相手のない上書き、参照先のない後方参照を"
                    + "ERROR で、見つからないメンバーと展開されなかった PROC 呼び出しを"
                    + "NOTE で検出します。")
            .rationale("実行時に JCL エラーとなってジョブが始まらないか、"
                    + "意図した上書きが行われないまま前の値で動きます。")
            .detection("ジョブの中に定義のない記号、PROC に相手のない //STEP.DD・PARM.STEP の"
                    + "上書き、参照先のない *.ステップ.DD、"
                    + "メンバーライブラリにない PROC・INCLUDE のメンバー、"
                    + "本体が展開されなかった PROC 呼び出しを検出します。"
                    + "システム記号とスケジューラーの記号は対象外です。"
                    + "メンバーはあるのにその名前の PROC を持たない場合は"
                    + "見つからないメンバーとして記録されないため、"
                    + "展開されなかった呼び出しとして報告します。"
                    + "前の 3 つは JCL の誤りとして ERROR で報告し、"
                    + "後の 2 つは解析できなかった範囲を示すだけなので NOTE で報告します。"
                    + "メンバーを読めなかった PROC 呼び出しの下に書かれた上書きと後方参照は、"
                    + "そのメンバーの中に相手があり得るため対象外です。"
                    + "読めなかった INCLUDE のメンバーがあるジョブでは、"
                    + "そのメンバーの SET が値を与えていることがあるため、"
                    + "記号は ERROR ではなく NOTE で報告します。")
            .remedy("記号に SET で値を与え、上書きと後方参照の名前を PROC の"
                    + "ステップ名・DD 名に合わせ、PROC のメンバーを解析対象に含めてください。")
            .example("""
                    //STEP010 EXEC PGM=SYK001,PARM='&CYCLE'
                    """, """
                    //         SET CYCLE=250718
                    //STEP010 EXEC PGM=SYK001,PARM='&CYCLE'
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
        // A member expanded into two jobs keeps its own line in both; the place is reported once.
        Set<String> reportedAt = new LinkedHashSet<>();
        for (JclJobModel job : context.jclJobs()) {
            Set<Integer> missingAt = new LinkedHashSet<>();
            for (JclMemberMiss miss : job.missingMembers()) {
                missingAt.add(miss.line());
            }
            Set<Integer> behindAMissingMember = behindAMissingMember(job, missingAt);
            boolean anIncludeIsMissing = job.missingMembers().stream()
                    .anyMatch(miss -> JclMemberMiss.INCLUDE.equals(miss.kind()));
            for (String symbol : job.unresolvedSymbols()) {
                add(findings, reportedAt, positionOf(job, symbol),
                        anIncludeIsMissing ? FindingLevel.NOTE : FindingLevel.ERROR,
                        anIncludeIsMissing
                                ? symbol + " に値を与える定義が見つかりません。"
                                        + "読めなかった INCLUDE のメンバーが "
                                        + "SET で値を与えていることもあります。"
                                : symbol + " に値を与える定義がありません。"
                                        + "実行時に置き換えられず、JCL エラーになります。");
            }
            for (JclOverrideMiss miss : job.unresolvedOverrides()) {
                if (behindAMissingMember.contains(miss.line())) {
                    continue;
                }
                add(findings, reportedAt, positionIn(job, miss.line()), FindingLevel.ERROR,
                        miss.text() + " の上書きの相手が PROC にありません。"
                                + "この上書きは行われず、PROC が書いた値のまま動きます。");
            }
            for (JclReferbackMiss miss : job.unresolvedReferbacks()) {
                if (behindAMissingMember.contains(miss.line())) {
                    continue;
                }
                add(findings, reportedAt, positionIn(job, miss.line()), FindingLevel.ERROR,
                        miss.text() + " の参照先がありません。"
                                + "参照先のデータセット名が決まらず、JCL エラーになります。");
            }
            for (JclMemberMiss miss : job.missingMembers()) {
                add(findings, reportedAt, positionIn(job, miss.line()), FindingLevel.NOTE,
                        miss.kind() + " メンバー " + miss.name() + " が見つかりません。"
                                + "このメンバーの内容は解析の対象外です。");
            }
            for (JclStep step : job.steps()) {
                if (step.execKind() == JclExecKind.PROC && !expanded(job, step)
                        && !missingAt.contains(step.position().line())) {
                    add(findings, reportedAt, step.position(), FindingLevel.NOTE,
                            "PROC " + step.target() + " の呼び出しは展開されていません。"
                                    + "この呼び出しの中のステップは解析の対象外です。");
                }
            }
        }
        return findings;
    }

    /**
     * The lines an unexpanded call whose member is missing covers: the EXEC statement itself and the
     * DD statements the model left on it, which an expanded call would have turned into overrides.
     * An override or a referback written there may name a step or a DD the member would have
     * supplied, so it is valid JCL that only looks unresolved because the member was not read.
     */
    private static Set<Integer> behindAMissingMember(JclJobModel job, Set<Integer> missingAt) {
        Set<Integer> lines = new LinkedHashSet<>();
        for (JclStep step : job.steps()) {
            if (step.execKind() != JclExecKind.PROC
                    || !missingAt.contains(step.position().line())) {
                continue;
            }
            lines.add(step.position().line());
            for (JclDdStatement dd : step.ddStatements()) {
                lines.add(dd.position().line());
            }
        }
        return lines;
    }

    private static SourcePosition positionIn(JclJobModel job, int line) {
        return new SourcePosition(job.sourceFile(), line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    /** Whether the call left a body behind: a step of the job named {@code call.procstep}. */
    private static boolean expanded(JclJobModel job, JclStep call) {
        String prefix = call.name() + ".";
        return job.steps().stream().anyMatch(step -> step.name().startsWith(prefix));
    }

    /**
     * Where the symbol is written. The model carries the unresolved symbols as names alone, so the
     * first place of the job that still spells the symbol stands in for it; a job that resolved the
     * symbol out of every text it keeps is reported at its JOB card.
     */
    private static SourcePosition positionOf(JclJobModel job, String symbol) {
        for (JclStep step : job.steps()) {
            if (mentions(step.parameters().values(), symbol) || mentions(List.of(step.target()),
                    symbol)) {
                return step.position();
            }
            for (JclDdStatement dd : step.ddStatements()) {
                if (mentions(dd.parameters().values(), symbol)) {
                    return dd.position();
                }
            }
        }
        return job.position();
    }

    /**
     * Whether one of the texts still spells the symbol. The match ends on a word boundary of the
     * JCL symbol alphabet, so {@code &MODE} does not land on {@code &MODEL}.
     */
    private static boolean mentions(Iterable<String> texts, String symbol) {
        Pattern spelled = Pattern.compile(Pattern.quote(symbol) + "(?![A-Za-z0-9@#$])");
        for (String text : texts) {
            if (text != null && spelled.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Finding> findings, Set<String> reportedAt,
            SourcePosition position, FindingLevel level, String message) {
        if (reportedAt.add(position.file() + ":" + position.line() + " " + message)) {
            findings.add(Finding.of(META.id(), level, message, position));
        }
    }
}
