package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R074 A LOAD or REORG run with {@code LOG NO} and no image copy behind it. With logging off the
 * utility leaves the table space in COPY-pending status, because what it wrote cannot be recovered
 * from the log: until an image copy is taken, every INSERT, UPDATE and DELETE against the table
 * fails. The load job ends with return code 4, which reads as a success, and the failures start in
 * the applications that run next.
 */
public final class Db2UtilityCopyPendingRule implements Rule {

    private static final Pattern STATEMENT_START = Pattern.compile(
            "(?m)^\\s*(LOAD|REORG|COPY|RUNSTATS|REBUILD|CHECK|RECOVER|REPAIR|QUIESCE|MODIFY"
                    + "|UNLOAD|TEMPLATE|LISTDEF|OPTIONS|MERGECOPY|REPORT|STOSPACE|EXEC\\s+SQL)\\b");
    private static final Pattern LOG_NO = Pattern.compile("\\bLOG\\s+NO\\b");
    private static final Pattern INLINE_COPY =
            Pattern.compile("\\b(COPYDDN|RECOVERYDDN|NOCOPYPEND)\\b");
    private static final Pattern IMAGE_COPY =
            Pattern.compile("^\\s*COPY\\s+(TABLESPACE|LIST)\\b|\\bNOCOPYPEND\\b");

    private static final RuleMeta META = RuleMeta
            .named("R074", "LOG NO の LOAD・REORG の後のイメージコピー欠如", RuleCategory.OPERATION)
            .technology(Technology.DB2)
            .symptom("COPY 保留による更新不可（SQLCODE -904）")
            .summary("LOG NO を指定した LOAD・REORG の後に、イメージコピーを取得しない"
                    + "Db2 ユーティリティのジョブを検出します。")
            .rationale("表スペースが COPY 保留状態になり、イメージコピーを取るまで"
                    + "INSERT・UPDATE・DELETE がすべて失敗します。ユーティリティは戻りコード 4 で"
                    + "終わるため、後続のアプリケーションが失敗して初めて気づきます。")
            .detection("DSNUTILB のストリーム内の制御文を読み、LOG NO を持つ LOAD・REORG のうち、"
                    + "その文に COPYDDN・RECOVERYDDN・NOCOPYPEND がなく、同じジョブのそれより後ろに"
                    + "COPY TABLESPACE・COPY LIST も REPAIR の NOCOPYPEND もないものを検出します。"
                    + "制御文がデータセットにあるステップは読めないため対象外です。")
            .remedy("LOAD・REORG に COPYDDN を指定してインラインコピーを取るか、"
                    + "直後のステップで COPY TABLESPACE を実行してください。")
            .example("""
                    LOAD DATA INDDN SYSREC LOG NO REPLACE
                      INTO TABLE URIDB.URIAGE
                    """, """
                    LOAD DATA INDDN SYSREC LOG NO REPLACE COPYDDN(SYSCOPY)
                      INTO TABLE URIDB.URIAGE
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
            // Every utility statement of the job in order, with the step it stands in.
            List<Map.Entry<JclStep, String>> statements = new ArrayList<>();
            for (JclStep step : JclSupport.programSteps(job)) {
                if (step.target().equalsIgnoreCase("DSNUTILB")) {
                    for (String statement : statements(JclSupport.cards(step, "SYSIN"))) {
                        statements.add(Map.entry(step, statement));
                    }
                }
            }
            for (int i = 0; i < statements.size(); i++) {
                String statement = statements.get(i).getValue();
                boolean loads = statement.startsWith("LOAD") || statement.startsWith("REORG");
                if (!loads || !LOG_NO.matcher(statement).find()
                        || INLINE_COPY.matcher(statement).find()) {
                    continue;
                }
                boolean copied = statements.subList(i + 1, statements.size()).stream()
                        .anyMatch(later -> IMAGE_COPY.matcher(later.getValue()).find());
                if (!copied) {
                    JclStep step = statements.get(i).getKey();
                    String utility = statement.startsWith("LOAD") ? "LOAD" : "REORG";
                    report.add(step.position(), utility + i, step.name() + " の " + utility
                            + " は LOG NO ですが、後ろにイメージコピーがありません。"
                            + "表スペースが COPY 保留状態になり、更新がすべて失敗します。");
                }
            }
        }
        return findings;
    }

    /** The utility statements of a SYSIN, upper case, each from its verb to the next verb. */
    private static List<String> statements(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String raw : lines) {
            String line = raw.length() > 72 ? raw.substring(0, 72) : raw;
            int comment = line.indexOf("--");
            text.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        String upper = text.toString().toUpperCase(Locale.ROOT);
        List<Integer> starts = new ArrayList<>();
        Matcher start = STATEMENT_START.matcher(upper);
        while (start.find()) {
            starts.add(start.start(1));
        }
        List<String> statements = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : upper.length();
            statements.add(upper.substring(starts.get(i), end).replaceAll("\\s+", " ").strip());
        }
        return statements;
    }
}
