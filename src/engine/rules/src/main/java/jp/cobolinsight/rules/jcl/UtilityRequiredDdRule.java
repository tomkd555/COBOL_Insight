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
import java.util.Map;

/**
 * R064 A utility step without a DD the utility cannot run without. Each of the standard utilities
 * opens a fixed set of DD names — its message data set, its control statements, its input and
 * output — and ends at once, or abends, when one is missing. The mistake is usually a DD lost in a
 * copy of the step, and it is found only when the job runs.
 *
 * <p>The table holds only what every installation agrees on: a DD the utility replaces by a PARM,
 * an exit or a control statement is left out of it.
 */
public final class UtilityRequiredDdRule implements Rule {

    /** One requirement: any one of the alternatives satisfies it. */
    private record Required(List<String> alternatives) {
        static Required of(String... names) {
            return new Required(List.of(names));
        }
    }

    private static final Map<String, List<Required>> REQUIRED = Map.ofEntries(
            Map.entry("IDCAMS", List.of(Required.of("SYSPRINT"), Required.of("SYSIN"))),
            Map.entry("IEBGENER", List.of(Required.of("SYSPRINT"), Required.of("SYSUT1"),
                    Required.of("SYSUT2"), Required.of("SYSIN"))),
            Map.entry("ICEGENER", List.of(Required.of("SYSPRINT"), Required.of("SYSUT1"),
                    Required.of("SYSUT2"), Required.of("SYSIN"))),
            Map.entry("IEBCOPY", List.of(Required.of("SYSPRINT"))),
            Map.entry("IEBCOMPR", List.of(Required.of("SYSPRINT"), Required.of("SYSUT1"),
                    Required.of("SYSUT2"))),
            Map.entry("IEBPTPCH", List.of(Required.of("SYSPRINT"), Required.of("SYSUT1"),
                    Required.of("SYSUT2"), Required.of("SYSIN"))),
            Map.entry("IEBUPDTE", List.of(Required.of("SYSPRINT"), Required.of("SYSIN"))),
            Map.entry("SORT", sort()),
            Map.entry("ICEMAN", sort()),
            Map.entry("DFSORT", sort()),
            Map.entry("SYNCSORT", sort()),
            Map.entry("ICETOOL", List.of(Required.of("TOOLMSG"), Required.of("DFSMSG"),
                    Required.of("TOOLIN"))),
            Map.entry("IKJEFT01", tso()),
            Map.entry("IKJEFT1A", tso()),
            Map.entry("IKJEFT1B", tso()),
            Map.entry("DSNUTILB", List.of(Required.of("SYSPRINT"), Required.of("SYSIN"))),
            Map.entry("EZTPA00", List.of(Required.of("SYSPRINT"), Required.of("SYSIN"))),
            Map.entry("DFHCSDUP", List.of(Required.of("DFHCSD"), Required.of("SYSPRINT"),
                    Required.of("SYSIN"))),
            Map.entry("ADRDSSU", List.of(Required.of("SYSPRINT"), Required.of("SYSIN"))));

    /** The control statements of a sort may come from SYSIN, from DFSPARM or from the PARM. */
    private static List<Required> sort() {
        return List.of(Required.of("SYSOUT"), Required.of("SYSIN", "DFSPARM", "$ORTPARM"));
    }

    private static List<Required> tso() {
        return List.of(Required.of("SYSTSPRT"), Required.of("SYSTSIN"));
    }

    private static final RuleMeta META = RuleMeta
            .named("R064", "ユーティリティの必須 DD の欠落", RuleCategory.ABEND)
            .technology(Technology.UTILITY)
            .symptom("DD STATEMENT MISSING（IEC130I）・戻りコード 12 から 16")
            .summary("IDCAMS・IEBGENER・SORT・IKJEFT01 などのユーティリティのステップに、"
                    + "そのユーティリティが必ず開く DD がないものを検出します。")
            .rationale("ユーティリティは開始直後に DD を開けずに終了し、ステップが"
                    + "戻りコード 12 から 16 か異常終了で止まります。実行するまで気づけません。")
            .detection("EXEC PGM のユーティリティごとに、メッセージ・制御文・入出力の DD 名の表と"
                    + "ステップの DD 文を突き合わせ、ないものを検出します。PROC を展開した後の"
                    + "ステップで判定します。SORT の制御文は SYSIN・DFSPARM のいずれかがあれば"
                    + "足りるものとし、PARM を持つ SORT は制御文の DD を対象外にします。"
                    + "1 ステップにつき報告は 1 件で、欠けている DD 名をすべて本文に挙げます。")
            .remedy("欠けている DD 文を追加してください。出力が不要なメッセージは SYSOUT=*、"
                    + "制御文が不要な場合は DD DUMMY を指定します。")
            .example("""
                    //COPY010 EXEC PGM=IEBGENER
                    //SYSUT1  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
                    //SYSUT2  DD DSN=PROD.URIAGE.BACKUP,DISP=(NEW,CATLG,DELETE)
                    """, """
                    //COPY010 EXEC PGM=IEBGENER
                    //SYSPRINT DD SYSOUT=*
                    //SYSIN   DD DUMMY
                    //SYSUT1  DD DSN=PROD.URIAGE.DAILY,DISP=SHR
                    //SYSUT2  DD DSN=PROD.URIAGE.BACKUP,DISP=(NEW,CATLG,DELETE)
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
                String program = JclSupport.upper(step.target());
                List<Required> required = REQUIRED.get(program);
                if (required == null) {
                    continue;
                }
                boolean sortWithParm = sort().equals(required) && step.parm().isPresent();
                List<String> missing = new ArrayList<>();
                for (Required each : required) {
                    if (sortWithParm && each.alternatives().contains("SYSIN")) {
                        continue;
                    }
                    if (each.alternatives().stream().noneMatch(dd -> JclSupport.hasDd(step, dd))) {
                        missing.add(each.alternatives().get(0));
                    }
                }
                if (!missing.isEmpty()) {
                    report.add(step.position(), step.name() + "（" + program + "）に DD "
                            + String.join("・", missing) + " がありません。" + program
                            + " はこの DD を開けずに終了します。");
                }
            }
        }
        return findings;
    }
}
