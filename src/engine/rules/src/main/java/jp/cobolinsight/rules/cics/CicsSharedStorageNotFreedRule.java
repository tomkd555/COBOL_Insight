package jp.cobolinsight.rules.cics;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleCategory;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.rule.Technology;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R087 A GETMAIN SHARED with no FREEMAIN. Task storage is returned when the task ends; shared
 * storage is not — it stays until some program frees it or the region is restarted. A transaction
 * that takes a shared area on every run and frees none drains the dynamic storage area a little at
 * a time, and the region goes short on storage days after the change that caused it.
 */
public final class CicsSharedStorageNotFreedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R087", "FREEMAIN のない GETMAIN SHARED", RuleCategory.PERFORMANCE)
            .technology(Technology.CICS)
            .symptom("記憶域不足（SOS）")
            .summary("SHARED を指定した GETMAIN を持ちながら、どのプログラムも FREEMAIN を"
                    + "実行しないものを検出します。")
            .rationale("共用記憶域はタスクが終わっても解放されません。実行のたびに確保すると"
                    + "動的記憶域が少しずつ減り、領域が記憶域不足になります。")
            .detection("EXEC CICS GETMAIN のうち SHARED を持つものについて、解析対象の"
                    + "いずれかのプログラムに EXEC CICS FREEMAIN があるかを確かめ、"
                    + "1 つもないときに検出します。確保した領域を別のプログラムが解放する構成に"
                    + "配慮して、FREEMAIN の有無は解析対象の全体で判定します。")
            .remedy("共用記憶域を使い終えたプログラムで EXEC CICS FREEMAIN を実行してください。"
                    + "タスクの中だけで使う領域なら SHARED を外してください。")
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<CicsCommands.Command> shared = new ArrayList<>();
        boolean freed = false;
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (CicsCommands.Command command : CicsCommands.of(model)) {
                if (command.is("GETMAIN") && command.has("SHARED")) {
                    shared.add(command);
                }
                freed |= command.is("FREEMAIN");
            }
        }
        List<Finding> findings = new ArrayList<>();
        if (!freed) {
            for (CicsCommands.Command command : shared) {
                findings.add(CicsCommands.finding(META, command,
                        "GETMAIN SHARED で確保した記憶域を、解析対象のどのプログラムも "
                                + "FREEMAIN していません。タスクが終わっても解放されず、"
                                + "領域の記憶域が減り続けます。"));
            }
        }
        return findings;
    }
}
