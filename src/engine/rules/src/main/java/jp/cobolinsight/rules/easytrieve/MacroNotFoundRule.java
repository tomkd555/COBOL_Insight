package jp.cobolinsight.rules.easytrieve;

import jp.cobolinsight.core.easytrieve.EasytrieveProgram;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
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
 * R106 A macro invocation whose member is not among the assets. The macro usually holds the
 * record layout of a file, so without it the program's fields are unknown here: the rules that
 * compare a field with its record, or look for a field's NUMERIC test, have nothing to read. The
 * finding says how much of the program the analysis could not see, not that the program is wrong.
 */
public final class MacroNotFoundRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R106", "解析対象にない Easytrieve マクロの呼び出し",
                    RuleCategory.MAINTAINABILITY)
            .technology(Technology.EASYTRIEVE)
            .summary("%名前 で呼び出すマクロのうち、解析対象のフォルダーにメンバーがないものを"
                    + "検出します。")
            .rationale("マクロが定義するファイルと項目を読めないため、そのプログラムの"
                    + "項目に関する検査が行われません。")
            .detection("マクロの呼び出しごとに、解析対象のフォルダーから同じ名前の Easytrieve の"
                    + "メンバーを探し、見つからないものを検出します。プログラムの中に"
                    + "MSTART … MEND で定義したマクロは対象外です。")
            .remedy("マクロライブラリーのメンバーを解析対象のフォルダーに加えてください。")
            .severity(Severity.LOW)
            .commands(Command.LINT)
            .targets(AssetKind.EASYTRIEVE, AssetKind.JCL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (EasytrieveProgram program : EasytrieveSupport.programs(context)) {
            for (EasytrieveProgram.MacroUse use : program.macros()) {
                if (!use.resolved()) {
                    findings.add(EasytrieveSupport.finding(META, use.position(),
                            "マクロ " + use.name() + " のメンバーが解析対象にありません。"
                                    + "このマクロが定義する項目は検査されません。"));
                }
            }
        }
        return findings;
    }
}
