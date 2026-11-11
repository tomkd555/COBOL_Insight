package jp.cobolinsight.rules.jcl;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R075 A BIND that defers its checks to run time or locks more than it needs.
 * {@code VALIDATE(RUN)} lets a plan bind although a table or a privilege is missing: the statement
 * is bound again on every execution, and fails there if the object is still missing.
 * {@code ISOLATION(RR)} keeps a lock on every row and page a statement looked at until the commit,
 * which is what turns one long batch program into timeouts for everyone else.
 */
public final class Db2BindOptionRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R075", "実行時に問題を持ち越す BIND オプション", RuleCategory.PERFORMANCE)
            .technology(Technology.DB2)
            .symptom("実行時の SQLCODE -204・-551、ロック待ちによる SQLCODE -911・-913")
            .summary("BIND・REBIND の VALIDATE(RUN) と ISOLATION(RR) を検出します。")
            .rationale("VALIDATE(RUN) は表や権限の不足を BIND で止めず、実行のたびに"
                    + "文を BIND し直して、不足が残っていれば実行時に失敗します。"
                    + "ISOLATION(RR) は参照した行とページのロックをすべて COMMIT まで保持し、"
                    + "他のプログラムをロック待ちでタイムアウトさせます。")
            .detection("IKJEFT01 のストリーム内の BIND PLAN・BIND PACKAGE・REBIND について、"
                    + "VALIDATE(RUN) と ISOLATION(RR) を検出します。オプションを書かない BIND は、"
                    + "サブシステムの既定値によるため対象外です。")
            .remedy("VALIDATE(BIND) を指定して BIND の時点で検査してください。分離レベルは"
                    + "ISOLATION(CS) を基本にし、必要な文だけに WITH RR・WITH RS を書いてください。")
            .example("""
                    BIND PLAN(URIPLAN) MEMBER(URI010) VALIDATE(RUN) ISOLATION(RR)
                    """, """
                    BIND PLAN(URIPLAN) MEMBER(URI010) VALIDATE(BIND) ISOLATION(CS)
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
            for (JclStep step : JclSupport.programSteps(job)) {
                for (JclUtilityFacts.BindRequest bind : step.utility()
                        .map(JclUtilityFacts::binds).orElseGet(List::of)) {
                    String target = (bind.kind().endsWith("PACKAGE") ? "パッケージ " : "プラン ")
                            + bind.name();
                    if (option(bind, "VALIDATE").startsWith("R")) {
                        report.add(step.position(), bind.name() + ":V", step.name() + " の"
                                + target + " の BIND に VALIDATE(RUN) があります。"
                                + "表や権限の不足が BIND で検出されず、実行時に失敗します。");
                    }
                    if (option(bind, "ISOLATION").equals("RR")) {
                        report.add(step.position(), bind.name() + ":I", step.name() + " の"
                                + target + " の BIND に ISOLATION(RR) があります。"
                                + "参照した行のロックを COMMIT まで保持し、他のプログラムを"
                                + "ロック待ちにします。");
                    }
                }
            }
        }
        return findings;
    }

    private static String option(JclUtilityFacts.BindRequest bind, String keyword) {
        return bind.options().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(keyword))
                .map(entry -> JclSupport.upper(entry.getValue()).strip())
                .findFirst().orElse("");
    }
}
