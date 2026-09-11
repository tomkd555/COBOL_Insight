package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R051 Two DD statements of one step carrying the same DD name. Only the statements that open a
 * DD ({@code concatIndex == 0}) are counted: the nameless statements concatenated under a DD carry
 * the same name by design and are not a second definition. The program opens the first statement of
 * the name, so the allocation every later one describes never reaches it.
 */
public final class JclDuplicateDdNameRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R051", "ステップ内の DD 名の重複", "JCL制御")
            .summary("同じステップに同じ DD 名の DD 文が 2 つ以上書かれているものを"
                    + "検出します。")
            .rationale("プログラムが開くのは最初に書いた DD だけで、"
                    + "後の DD が指すデータセットは使われません。")
            .detection("1 つのステップの DD 文のうち、DD 名が重複するものを検出します。"
                    + "連結のために名前を書かない DD 文は、"
                    + "先頭の DD と同じ名前を持ちますが対象外です。")
            .remedy("後の DD 文の名前を変えるか、"
                    + "不要な DD 文を削除してください。")
            .example("""
                    //ORDIN    DD DSN=SYKT.ORDER.DAILY,DISP=SHR
                    //ORDIN    DD DSN=SYKT.ORDER.EXTRA,DISP=SHR
                    """, """
                    //ORDIN    DD DSN=SYKT.ORDER.DAILY,DISP=SHR
                    //         DD DSN=SYKT.ORDER.EXTRA,DISP=SHR
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
        // A DD expanded from a PROC keeps its line in the PROC member; two jobs calling the PROC
        // would otherwise report it twice.
        Set<String> reportedAt = new LinkedHashSet<>();
        for (JclJobModel job : context.jclJobs()) {
            for (JclStep step : job.steps()) {
                Set<String> seen = new LinkedHashSet<>();
                for (JclDdStatement dd : step.ddStatements()) {
                    if (dd.concatIndex() != 0
                            || seen.add(dd.ddName().toUpperCase(Locale.ROOT))
                            || !reportedAt.add(dd.position().file() + ":"
                                    + dd.position().line())) {
                        continue;
                    }
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            step.name() + " に DD " + dd.ddName() + " が重ねて書かれています。"
                                    + "プログラムが開くのは最初に書いた DD だけで、"
                                    + "この DD は使われません。",
                            dd.position()));
                }
            }
        }
        return findings;
    }
}
